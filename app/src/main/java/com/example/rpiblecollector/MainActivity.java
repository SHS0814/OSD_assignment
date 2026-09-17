package com.example.rpiblecollector;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int ENABLE_BLUETOOTH_REQUEST_CODE = 101;
    private static final ParcelUuid TARGET_UUID =
            ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb");
    private static final String TARGET_NAME = "opensrc_week_3";
    private static final long RECOMMENDED_COLLECTION_MILLIS = 10L * 60L * 1000L;
    private static final int MAX_VISIBLE_ROWS = 100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final List<BleRecord> collectedRecords = new ArrayList<>();
    private final List<String> visibleRows = new ArrayList<>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private boolean scanning;
    private long scanStartedAt;
    private int validPacketCount;

    private TextView statusText;
    private TextView latestSensorText;
    private TextView logText;
    private ScrollView logScroll;
    private Button startButton;
    private Button stopButton;
    private Button saveButton;
    private ArrayAdapter<String> scanListAdapter;

    private final Runnable elapsedTicker = new Runnable() {
        @Override
        public void run() {
            if (!scanning) {
                return;
            }
            long elapsed = System.currentTimeMillis() - scanStartedAt;
            statusText.setText(String.format(Locale.getDefault(),
                    "스캔 중 · %s · 수신 %,d개 / 유효 %,d개",
                    formatElapsed(elapsed), collectedRecords.size(), validPacketCount));
            handler.postDelayed(this, 1000L);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            processScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                processScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            updateButtons();
            handler.removeCallbacks(elapsedTicker);
            statusText.setText(getString(R.string.scan_failed_status, errorCode));
            appendLog("스캔 실패: " + scanErrorMessage(errorCode));
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
        ListView scanList = findViewById(R.id.scanList);

        scanListAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, visibleRows);
        scanList.setAdapter(scanListAdapter);
        scanList.setOnItemClickListener((parent, view, position, id) -> {
            int recordIndex = collectedRecords.size() - 1 - position;
            if (recordIndex >= 0 && recordIndex < collectedRecords.size()) {
                showPacketDetails(collectedRecords.get(recordIndex));
            }
        });

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();

        startButton.setOnClickListener(v -> prepareAndStartScan());
        stopButton.setOnClickListener(v -> stopBleScan());
        saveButton.setOnClickListener(v -> saveCsv());

        if (bluetoothAdapter == null) {
            statusText.setText(R.string.bluetooth_unsupported);
            startButton.setEnabled(false);
            appendLog("Bluetooth adapter를 만들 수 없습니다.");
        } else {
            appendLog("준비 완료. 0x181A 광고 패킷을 수집합니다.");
        }
    }

    @SuppressLint("MissingPermission")
    private void prepareAndStartScan() {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            appendLog("Bluetooth 활성화를 요청합니다.");
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    ENABLE_BLUETOOTH_REQUEST_CODE);
            return;
        }
        startBleScan();
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

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return;
        }
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            granted &= result == PackageManager.PERMISSION_GRANTED;
        }
        if (granted) {
            appendLog("BLE 권한 승인 완료.");
            prepareAndStartScan();
        } else {
            appendLog("BLE 권한이 거부되어 스캔할 수 없습니다.");
            Toast.makeText(this, "주변 기기 권한을 허용해 주세요.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BLUETOOTH_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                appendLog("Bluetooth가 활성화되었습니다.");
                startBleScan();
            } else {
                appendLog("Bluetooth 활성화가 취소되었습니다.");
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startBleScan() {
        if (scanning || !hasBlePermissions()) {
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            appendLog("BluetoothLeScanner를 가져올 수 없습니다.");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(TARGET_UUID)
                .build();
        List<ScanFilter> filters = Collections.singletonList(filter);
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();

        bluetoothLeScanner.startScan(filters, settings, scanCallback);
        scanning = true;
        scanStartedAt = System.currentTimeMillis();
        updateButtons();
        appendLog("BLE 스캔 시작: UUID 0x181A");
        handler.removeCallbacks(elapsedTicker);
        handler.post(elapsedTicker);
    }

    @SuppressLint("MissingPermission")
    private void stopBleScan() {
        if (!scanning) {
            return;
        }
        if (hasBlePermissions() && bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
        scanning = false;
        handler.removeCallbacks(elapsedTicker);
        long elapsed = System.currentTimeMillis() - scanStartedAt;
        statusText.setText(String.format(Locale.getDefault(),
                "스캔 중지 · %s · 수신 %,d개 / 유효 %,d개",
                formatElapsed(elapsed), collectedRecords.size(), validPacketCount));
        appendLog("BLE 스캔 중지.");
        if (elapsed < RECOMMENDED_COLLECTION_MILLIS) {
            appendLog("안내: PDF 실습 기준 수집 시간은 10분 이상입니다.");
        }
        updateButtons();
    }

    @SuppressLint("MissingPermission")
    private void processScanResult(ScanResult result) {
        ScanRecord scanRecord = result.getScanRecord();
        if (scanRecord == null) {
            return;
        }

        byte[] serviceData = scanRecord.getServiceData(TARGET_UUID);
        SensorPacket sensor = SensorPacket.parse(serviceData);
        if (sensor == null) {
            appendLogOnce("0x181A 패킷을 받았지만 ServiceData가 13바이트보다 짧습니다. 상세 분석용으로 보존합니다.");
        } else {
            validPacketCount++;
        }

        BluetoothDevice device = result.getDevice();
        String name = scanRecord.getDeviceName();
        if (TextUtils.isEmpty(name) && hasConnectPermission()) {
            name = device.getName();
        }
        if (TextUtils.isEmpty(name)) {
            name = "unknown";
        }
        String address = hasConnectPermission() ? device.getAddress() : "permission-required";
        String rawHex = toHex(serviceData);
        String scanRecordHex = toHex(scanRecord.getBytes());
        long receivedAtMillis = System.currentTimeMillis();
        String packetDetails = PacketInspector.inspect(result, scanRecord, name, address,
                TARGET_UUID, TARGET_NAME, receivedAtMillis);

        BleRecord record = new BleRecord(receivedAtMillis, name, address,
                result.getRssi(), TARGET_UUID.toString(), sensor, rawHex,
                scanRecordHex, packetDetails);
        collectedRecords.add(record);

        String sensorSummary = sensor == null
                ? "센서 파싱 불가 · ServiceData "
                    + (serviceData == null ? 0 : serviceData.length) + " bytes"
                : sensor.toString();
        String row = String.format(Locale.getDefault(),
                "%s\nMAC: %s  RSSI: %d dBm\n%s",
                name, address, result.getRssi(), sensorSummary);
        visibleRows.add(0, row);
        if (visibleRows.size() > MAX_VISIBLE_ROWS) {
            visibleRows.remove(visibleRows.size() - 1);
        }
        scanListAdapter.notifyDataSetChanged();
        if (sensor == null) {
            latestSensorText.setText(getString(R.string.latest_packet_invalid,
                    serviceData == null ? 0 : serviceData.length, rawHex));
        } else {
            latestSensorText.setText(getString(R.string.latest_sensor_format,
                    sensor.toString(), sensor.timestamp, rawHex));
        }
        saveButton.setEnabled(true);
    }

    private void showPacketDetails(BleRecord record) {
        TextView details = new TextView(this);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        details.setPadding(padding, padding, padding, padding);
        details.setTypeface(Typeface.MONOSPACE);
        details.setTextSize(12f);
        details.setTextIsSelectable(true);
        details.setText(record.packetDetails);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(details);

        new AlertDialog.Builder(this)
                .setTitle(R.string.packet_detail_title)
                .setView(scroll)
                .setNegativeButton(R.string.copy, (dialog, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                "BLE packet details", record.packetDetails));
                        Toast.makeText(this, "패킷 상세를 복사했습니다.",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void saveCsv() {
        if (collectedRecords.isEmpty()) {
            Toast.makeText(this, "저장할 유효 패킷이 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<BleRecord> snapshot = new ArrayList<>(collectedRecords);
        saveButton.setEnabled(false);
        appendLog("CSV 저장 시작: " + snapshot.size() + "행");

        fileExecutor.execute(() -> {
            try {
                File file = CsvExporter.save(this, snapshot);
                runOnUiThread(() -> {
                    appendLog("CSV 저장 완료: " + file.getAbsolutePath());
                    Toast.makeText(this, "CSV 저장 완료\n" + file.getName(),
                            Toast.LENGTH_LONG).show();
                    saveButton.setEnabled(true);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    appendLog("CSV 저장 실패: " + e.getMessage());
                    Toast.makeText(this, "CSV 저장 실패", Toast.LENGTH_LONG).show();
                    saveButton.setEnabled(true);
                });
            }
        });
    }

    private void updateButtons() {
        startButton.setEnabled(!scanning && bluetoothAdapter != null);
        stopButton.setEnabled(scanning);
        saveButton.setEnabled(!collectedRecords.isEmpty());
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        logText.append("[" + time + "] " + message + "\n");
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void appendLogOnce(String message) {
        if (!logText.getText().toString().contains(message)) {
            appendLog(message);
        }
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02X", value & 0xFF));
        }
        return builder.toString();
    }

    private static String formatElapsed(long millis) {
        long totalSeconds = millis / 1000L;
        return String.format(Locale.getDefault(), "%02d:%02d",
                totalSeconds / 60L, totalSeconds % 60L);
    }

    private static String scanErrorMessage(int errorCode) {
        switch (errorCode) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:
                return "이미 스캔 중입니다 (1)";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED:
                return "앱 등록 실패 (2)";
            case ScanCallback.SCAN_FAILED_INTERNAL_ERROR:
                return "내부 오류 (3)";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:
                return "기능 미지원 (4)";
            default:
                return "오류 코드 " + errorCode;
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void onDestroy() {
        if (scanning && bluetoothLeScanner != null && hasBlePermissions()) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
        handler.removeCallbacksAndMessages(null);
        fileExecutor.shutdown();
        super.onDestroy();
    }
}
