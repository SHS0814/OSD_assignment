package com.example.rpiblecollector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int ENABLE_BLUETOOTH_REQUEST_CODE = 101;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 102;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 103;
    private static final int MAX_VISIBLE_ROWS = 100;
    private static final String PREFS_NAME = "collector_preferences";
    private static final String PREF_NOTIFICATION_PERMISSION_ASKED =
            "notification_permission_asked";
    private static final String PREF_LOCATION_PERMISSION_ASKED =
            "location_permission_asked";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<BleRecord> collectedRecords = new ArrayList<>();
    private final List<String> visibleRows = new ArrayList<>();

    private BluetoothAdapter bluetoothAdapter;
    private BleScanService scanService;
    private boolean serviceBound;
    private boolean scanning;
    private boolean saving;
    private long scanStartedAt;
    private long scanStoppedAt;
    private int validPacketCount;
    private String failureMessage;
    private int uploadSuccessCount;
    private int uploadFailureCount;
    private String lastServerMessage;
    /** 화면 값을 서비스 설정으로 되돌려 쓰는 동안에는 TextWatcher 를 무시한다. */
    private boolean applyingConfig;

    private TextView statusText;
    private TextView latestSensorText;
    private TextView logText;
    private TextView uploadStatusText;
    private ScrollView logScroll;
    private Button startButton;
    private Button stopButton;
    private Button saveButton;
    private Button uploadButton;
    private Button checkPageButton;
    private EditText teamInput;
    private EditText sensorInput;
    private EditText intervalInput;
    private CheckBox autoUploadCheck;
    private ArrayAdapter<String> scanListAdapter;

    private final Runnable elapsedTicker = new Runnable() {
        @Override
        public void run() {
            if (!scanning) {
                return;
            }
            updateStatusText();
            handler.postDelayed(this, 1000L);
        }
    };

    private final TextWatcher configWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            pushUploadConfig();
        }
    };

    private final BleScanService.Listener serviceListener = new BleScanService.Listener() {
        @Override
        public void onStateChanged(BleScanService.ScanState state) {
            applyServiceState(state);
        }

        @Override
        public void onRecordReceived(BleRecord record) {
            collectedRecords.add(record);
            addVisibleRecord(record);
            updateLatestSensor(record);
            updateButtons();
        }

        @Override
        public void onLogLine(String line) {
            appendLogLine(line);
        }

        @Override
        public void onCsvSaved(File file) {
            Toast.makeText(MainActivity.this,
                    "CSV 저장 완료\n" + file.getName(), Toast.LENGTH_LONG).show();
        }

        @Override
        public void onCsvSaveFailed(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onUploadResult(boolean success, String message) {
            Toast.makeText(MainActivity.this,
                    (success ? "서버 전송 성공\n" : "서버 전송 실패\n") + message,
                    Toast.LENGTH_SHORT).show();
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            BleScanService.LocalBinder localBinder = (BleScanService.LocalBinder) binder;
            scanService = localBinder.getService();
            serviceBound = true;
            syncFromService();
            scanService.setListener(serviceListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            scanService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        latestSensorText = findViewById(R.id.latestSensorText);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        saveButton = findViewById(R.id.saveButton);
        uploadButton = findViewById(R.id.uploadButton);
        checkPageButton = findViewById(R.id.checkPageButton);
        uploadStatusText = findViewById(R.id.uploadStatusText);
        teamInput = findViewById(R.id.teamInput);
        sensorInput = findViewById(R.id.sensorInput);
        intervalInput = findViewById(R.id.intervalInput);
        autoUploadCheck = findViewById(R.id.autoUploadCheck);
        ListView scanList = findViewById(R.id.scanList);

        scanListAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, visibleRows);
        scanList.setAdapter(scanListAdapter);

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();

        startButton.setOnClickListener(v -> prepareAndStartScan());
        stopButton.setOnClickListener(v -> stopBleScan());
        saveButton.setOnClickListener(v -> saveCsv());
        uploadButton.setOnClickListener(v -> uploadLatest());
        checkPageButton.setOnClickListener(v -> openCheckPage());

        applyUploadConfig(UploadConfig.load(this));
        teamInput.addTextChangedListener(configWatcher);
        sensorInput.addTextChangedListener(configWatcher);
        intervalInput.addTextChangedListener(configWatcher);
        autoUploadCheck.setOnCheckedChangeListener((v, checked) -> {
            if (applyingConfig) {
                return;
            }
            if (checked) {
                ensureLocationPermission();
            }
            pushUploadConfig();
        });
        updateUploadStatusText();

        if (bluetoothAdapter == null) {
            statusText.setText(R.string.bluetooth_unsupported);
            startButton.setEnabled(false);
            appendLocalLog("Bluetooth adapter를 만들 수 없습니다.");
        } else {
            appendLocalLog("준비 완료. Foreground Service로 0x181A 광고 패킷을 수집합니다.");
            appendLocalLog("서버: POST " + SensorUploader.BASE_URL + SensorUploader.SEND_PATH);
            appendLocalLog(getString(R.string.sender_format, UploadConfig.senderId(this)));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, BleScanService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (serviceBound) {
            scanService.setListener(null);
            unbindService(serviceConnection);
            serviceBound = false;
            scanService = null;
        }
        super.onStop();
    }

    @SuppressLint("MissingPermission")
    private void prepareAndStartScan() {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            appendLocalLog("Bluetooth 활성화를 요청합니다.");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    ENABLE_BLUETOOTH_REQUEST_CODE);
            return;
        }
        if (shouldRequestNotificationPermission()) {
            markAsked(PREF_NOTIFICATION_PERMISSION_ASKED);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST_CODE);
            return;
        }
        startForegroundScan();
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, PERMISSION_REQUEST_CODE);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    PERMISSION_REQUEST_CODE);
        }
    }

    private boolean shouldRequestNotificationPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !wasAsked(PREF_NOTIFICATION_PERMISSION_ASKED);
    }

    /** PDF 21쪽 요청 양식의 lat/lon 을 채우기 위해 한 번만 위치 권한을 물어본다. */
    private void ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (wasAsked(PREF_LOCATION_PERMISSION_ASKED)) {
            return;
        }
        markAsked(PREF_LOCATION_PERMISSION_ASKED);
        Toast.makeText(this, R.string.location_permission_rationale,
                Toast.LENGTH_LONG).show();
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, LOCATION_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) {
                granted &= result == PackageManager.PERMISSION_GRANTED;
            }
            if (granted) {
                appendLocalLog("BLE 권한 승인 완료.");
                prepareAndStartScan();
            } else {
                appendLocalLog("BLE 권한이 거부되어 스캔할 수 없습니다.");
                Toast.makeText(this, "주변 기기 권한을 허용해 주세요.",
                        Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length == 0
                    || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                appendLocalLog("알림 권한이 없어 수집 알림이 제한될 수 있습니다.");
            }
            startForegroundScan();
        } else if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            appendLocalLog(granted
                    ? "위치 권한 승인 완료. lat/lon 을 함께 전송합니다."
                    : "위치 권한이 없어 lat/lon 은 0.0 으로 전송됩니다.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BLUETOOTH_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                appendLocalLog("Bluetooth가 활성화되었습니다.");
                prepareAndStartScan();
            } else {
                appendLocalLog("Bluetooth 활성화가 취소되었습니다.");
            }
        }
    }

    private void startForegroundScan() {
        pushUploadConfig();
        Intent intent = new Intent(this, BleScanService.class)
                .setAction(BleScanService.ACTION_START_SCAN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusText.setText(R.string.scan_service_starting);
        startButton.setEnabled(false);
    }

    private void stopBleScan() {
        if (serviceBound) {
            scanService.stopBleScan();
        } else {
            startService(new Intent(this, BleScanService.class)
                    .setAction(BleScanService.ACTION_STOP_SCAN));
        }
    }

    private void saveCsv() {
        if (collectedRecords.isEmpty()) {
            Toast.makeText(this, "저장할 패킷이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (serviceBound) {
            scanService.saveCsv();
        } else {
            startService(new Intent(this, BleScanService.class)
                    .setAction(BleScanService.ACTION_SAVE_CSV));
        }
    }

    /** PDF 21쪽 API 로 최근 센서 패킷 1건을 즉시 POST 한다. */
    private void uploadLatest() {
        if (TextUtils.isEmpty(teamInput.getText().toString().trim())) {
            Toast.makeText(this, "팀 번호를 입력해 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        pushUploadConfig();
        if (serviceBound) {
            scanService.uploadLatestRecord();
        } else {
            startService(new Intent(this, BleScanService.class)
                    .setAction(BleScanService.ACTION_UPLOAD_LATEST));
        }
    }

    /** PDF 26쪽: 수집한 데이터 실시간 확인 페이지를 브라우저로 연다. */
    private void openCheckPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SensorUploader.CHECK_URL)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, SensorUploader.CHECK_URL, Toast.LENGTH_LONG).show();
        }
    }

    private void applyUploadConfig(UploadConfig config) {
        applyingConfig = true;
        teamInput.setText(config.team);
        sensorInput.setText(config.sensor);
        intervalInput.setText(String.valueOf(config.intervalSeconds));
        autoUploadCheck.setChecked(config.autoUpload);
        applyingConfig = false;
    }

    private void pushUploadConfig() {
        if (applyingConfig) {
            return;
        }
        UploadConfig config = readUploadConfig();
        if (serviceBound) {
            scanService.setUploadConfig(config);
        } else {
            config.save(this);
        }
    }

    private UploadConfig readUploadConfig() {
        int interval = UploadConfig.DEFAULT_INTERVAL_SECONDS;
        try {
            String raw = intervalInput.getText().toString().trim();
            if (!raw.isEmpty()) {
                interval = Integer.parseInt(raw);
            }
        } catch (NumberFormatException ignored) {
            // 입력이 끝나지 않은 상태에서는 기본 주기를 쓴다.
        }
        return new UploadConfig(
                teamInput.getText().toString(),
                sensorInput.getText().toString(),
                autoUploadCheck.isChecked(),
                interval);
    }

    private void syncFromService() {
        collectedRecords.clear();
        collectedRecords.addAll(scanService.getRecordsSnapshot());
        rebuildVisibleRows();

        List<String> lines = scanService.getLogLinesSnapshot();
        if (!lines.isEmpty()) {
            logText.setText("");
            for (String line : lines) {
                appendLogLine(line);
            }
        }

        applyUploadConfig(scanService.getUploadConfig());
        if (!collectedRecords.isEmpty()) {
            updateLatestSensor(collectedRecords.get(collectedRecords.size() - 1));
        }
        applyServiceState(scanService.getState());
    }

    private void applyServiceState(BleScanService.ScanState state) {
        scanning = state.scanning;
        saving = state.saving;
        scanStartedAt = state.scanStartedAt;
        scanStoppedAt = state.scanStoppedAt;
        validPacketCount = state.validPacketCount;
        failureMessage = state.failureMessage;
        uploadSuccessCount = state.uploadSuccessCount;
        uploadFailureCount = state.uploadFailureCount;
        lastServerMessage = state.lastServerMessage;

        handler.removeCallbacks(elapsedTicker);
        updateStatusText();
        updateUploadStatusText();
        if (scanning) {
            handler.postDelayed(elapsedTicker, 1000L);
        }
        updateButtons();
    }

    private void updateStatusText() {
        if (failureMessage != null) {
            statusText.setText(getString(R.string.scan_failed_status, failureMessage));
            return;
        }
        if (saving) {
            statusText.setText(String.format(Locale.getDefault(),
                    "CSV 저장 중 · 수신 %,d개 / 유효 %,d개",
                    collectedRecords.size(), validPacketCount));
            return;
        }
        if (scanning) {
            statusText.setText(String.format(Locale.getDefault(),
                    "백그라운드 스캔 중 · %s · 수신 %,d개 / 유효 %,d개",
                    formatElapsed(System.currentTimeMillis() - scanStartedAt),
                    collectedRecords.size(), validPacketCount));
            return;
        }
        if (scanStartedAt > 0L) {
            long end = Math.max(scanStoppedAt, scanStartedAt);
            statusText.setText(String.format(Locale.getDefault(),
                    "스캔 중지 · %s · 수신 %,d개 / 유효 %,d개",
                    formatElapsed(end - scanStartedAt),
                    collectedRecords.size(), validPacketCount));
        } else {
            statusText.setText(R.string.status_ready);
        }
    }

    private void updateUploadStatusText() {
        String response = TextUtils.isEmpty(lastServerMessage)
                ? getString(R.string.upload_status_no_response)
                : lastServerMessage;
        uploadStatusText.setText(getString(R.string.upload_status_format,
                uploadSuccessCount, uploadFailureCount, response));
    }

    private void addVisibleRecord(BleRecord record) {
        visibleRows.add(0, formatRecordRow(record));
        if (visibleRows.size() > MAX_VISIBLE_ROWS) {
            visibleRows.remove(visibleRows.size() - 1);
        }
        scanListAdapter.notifyDataSetChanged();
    }

    private void rebuildVisibleRows() {
        visibleRows.clear();
        int first = Math.max(0, collectedRecords.size() - MAX_VISIBLE_ROWS);
        for (int i = collectedRecords.size() - 1; i >= first; i--) {
            visibleRows.add(formatRecordRow(collectedRecords.get(i)));
        }
        scanListAdapter.notifyDataSetChanged();
    }

    private String formatRecordRow(BleRecord record) {
        SensorPacket sensor = record.sensor;
        String sensorSummary = sensor == null
                ? "센서 파싱 불가 · ServiceData "
                + (record.rawHex.length() / 2) + " bytes"
                : sensor.toString();
        return String.format(Locale.getDefault(),
                "%s\nMAC: %s  RSSI: %d dBm\n%s",
                record.name, record.address, record.rssi, sensorSummary);
    }

    private void updateLatestSensor(BleRecord record) {
        SensorPacket sensor = record.sensor;
        if (sensor == null) {
            latestSensorText.setText(getString(R.string.latest_packet_invalid,
                    record.rawHex.length() / 2, record.rawHex));
        } else {
            String tag = sensor.hasHmacTag()
                    ? getString(R.string.hmac_tag_format,
                            sensor.hmacTagHex(), sensor.hmacTagLength())
                    : getString(R.string.hmac_tag_missing);
            latestSensorText.setText(getString(R.string.latest_sensor_format,
                    sensor.toString(), sensor.timestamp, tag, record.rawHex));
        }
    }

    private void updateButtons() {
        startButton.setEnabled(!scanning && !saving && bluetoothAdapter != null);
        stopButton.setEnabled(scanning && !saving);
        saveButton.setEnabled(!collectedRecords.isEmpty() && !saving);
        uploadButton.setEnabled(hasParsedRecord());
    }

    private boolean hasParsedRecord() {
        for (int i = collectedRecords.size() - 1; i >= 0; i--) {
            if (collectedRecords.get(i).sensor != null) {
                return true;
            }
        }
        return false;
    }

    private boolean wasAsked(String key) {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(key, false);
    }

    private void markAsked(String key) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(key, true)
                .apply();
    }

    private void appendLocalLog(String message) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new java.util.Date());
        appendLogLine("[" + time + "] " + message);
    }

    private void appendLogLine(String line) {
        logText.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private static String formatElapsed(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        return String.format(Locale.getDefault(), "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
