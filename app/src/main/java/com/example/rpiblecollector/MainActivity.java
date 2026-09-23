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
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int ENABLE_BLUETOOTH_REQUEST_CODE = 101;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 102;
    private static final int MAX_VISIBLE_ROWS = 100;
    private static final String PREFS_NAME = "collector_preferences";
    private static final String PREF_NOTIFICATION_PERMISSION_ASKED =
            "notification_permission_asked";

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
    private EditText latInput;
    private EditText lonInput;
    private CheckBox autoUploadCheck;
    private CheckBox diagnosticCheck;
    private RadioGroup modeGroup;
    private EditText testCountInput;
    private Button testSendButton;
    private TextView sendLogText;
    private Spinner sourceSpinner;
    private TextView sourceInfoText;
    private final List<File> csvFiles = new ArrayList<>();
    private ScrollView sendLogScroll;
    private View pageCollect;
    private View pageSend;
    private TextView tabCollect;
    private TextView tabSend;
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
            reloadSourceList();
        }

        @Override
        public void onCsvSaveFailed(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onUploadDetail(String line) {
            appendSendLog(line);
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
        latInput = findViewById(R.id.latInput);
        lonInput = findViewById(R.id.lonInput);
        autoUploadCheck = findViewById(R.id.autoUploadCheck);
        diagnosticCheck = findViewById(R.id.diagnosticCheck);
        modeGroup = findViewById(R.id.modeGroup);
        testCountInput = findViewById(R.id.testCountInput);
        testSendButton = findViewById(R.id.testSendButton);
        sendLogText = findViewById(R.id.sendLogText);
        sourceSpinner = findViewById(R.id.sourceSpinner);
        sourceInfoText = findViewById(R.id.sourceInfoText);
        sendLogScroll = findViewById(R.id.sendLogScroll);
        pageCollect = findViewById(R.id.pageCollect);
        pageSend = findViewById(R.id.pageSend);
        tabCollect = findViewById(R.id.tabCollect);
        tabSend = findViewById(R.id.tabSend);
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
        testSendButton.setOnClickListener(v -> sendTestBatch());
        tabCollect.setOnClickListener(v -> showPage(true));
        tabSend.setOnClickListener(v -> showPage(false));
        showPage(true);
        reloadSourceList();

        applyUploadConfig(UploadConfig.load(this));
        teamInput.addTextChangedListener(configWatcher);
        sensorInput.addTextChangedListener(configWatcher);
        intervalInput.addTextChangedListener(configWatcher);
        latInput.addTextChangedListener(configWatcher);
        lonInput.addTextChangedListener(configWatcher);
        autoUploadCheck.setOnCheckedChangeListener((v, checked) -> {
            if (applyingConfig) {
                return;
            }
            pushUploadConfig();
        });
        diagnosticCheck.setOnCheckedChangeListener((v, checked) -> {
            if (applyingConfig) {
                return;
            }
            if (serviceBound) {
                scanService.setDiagnosticScan(checked);
            }
            appendLocalLog(checked
                    ? "진단 모드: 다음 스캔부터 필터 없이 주변 광고를 모두 확인합니다."
                    : "진단 모드 해제: 0x181A 필터를 사용합니다.");
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

    /** 하단 탭 전환. 앱을 열면 수집 페이지가 먼저 보인다. */
    private void showPage(boolean collect) {
        pageCollect.setVisibility(collect ? View.VISIBLE : View.GONE);
        pageSend.setVisibility(collect ? View.GONE : View.VISIBLE);
        tabCollect.setTextColor(getColor(collect ? R.color.blue : R.color.text_secondary));
        tabSend.setTextColor(getColor(collect ? R.color.text_secondary : R.color.blue));
    }

    /** 전송할 데이터 목록을 채운다: 메모리 기록 + 저장된 CSV 파일들. */
    private void reloadSourceList() {
        csvFiles.clear();
        csvFiles.addAll(CsvImporter.listCsvFiles(this));
        List<String> labels = new ArrayList<>();
        labels.add(getString(R.string.source_memory));
        for (File f : csvFiles) {
            labels.add(f.getName());
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sourceSpinner.setAdapter(adapter);
        sourceSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                                               View view, int position, long id) {
                        describeSource(position);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });
        describeSource(0);
    }

    private void describeSource(int position) {
        List<BleRecord> records = loadSource(position);
        String label = position == 0
                ? getString(R.string.source_memory)
                : csvFiles.get(position - 1).getName();
        sourceInfoText.setText(getString(R.string.source_info, label, records.size()));
    }

    /** 선택된 항목의 레코드를 반환한다. 0번은 메모리, 그 뒤는 CSV 파일. */
    private List<BleRecord> loadSource(int position) {
        if (position <= 0) {
            return serviceBound
                    ? scanService.getRecordsSnapshot()
                    : new ArrayList<>(collectedRecords);
        }
        File file = csvFiles.get(position - 1);
        try {
            return CsvImporter.load(file);
        } catch (IOException e) {
            appendSendLog(getString(R.string.source_load_failed, e.getMessage()));
            return new ArrayList<>();
        }
    }

    /** 전송 페이지의 테스트 전송: 최근 N건을 선택한 모드로 보낸다. */
    private void sendTestBatch() {
        if (!serviceBound) {
            Toast.makeText(this, "서비스에 연결되지 않았습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(teamInput.getText().toString().trim())) {
            Toast.makeText(this, "팀 번호를 입력해 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = 5;
        try {
            String raw = testCountInput.getText().toString().trim();
            if (!raw.isEmpty()) {
                count = Math.max(1, Integer.parseInt(raw));
            }
        } catch (NumberFormatException ignored) {
            // 입력이 비정상이면 기본 5건
        }
        pushUploadConfig();
        int position = sourceSpinner.getSelectedItemPosition();
        List<BleRecord> source = loadSource(position);
        int sent = scanService.uploadRecords(source, count, selectedMode());
        if (sent == 0) {
            Toast.makeText(this, R.string.no_record_to_send, Toast.LENGTH_LONG).show();
        }
    }

    private TestMode selectedMode() {
        int id = modeGroup.getCheckedRadioButtonId();
        if (id == R.id.modeNoRaw) {
            return TestMode.NO_RAW;
        }
        if (id == R.id.modeBadTag) {
            return TestMode.BAD_TAG;
        }
        if (id == R.id.modeBadValue) {
            return TestMode.BAD_VALUE;
        }
        return TestMode.NORMAL;
    }

    private void appendSendLog(String line) {
        sendLogText.append(line + "\n");
        sendLogScroll.post(() -> sendLogScroll.fullScroll(View.FOCUS_DOWN));
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
        latInput.setText(formatCoordinate(config.latitude));
        lonInput.setText(formatCoordinate(config.longitude));
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
                interval,
                parseCoordinate(latInput),
                parseCoordinate(lonInput));
    }

    /** 입력이 끝나지 않았거나 비어 있으면 0.0 으로 둔다. */
    private static double parseCoordinate(EditText input) {
        String raw = input.getText().toString().trim();
        if (raw.isEmpty() || raw.equals("-") || raw.equals(".") || raw.equals("-.")) {
            return 0.0;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String formatCoordinate(double value) {
        return value == 0.0 ? "" : String.format(Locale.US, "%.6f", value);
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
        applyingConfig = true;
        diagnosticCheck.setChecked(scanService.isDiagnosticScan());
        applyingConfig = false;
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
        diagnosticCheck.setOnCheckedChangeListener((v, checked) -> {
            if (applyingConfig) {
                return;
            }
            if (serviceBound) {
                scanService.setDiagnosticScan(checked);
            }
            appendLocalLog(checked
                    ? "진단 모드: 다음 스캔부터 필터 없이 주변 광고를 모두 확인합니다."
                    : "진단 모드 해제: 0x181A 필터를 사용합니다.");
        });
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
