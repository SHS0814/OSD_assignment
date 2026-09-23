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
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * BLE 광고 스캔과 수집 레코드를 Activity와 분리해 백그라운드에서 유지한다.
 * 4주차 PDF 7쪽 시스템 아키텍처의 "BLE Scanning → HTTP" 구간을 이 서비스가 모두 담당한다.
 */
public final class BleScanService extends Service {
    public static final String ACTION_START_SCAN =
            "com.example.rpiblecollector.action.START_SCAN";
    public static final String ACTION_STOP_SCAN =
            "com.example.rpiblecollector.action.STOP_SCAN";
    public static final String ACTION_SAVE_CSV =
            "com.example.rpiblecollector.action.SAVE_CSV";
    public static final String ACTION_STOP_AND_SAVE =
            "com.example.rpiblecollector.action.STOP_AND_SAVE";
    public static final String ACTION_UPLOAD_LATEST =
            "com.example.rpiblecollector.action.UPLOAD_LATEST";

    public static final String LOG_TAG = "BleCollector";
    private static final String NOTIFICATION_CHANNEL_ID = "ble_collection";
    private static final int NOTIFICATION_ID = 1810;
    private static final int MAX_LOG_LINES = 200;
    private static final long NOTIFICATION_REFRESH_MILLIS = 5_000L;
    private static final ParcelUuid TARGET_UUID =
            ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb");
    private static final long RECOMMENDED_COLLECTION_MILLIS = 10L * 60L * 1000L;

    public interface Listener {
        void onStateChanged(ScanState state);
        void onRecordReceived(BleRecord record);
        void onLogLine(String line);
        void onCsvSaved(File file);
        void onCsvSaveFailed(String message);
        void onUploadResult(boolean success, String message);
        void onUploadDetail(String line);
    }

    public static final class ScanState {
        public final boolean scanning;
        public final boolean saving;
        public final long scanStartedAt;
        public final long scanStoppedAt;
        public final int recordCount;
        public final int validPacketCount;
        public final String failureMessage;
        public final int uploadSuccessCount;
        public final int uploadFailureCount;
        public final String lastServerMessage;

        private ScanState(boolean scanning, boolean saving, long scanStartedAt,
                          long scanStoppedAt, int recordCount, int validPacketCount,
                          String failureMessage, int uploadSuccessCount,
                          int uploadFailureCount, String lastServerMessage) {
            this.scanning = scanning;
            this.saving = saving;
            this.scanStartedAt = scanStartedAt;
            this.scanStoppedAt = scanStoppedAt;
            this.recordCount = recordCount;
            this.validPacketCount = validPacketCount;
            this.failureMessage = failureMessage;
            this.uploadSuccessCount = uploadSuccessCount;
            this.uploadFailureCount = uploadFailureCount;
            this.lastServerMessage = lastServerMessage;
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

    // --- HTTP 전송 (4주차 PDF) ---
    private SensorUploader uploader;
    private UploadConfig uploadConfig;
    private String senderId;
    private int uploadSuccessCount;
    private int uploadFailureCount;
    private long lastUploadElapsed;
    private long lastUploadedSensorTimestamp = -1L;
    private String lastServerMessage;
    private boolean loggedPacketLayout;
    /** 필터를 걸지 않고 주변 광고를 전부 확인하는 진단 모드. */
    private boolean diagnosticScan;
    private final Set<String> diagnosticSeen = new HashSet<>();
    /** 필터를 통과했지만 0x181A ServiceData 가 없어 버린 광고 수. */
    private volatile int nonTargetCount;

    private final SensorUploader.UploadCallback uploadCallback =
            new SensorUploader.UploadCallback() {
                @Override
                public void onUploadSuccess(PostData sent, PostResponse body) {
                    uploadSuccessCount++;
                    lastServerMessage = body.summary();
                    addLog("HTTP 200 · " + lastServerMessage);
                    notifyUploadDetail("  ✓ " + lastServerMessage);
                    markUploadResult(sent, "success");
                    if (listener != null) {
                        listener.onUploadResult(true, lastServerMessage);
                    }
                    updateForegroundNotification();
                    notifyStateChanged();
                }

                @Override
                public void onUploadFailure(PostData sent, String message) {
                    uploadFailureCount++;
                    lastServerMessage = message;
                    addLog("전송 실패 · " + message);
                    notifyUploadDetail("  ✗ " + message);
                    markUploadResult(sent, "fail: " + message);
                    if (listener != null) {
                        listener.onUploadResult(false, message);
                    }
                    updateForegroundNotification();
                    notifyStateChanged();
                }
            };

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
        uploader = new SensorUploader();
        uploadConfig = UploadConfig.load(this);
        senderId = UploadConfig.senderId(this);
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
        } else if (ACTION_UPLOAD_LATEST.equals(action)) {
            uploadLatestRecord();
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
                collectedRecords.size(), validPacketCount, failureMessage,
                uploadSuccessCount, uploadFailureCount, lastServerMessage);
    }

    public List<BleRecord> getRecordsSnapshot() {
        return new ArrayList<>(collectedRecords);
    }

    public List<String> getLogLinesSnapshot() {
        return new ArrayList<>(logLines);
    }

    public boolean isDiagnosticScan() {
        return diagnosticScan;
    }

    /** 스캔 중에 바꾸면 다음 스캔부터 적용된다. */
    public void setDiagnosticScan(boolean diagnosticScan) {
        this.diagnosticScan = diagnosticScan;
    }

    public UploadConfig getUploadConfig() {
        return uploadConfig;
    }

    /** Activity 에서 팀 번호·센서 이름·자동 전송 설정을 바꿀 때 호출한다. */
    public void setUploadConfig(UploadConfig config) {
        boolean autoChanged = uploadConfig.autoUpload != config.autoUpload;
        uploadConfig = config;
        config.save(this);
        if (autoChanged) {
            addLog(config.autoUpload
                    ? "자동 전송 켜짐 · " + config.intervalSeconds + "초마다 POST "
                            + SensorUploader.BASE_URL + SensorUploader.SEND_PATH
                    : "자동 전송 꺼짐.");
        }
        notifyStateChanged();
    }

    /** 가장 최근에 파싱된 센서 패킷 하나를 즉시 서버로 보낸다(수동 전송). */
    public void uploadLatestRecord() {
        BleRecord latest = latestParsedRecord();
        if (latest == null) {
            String message = "전송할 센서 패킷이 없습니다.";
            addLog(message);
            if (listener != null) {
                listener.onUploadResult(false, message);
            }
            return;
        }
        upload(latest);
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
        if (nonTargetCount > 0) {
            addLog("0x181A 가 아닌 광고 " + nonTargetCount + "건은 기록하지 않았습니다.");
        }
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

        // 필터 두 개를 넘기면 Android 가 OR 로 처리한다.
        //  (1) 0x03 Service UUID 목록 AD 로 매칭 (3주차 PDF 예제와 동일)
        //  (2) 0x16 Service Data AD 로 매칭 — 광고에 HMAC 태그가 붙어 길이가 늘면서
        //      펌웨어가 (1) 의 UUID 목록 AD 를 빼더라도 놓치지 않기 위함
        List<ScanFilter> filters = new ArrayList<>();
        if (!diagnosticScan) {
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(TARGET_UUID)
                    .build());
            filters.add(new ScanFilter.Builder()
                    .setServiceData(TARGET_UUID, new byte[0], new byte[0])
                    .build());
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        try {
            // 진단 모드에서는 필터를 걸지 않아 주변 광고를 전부 받는다.
            bluetoothLeScanner.startScan(filters.isEmpty() ? null : filters,
                    settings, scanCallback);
            scanning = true;
            scanStartedAt = System.currentTimeMillis();
            scanStoppedAt = 0L;
            diagnosticSeen.clear();
            nonTargetCount = 0;
            addLog(diagnosticScan
                    ? "BLE 스캔 시작: 필터 없음(진단 모드) · 주변 광고를 모두 확인합니다"
                    : "BLE 스캔 시작: UUID 0x181A 필터 2종(UUID 목록 + ServiceData)");
            if (!isLocationServiceEnabled()) {
                addLog("경고: 시스템 '위치' 가 꺼져 있습니다. "
                        + "켜지 않으면 스캔이 시작돼도 광고 패킷이 올라오지 않습니다.");
            }
            if (uploadConfig.autoUpload) {
                addLog("자동 전송 켜짐 · " + uploadConfig.intervalSeconds + "초마다 POST "
                        + SensorUploader.BASE_URL + SensorUploader.SEND_PATH);
            }
            updateForegroundNotification();
            notifyStateChanged();
        } catch (SecurityException e) {
            failStart("스캔 권한 확인 실패: " + e.getMessage());
        }
    }

    private void enqueueScanResult(ScanResult result) {
        long receivedAtMillis = System.currentTimeMillis();
        double latitude = uploadConfig.latitude;
        double longitude = uploadConfig.longitude;
        try {
            recordExecutor.execute(() ->
                    processScanResult(result, receivedAtMillis, latitude, longitude));
        } catch (RejectedExecutionException ignored) {
            // 서비스 종료 후 늦게 도착한 콜백은 더 이상 처리할 수 없다.
        }
    }

    @SuppressLint("MissingPermission")
    private void processScanResult(ScanResult result, long receivedAtMillis,
                                   double latitude, double longitude) {
        // ScanRecord 를 못 얻어도 레코드를 버리지 않는다. RSSI/MAC/무선 계층 정보만이라도
        // 남겨 두어야 "수신은 했는데 원본이 없는" 상황이 CSV 에서 드러난다.
        ScanRecord scanRecord = result.getScanRecord();
        byte[] serviceData = scanRecord == null ? null : scanRecord.getServiceData(TARGET_UUID);
        // 하드웨어 필터는 0x181A 가 없는 광고도 통과시킬 수 있으므로 여기서 반드시 다시 거른다.
        // 이 검사가 없으면 주변 BLE 기기가 전부 CSV 에 섞인다.
        if (serviceData == null) {
            nonTargetCount++;
            if (diagnosticScan) {
                logDiagnosticDevice(result, scanRecord);
            }
            return;
        }
        SensorPacket sensor = SensorPacket.parse(serviceData);
        BluetoothDevice device = result.getDevice();
        String name = scanRecord == null ? null : scanRecord.getDeviceName();
        if (TextUtils.isEmpty(name) && hasConnectPermission()) {
            name = device.getName();
        }
        if (TextUtils.isEmpty(name)) {
            name = "unknown";
        }
        String address = hasConnectPermission() ? device.getAddress() : "permission-required";
        String serviceDataHex = Hex.encode(serviceData);
        String scanRecordHex = scanRecord == null ? "" : Hex.encode(scanRecord.getBytes());
        BleRecord record = new BleRecord(receivedAtMillis, name, address,
                result.getRssi(), TARGET_UUID.toString(), sensor, serviceDataHex,
                scanRecordHex, latitude, longitude, AdvertisingMeta.from(result));
        mainHandler.post(() -> recordScanResult(record));
    }

    /** 진단 모드에서 0x181A 가 아닌 기기를 기기당 한 번만 로그로 남긴다. */
    private void logDiagnosticDevice(ScanResult result, ScanRecord scanRecord) {
        String address = hasConnectPermission()
                ? result.getDevice().getAddress() : "permission-required";
        if (!diagnosticSeen.add(address)) {
            return;
        }
        String name = scanRecord == null ? null : scanRecord.getDeviceName();
        List<ParcelUuid> uuids = scanRecord == null ? null : scanRecord.getServiceUuids();
        Map<ParcelUuid, byte[]> data = scanRecord == null ? null : scanRecord.getServiceData();
        StringBuilder sb = new StringBuilder("[진단] ")
                .append(TextUtils.isEmpty(name) ? "(이름 없음)" : name)
                .append(" ").append(address)
                .append(" RSSI ").append(result.getRssi())
                .append(" · UUID목록=").append(uuids == null ? "없음" : uuids.toString())
                .append(" · ServiceData키=");
        if (data == null || data.isEmpty()) {
            sb.append("없음");
        } else {
            for (ParcelUuid k : data.keySet()) {
                sb.append(k).append("(").append(data.get(k).length).append("B) ");
            }
        }
        final String line = sb.toString();
        mainHandler.post(() -> addLog(line));
    }

    private void recordScanResult(BleRecord record) {
        if (record.sensor == null) {
            addLogOnce("0x181A 패킷을 받았지만 ServiceData가 13바이트보다 짧습니다. 상세 분석용으로 보존합니다.");
        } else {
            validPacketCount++;
            logPacketLayoutOnce(record);
        }
        if (record.meta.isTruncated()) {
            addLogOnce("경고: 광고 데이터가 잘려서(truncated) 수신되었습니다. "
                    + "HMAC 태그 일부가 유실되었을 수 있습니다.");
        }
        if (record.scanRecordHex.isEmpty()) {
            addLogOnce("경고: 광고 원본 바이트를 얻지 못한 패킷이 있습니다. "
                    + "해당 행은 MAC/RSSI 만 기록됩니다.");
        } else if (record.rawHex.isEmpty()) {
            addLogOnce("경고: 0x181A ServiceData 가 없는 패킷이 있습니다. "
                    + "scan_record_hex 에서 직접 확인하세요.");
        }
        collectedRecords.add(record);
        if (listener != null) {
            listener.onRecordReceived(record);
        }
        maybeAutoUpload(record);
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationUpdateElapsed >= NOTIFICATION_REFRESH_MILLIS) {
            updateForegroundNotification();
        }
        notifyStateChanged();
    }

    /** 첫 유효 패킷에서 실제 ServiceData 길이와 HMAC 태그 길이를 한 번만 알린다. */
    private void logPacketLayoutOnce(BleRecord record) {
        if (loggedPacketLayout) {
            return;
        }
        loggedPacketLayout = true;
        int serviceDataBytes = record.rawHex.length() / 2;
        if (record.sensor.hasHmacTag()) {
            addLog("패킷 구조: ServiceData " + serviceDataBytes + "바이트 = 센서 "
                    + SensorPacket.SENSOR_PAYLOAD_LENGTH + "바이트 + HMAC 태그 "
                    + record.sensor.hmacTagLength() + "바이트");
        } else {
            addLog("패킷 구조: ServiceData " + serviceDataBytes
                    + "바이트 · HMAC 태그 없음");
        }
        if (!record.meta.dataStatusName().isEmpty()) {
            addLog("광고 유형: " + (record.meta.legacy ? "legacy" : "extended")
                    + " · 데이터 " + record.meta.dataStatusName()
                    + " · PHY " + record.meta.phyName(record.meta.primaryPhy)
                    + "/" + record.meta.phyName(record.meta.secondaryPhy));
        }
    }

    /**
     * 자동 전송. 광고 패킷은 초당 여러 번 들어오므로 설정한 주기를 지키고,
     * 같은 센서 timestamp 는 한 번만 보낸다.
     */
    private void maybeAutoUpload(BleRecord record) {
        if (!uploadConfig.autoUpload || record.sensor == null) {
            return;
        }
        if (record.sensor.timestamp == lastUploadedSensorTimestamp) {
            record.setUploadResult("skipped_duplicate");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastUploadElapsed != 0L && now - lastUploadElapsed < uploadConfig.intervalMillis()) {
            record.setUploadResult("skipped_interval");
            return;
        }
        upload(record);
    }

    /**
     * 최근 센서 패킷 N건을 테스트 모드로 서버에 보낸다.
     * 공용 서버이므로 250ms 간격을 두고 순차 전송한다.
     *
     * @return 실제로 전송을 시작한 건수
     */
    public int uploadRecent(int count, TestMode mode) {
        return uploadRecords(collectedRecords, count, mode);
    }

    /**
     * 주어진 레코드 목록에서 최근 N건을 골라 전송한다.
     * 메모리에 수집한 기록뿐 아니라 저장된 CSV 에서 읽어온 기록도 보낼 수 있다.
     */
    public int uploadRecords(List<BleRecord> source, int count, TestMode mode) {
        List<BleRecord> targets = new ArrayList<>();
        for (int i = source.size() - 1; i >= 0 && targets.size() < count; i--) {
            BleRecord record = source.get(i);
            if (record.sensor != null) {
                targets.add(record);
            }
        }
        if (targets.isEmpty()) {
            return 0;
        }
        Collections.reverse(targets); // 오래된 것부터 보낸다
        addLog("테스트 전송 시작: " + targets.size() + "건 · 모드 " + mode.label());
        notifyUploadDetail("── 테스트 전송 " + targets.size() + "건 · 모드 " + mode.label() + " ──");
        for (int i = 0; i < targets.size(); i++) {
            final BleRecord record = targets.get(i);
            final int index = i + 1;
            mainHandler.postDelayed(() -> uploadOne(record, mode, index), i * 250L);
        }
        return targets.size();
    }

    private void uploadOne(BleRecord record, TestMode mode, int index) {
        PostData body = PostData.from(record, uploadConfig.team, uploadConfig.sensor,
                senderId, mode);
        if (body == null) {
            notifyUploadDetail("[" + index + "] 건너뜀 · 센서 패킷 파싱 불가");
            return;
        }
        notifyUploadDetail("[" + index + "] POST ts=" + body.getTimestamp()
                + " raw=" + (body.getRaw() == null ? "(없음)" : body.getRaw()));
        record.setUploadResult("sending");
        lastUploadElapsed = SystemClock.elapsedRealtime();
        lastUploadedSensorTimestamp = record.sensor.timestamp;
        uploader.send(body, uploadCallback);
    }

    private void notifyUploadDetail(String line) {
        if (listener != null) {
            listener.onUploadDetail(line);
        }
    }

    /** PDF 21쪽 "데이터 전송 코드" 호출 지점. */
    private void upload(BleRecord record) {
        PostData body = PostData.from(record, uploadConfig.team, uploadConfig.sensor, senderId);
        if (body == null) {
            addLog("전송 실패 · 센서 패킷을 파싱하지 못한 레코드입니다.");
            if (listener != null) {
                listener.onUploadResult(false, "센서 패킷 파싱 불가");
            }
            return;
        }
        lastUploadElapsed = SystemClock.elapsedRealtime();
        lastUploadedSensorTimestamp = record.sensor.timestamp;
        record.setUploadResult("sending");
        addLog("POST " + SensorUploader.SEND_PATH + " · " + body.summary());
        uploader.send(body, uploadCallback);
    }

    /** 전송 결과를 해당 레코드에 되돌려 기록해 CSV 에 남긴다. */
    private void markUploadResult(PostData sent, String result) {
        for (int i = collectedRecords.size() - 1; i >= 0; i--) {
            BleRecord record = collectedRecords.get(i);
            if (record.sensor != null
                    && record.sensor.timestamp == sent.getTimestamp()
                    && "sending".equals(record.getUploadResult())) {
                record.setUploadResult(result);
                return;
            }
        }
    }

    private BleRecord latestParsedRecord() {
        for (int i = collectedRecords.size() - 1; i >= 0; i--) {
            if (collectedRecords.get(i).sensor != null) {
                return collectedRecords.get(i);
            }
        }
        return null;
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

    /**
     * BLE 스캔은 시스템 '위치' 토글이 켜져 있어야 결과를 준다.
     * 권한이 있어도 이 토글이 꺼져 있으면 콜백이 한 번도 오지 않는다.
     */
    private boolean isLocationServiceEnabled() {
        LocationManager manager = getSystemService(LocationManager.class);
        if (manager == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return manager.isLocationEnabled();
        }
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
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
        // 화면 로그를 logcat 에도 남긴다: adb logcat -s BleCollector 로 바로 확인 가능
        Log.i(LOG_TAG, message);
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
            if (uploadConfig.autoUpload) {
                text += " · 전송 " + uploadSuccessCount + "/"
                        + (uploadSuccessCount + uploadFailureCount);
            }
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
