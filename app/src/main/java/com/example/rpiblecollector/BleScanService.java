package com.example.rpiblecollector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.TextUtils;

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
import java.util.concurrent.RejectedExecutionException;

/** BLE 광고 스캔과 수집 레코드를 Activity와 분리해 백그라운드에서 유지한다. */
public final class BleScanService extends Service {
    public static final String ACTION_START_SCAN =
            "com.example.rpiblecollector.action.START_SCAN";
    public static final String ACTION_STOP_SCAN =
            "com.example.rpiblecollector.action.STOP_SCAN";
    public static final String ACTION_SAVE_CSV =
            "com.example.rpiblecollector.action.SAVE_CSV";
    public static final String ACTION_STOP_AND_SAVE =
            "com.example.rpiblecollector.action.STOP_AND_SAVE";

    private static final String NOTIFICATION_CHANNEL_ID = "ble_collection";
    private static final int NOTIFICATION_ID = 1810;
    private static final int MAX_LOG_LINES = 200;
    private static final long NOTIFICATION_REFRESH_MILLIS = 5_000L;
    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();
    private static final ParcelUuid TARGET_UUID =
            ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb");
    private static final long RECOMMENDED_COLLECTION_MILLIS = 10L * 60L * 1000L;

    public interface Listener {
        void onStateChanged(ScanState state);
        void onRecordReceived(BleRecord record);
        void onLogLine(String line);
        void onCsvSaved(File file);
        void onCsvSaveFailed(String message);
    }

    public static final class ScanState {
        public final boolean scanning;
        public final boolean saving;
        public final long scanStartedAt;
        public final long scanStoppedAt;
        public final int recordCount;
        public final int validPacketCount;
        public final String failureMessage;

        private ScanState(boolean scanning, boolean saving, long scanStartedAt,
                          long scanStoppedAt, int recordCount, int validPacketCount,
                          String failureMessage) {
            this.scanning = scanning;
            this.saving = saving;
            this.scanStartedAt = scanStartedAt;
            this.scanStoppedAt = scanStoppedAt;
            this.recordCount = recordCount;
            this.validPacketCount = validPacketCount;
            this.failureMessage = failureMessage;
        }

        public long elapsedMillis(long now) {
            if (scanStartedAt == 0L) {
                return 0L;
            }
            long end = scanning ? now : Math.max(scanStoppedAt, scanStartedAt);
            return Math.max(0L, end - scanStartedAt);
        }
    }

    public final class LocalBinder extends Binder {
        public BleScanService getService() {
            return BleScanService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService recordExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private final List<BleRecord> collectedRecords = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private Listener listener;
    private boolean scanning;
    private boolean saving;
    private boolean foregroundStarted;
    private long scanStartedAt;
    private long scanStoppedAt;
    private int validPacketCount;
    private String failureMessage;
    private long lastNotificationUpdateElapsed;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            enqueueScanResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                enqueueScanResult(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            scanStoppedAt = System.currentTimeMillis();
            failureMessage = "스캔 실패: " + scanErrorMessage(errorCode);
            addLog(failureMessage);
            updateForegroundNotification();
            notifyStateChanged();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START_SCAN.equals(action)) {
            ensureForeground("스캔 준비 중");
            if (scanning) {
                updateForegroundNotification();
            } else {
                startBleScan();
            }
        } else if (ACTION_STOP_SCAN.equals(action)) {
            stopBleScan();
        } else if (ACTION_SAVE_CSV.equals(action)) {
            saveCsv();
        } else if (ACTION_STOP_AND_SAVE.equals(action)) {
            stopBleScan();
            saveCsv();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onStateChanged(getState());
        }
    }

    public ScanState getState() {
        return new ScanState(scanning, saving, scanStartedAt, scanStoppedAt,
                collectedRecords.size(), validPacketCount, failureMessage);
    }

    public List<BleRecord> getRecordsSnapshot() {
        return new ArrayList<>(collectedRecords);
    }

    public List<String> getLogLinesSnapshot() {
        return new ArrayList<>(logLines);
    }

    @SuppressLint("MissingPermission")
    public void stopBleScan() {
        if (!scanning) {
            return;
        }
        if (hasBlePermissions() && bluetoothLeScanner != null) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
        scanning = false;
        scanStoppedAt = System.currentTimeMillis();
        long elapsed = scanStoppedAt - scanStartedAt;
        addLog("BLE 스캔 중지.");
        if (elapsed < RECOMMENDED_COLLECTION_MILLIS) {
            addLog("안내: PDF 실습 기준 수집 시간은 10분 이상입니다.");
        }
        updateForegroundNotification();
        notifyStateChanged();
    }

    public void saveCsv() {
        if (saving) {
            return;
        }
        saving = true;
        updateForegroundNotification();
        notifyStateChanged();
        // 수신 처리 큐의 앞선 결과가 모두 기록된 뒤 CSV 스냅샷을 만든다.
        try {
            recordExecutor.execute(() -> mainHandler.post(this::saveCsvAfterPendingRecords));
        } catch (RejectedExecutionException e) {
            saving = false;
            notifySaveFailed("CSV 저장 실패: 수신 처리기가 종료되었습니다.");
            updateForegroundNotification();
            notifyStateChanged();
        }
    }

    private void saveCsvAfterPendingRecords() {
        if (collectedRecords.isEmpty()) {
            saving = false;
            notifySaveFailed("저장할 패킷이 없습니다.");
            if (!scanning) {
                finishForegroundService();
            } else {
                updateForegroundNotification();
            }
            notifyStateChanged();
            return;
        }

        List<BleRecord> snapshot = new ArrayList<>(collectedRecords);
        addLog("CSV 저장 시작: " + snapshot.size() + "행");
        updateForegroundNotification();
        notifyStateChanged();

        fileExecutor.execute(() -> {
            try {
                File file = CsvExporter.save(this, snapshot);
                mainHandler.post(() -> {
                    saving = false;
                    addLog("CSV 저장 완료: " + file.getAbsolutePath());
                    if (listener != null) {
                        listener.onCsvSaved(file);
                    }
                    notifyStateChanged();
                    if (!scanning) {
                        finishForegroundService();
                    } else {
                        updateForegroundNotification();
                    }
                });
            } catch (IOException e) {
                mainHandler.post(() -> {
                    saving = false;
                    notifySaveFailed("CSV 저장 실패: " + e.getMessage());
                    updateForegroundNotification();
                    notifyStateChanged();
                });
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void startBleScan() {
        if (scanning) {
            return;
        }
        failureMessage = null;
        if (!hasBlePermissions()) {
            failStart("주변 기기 권한이 없어 스캔할 수 없습니다.");
            return;
        }
        if (bluetoothAdapter == null) {
            failStart("Bluetooth를 지원하지 않는 기기입니다.");
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            failStart("Bluetooth가 꺼져 있습니다.");
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            failStart("BluetoothLeScanner를 가져올 수 없습니다.");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(TARGET_UUID)
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        try {
            bluetoothLeScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            scanning = true;
            scanStartedAt = System.currentTimeMillis();
            scanStoppedAt = 0L;
            addLog("BLE 스캔 시작: UUID 0x181A · Foreground Service");
            updateForegroundNotification();
            notifyStateChanged();
        } catch (SecurityException e) {
            failStart("스캔 권한 확인 실패: " + e.getMessage());
        }
    }

    private void enqueueScanResult(ScanResult result) {
        long receivedAtMillis = System.currentTimeMillis();
        try {
            recordExecutor.execute(() -> processScanResult(result, receivedAtMillis));
        } catch (RejectedExecutionException ignored) {
            // 서비스 종료 후 늦게 도착한 콜백은 더 이상 처리할 수 없다.
        }
    }

    @SuppressLint("MissingPermission")
    private void processScanResult(ScanResult result, long receivedAtMillis) {
        ScanRecord scanRecord = result.getScanRecord();
        if (scanRecord == null) {
            return;
        }

        byte[] serviceData = scanRecord.getServiceData(TARGET_UUID);
        SensorPacket sensor = SensorPacket.parse(serviceData);
        BluetoothDevice device = result.getDevice();
        String name = scanRecord.getDeviceName();
        if (TextUtils.isEmpty(name) && hasConnectPermission()) {
            name = device.getName();
        }
        if (TextUtils.isEmpty(name)) {
            name = "unknown";
        }
        String address = hasConnectPermission() ? device.getAddress() : "permission-required";
        String serviceDataHex = toHex(serviceData);
        String scanRecordHex = toHex(scanRecord.getBytes());
        BleRecord record = new BleRecord(receivedAtMillis, name, address,
                result.getRssi(), TARGET_UUID.toString(), sensor, serviceDataHex,
                scanRecordHex);
        mainHandler.post(() -> recordScanResult(record));
    }

    private void recordScanResult(BleRecord record) {
        if (record.sensor == null) {
            addLogOnce("0x181A 패킷을 받았지만 ServiceData가 13바이트보다 짧습니다. 상세 분석용으로 보존합니다.");
        } else {
            validPacketCount++;
        }
        collectedRecords.add(record);
        if (listener != null) {
            listener.onRecordReceived(record);
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationUpdateElapsed >= NOTIFICATION_REFRESH_MILLIS) {
            updateForegroundNotification();
        }
        notifyStateChanged();
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

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void failStart(String message) {
        scanning = false;
        scanStoppedAt = System.currentTimeMillis();
        failureMessage = message;
        addLog(message);
        notifyStateChanged();
        finishForegroundService();
    }

    private void notifySaveFailed(String message) {
        addLog(message);
        if (listener != null) {
            listener.onCsvSaveFailed(message);
        }
    }

    private void notifyStateChanged() {
        if (listener != null) {
            listener.onStateChanged(getState());
        }
    }

    private void addLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + time + "] " + message;
        logLines.add(line);
        if (logLines.size() > MAX_LOG_LINES) {
            logLines.remove(0);
        }
        if (listener != null) {
            listener.onLogLine(line);
        }
    }

    private void addLogOnce(String message) {
        for (String line : logLines) {
            if (line.contains(message)) {
                return;
            }
        }
        addLog(message);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void ensureForeground(String text) {
        Notification notification = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private void updateForegroundNotification() {
        if (!foregroundStarted) {
            return;
        }
        lastNotificationUpdateElapsed = SystemClock.elapsedRealtime();
        String text;
        if (saving) {
            text = "CSV 저장 중 · " + collectedRecords.size() + "건";
        } else if (scanning) {
            text = "수집 " + collectedRecords.size() + "건 · "
                    + formatElapsed(System.currentTimeMillis() - scanStartedAt);
        } else if (failureMessage != null) {
            text = failureMessage;
        } else {
            text = "스캔 중지 · CSV 저장 대기";
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, pendingIntentFlags());

        Intent stopIntent = new Intent(this, BleScanService.class)
                .setAction(ACTION_STOP_AND_SAVE);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, pendingIntentFlags());

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_app_icon)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOngoing(scanning || saving)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_app_icon,
                        getString(R.string.notification_stop_and_save),
                        stopPendingIntent).build())
                .build();
    }

    private int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private void finishForegroundService() {
        if (foregroundStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            foregroundStarted = false;
        }
        stopSelf();
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            chars[i * 2] = HEX_DIGITS[value >>> 4];
            chars[i * 2 + 1] = HEX_DIGITS[value & 0x0F];
        }
        return new String(chars);
    }

    private static String formatElapsed(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
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
    public void onDestroy() {
        if (scanning && bluetoothLeScanner != null && hasBlePermissions()) {
            bluetoothLeScanner.stopScan(scanCallback);
        }
        scanning = false;
        recordExecutor.shutdown();
        fileExecutor.shutdown();
        super.onDestroy();
    }
}
