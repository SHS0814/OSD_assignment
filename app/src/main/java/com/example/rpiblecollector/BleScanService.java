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
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    /** 보관함의 대기 행을 지금 모두 보낸다. */
    public static final String ACTION_SEND_PENDING =
            "com.example.rpiblecollector.action.SEND_PENDING";

    public static final String LOG_TAG = "BleCollector";
    private static final String NOTIFICATION_CHANNEL_ID = "ble_collection";
    private static final int NOTIFICATION_ID = 1810;
    private static final int MAX_LOG_LINES = 200;
    private static final long NOTIFICATION_REFRESH_MILLIS = 5_000L;
    private static final ParcelUuid TARGET_UUID =
            ParcelUuid.fromString("0000181a-0000-1000-8000-00805f9b34fb");
    private static final long RECOMMENDED_COLLECTION_MILLIS = 10L * 60L * 1000L;
    /** 요청 광고의 phone_time 을 갱신하는 주기. 파이는 15초간 변화가 없으면 요청이 끝난 것으로 본다. */
    private static final long REQUEST_REFRESH_MILLIS = 2_000L;
    /** 파이 광고가 이 시간 동안 안 보이면 요청 광고를 멈춘다. */
    private static final long PI_LOST_MILLIS = 30_000L;
    /** 큐 업로드 실패 후 다시 시도하기까지의 대기 시간. */
    private static final long UPLOAD_RETRY_MILLIS = 30_000L;
    /** 서버가 한 행을 거부했을 때 다음 시도까지의 간격. */
    private static final long UPLOAD_REJECT_RETRY_MILLIS = 2_000L;
    /** 서버가 연속으로 이만큼 거부하면 key 등 설정 문제로 보고 전송을 멈춘다. */
    private static final int MAX_CONSECUTIVE_REJECTS = 5;
    private static final String UPLOAD_QUEUED = "queued";

    /** 보관함 작업(가져오기·저장) 결과. 메인 스레드에서 호출된다. */
    public interface ResultCallback {
        void onResult(boolean success, String message);
    }

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
        /** 과거 데이터 수집 현황 한 줄 요약. */
        public final String backlogSummary;

        private ScanState(boolean scanning, boolean saving, long scanStartedAt,
                          long scanStoppedAt, int recordCount, int validPacketCount,
                          String failureMessage, int uploadSuccessCount,
                          int uploadFailureCount, String lastServerMessage,
                          String backlogSummary) {
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
            this.backlogSummary = backlogSummary;
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
    private String lastServerMessage;
    private boolean loggedPacketLayout;
    /** 필터를 걸지 않고 주변 광고를 전부 확인하는 진단 모드. */
    private boolean diagnosticScan;
    private final Set<String> diagnosticSeen = new HashSet<>();
    /** 필터를 통과했지만 0x181A ServiceData 가 없어 버린 광고 수. */
    private volatile int nonTargetCount;

    // --- 과거 데이터 요청 (파이에 저장된 데이터를 굵은 간격부터 받아 온다) ---
    private LocationTracker locationTracker;
    private BacklogDb backlogDb;
    private RequestAdvertiser requestAdvertiser;
    /** 받은 샘플 timestamp (실시간 포함). BacklogDb 의 메모리 사본. */
    private TreeSet<Long> haveTimestamps;
    /** 파이가 "데이터 없음"이라고 답한 격자 시각. */
    private Set<Long> gapTimestamps;
    /** 파이 데이터가 처음 시작된 시각. 모르면 -1 (그동안은 want_ts=0 으로 요청). */
    private long piOldestTs = -1L;
    private byte[] piId;
    private long lastPiSeenElapsed;
    private boolean piLost;
    /** 지금 요청 중인 시각. 요청하지 않으면 -1. */
    private long currentWantTs = -1L;
    private boolean queueUploadInFlight;
    /** "지금 전송": 자동 전송이 꺼져 있어도 보관함의 대기 행을 끝까지 보낸다. */
    private boolean manualDrain;
    private int consecutiveRejects;
    /** 서버가 연속 거부해서 멈춘 상태. 설정을 바꾸거나 "지금 전송" 을 누르면 풀린다. */
    private boolean queuePaused;
    private String backlogSummary = "";

    private final Runnable drainRunnable = this::drainUploadQueue;

    private final Runnable backlogTicker = new Runnable() {
        @Override
        public void run() {
            if (!scanning) {
                return;
            }
            if (requestAdvertiser.isActive()
                    && SystemClock.elapsedRealtime() - lastPiSeenElapsed > PI_LOST_MILLIS) {
                piLost = true;
                requestAdvertiser.stop();
                currentWantTs = -1L;
                addLog("파이 광고가 30초간 보이지 않아 과거 데이터 요청을 멈춥니다.");
                updateBacklogSummary();
                notifyStateChanged();
            } else {
                requestAdvertiser.refresh();
            }
            mainHandler.postDelayed(this, REQUEST_REFRESH_MILLIS);
        }
    };

    /** 큐 업로드 결과. 공통 처리(카운트·로그)는 uploadCallback 에 맡긴다. */
    private final SensorUploader.UploadCallback queueCallback =
            new SensorUploader.UploadCallback() {
                @Override
                public void onUploadSuccess(PostData sent, PostResponse body) {
                    // out_of_range 도 서버에 저장은 되므로 전송 완료로 본다.
                    backlogDb.markUploaded(sent.getTimestamp(), System.currentTimeMillis(),
                            body.status == null ? PostResponse.STATUS_OK : body.status);
                    queueUploadInFlight = false;
                    consecutiveRejects = 0;
                    uploadCallback.onUploadSuccess(sent, body);
                    drainUploadQueue();
                }

                @Override
                public void onUploadFailure(PostData sent, String message, PostResponse response) {
                    queueUploadInFlight = false;
                    mainHandler.removeCallbacks(drainRunnable);
                    if (response == null) {
                        // 서버 응답이 없다(네트워크 끊김 등). 행 탓이 아니므로 시도 횟수는 세지 않고
                        // 자동 전송이면 잠시 뒤 다시 보낸다. "지금 전송" 은 여기서 멈춘다.
                        backlogDb.markFailed(sent.getTimestamp(), message, false);
                        manualDrain = false;
                        mainHandler.postDelayed(drainRunnable, UPLOAD_RETRY_MILLIS);
                    } else {
                        // 서버가 거부했다. 시도 횟수를 세고, MAX_ATTEMPTS 회가 되면 실패로 남긴다.
                        backlogDb.markFailed(sent.getTimestamp(), message, true);
                        consecutiveRejects++;
                        if (consecutiveRejects >= MAX_CONSECUTIVE_REJECTS) {
                            queuePaused = true;
                            manualDrain = false;
                            addLog("서버가 연속 " + consecutiveRejects + "번 거부해 전송을 멈춥니다. "
                                    + "key·센서 이름을 확인한 뒤 '지금 전송' 을 누르세요.");
                        } else {
                            mainHandler.postDelayed(drainRunnable, UPLOAD_REJECT_RETRY_MILLIS);
                        }
                    }
                    uploadCallback.onUploadFailure(sent, message, response);
                    updateBacklogSummary();
                }
            };

    private final SensorUploader.UploadCallback uploadCallback =
            new SensorUploader.UploadCallback() {
                @Override
                public void onUploadSuccess(PostData sent, PostResponse body) {
                    uploadSuccessCount++;
                    lastServerMessage = body.summary();
                    addLog("HTTP 200 · " + lastServerMessage);
                    // 5주차 PDF 49쪽: out_of_range 는 저장은 되지만 배선·단위 변환·Pi 시계를 점검해야 한다.
                    if (body.isOutOfRange()) {
                        addLog("경고: 서버가 값을 범위 이상(out_of_range)으로 표시했습니다. "
                                + "detail 의 필드를 확인하세요 (배선, 단위 변환, Pi 시계 NTP).");
                        notifyUploadDetail("  ⚠ " + lastServerMessage);
                    } else {
                        notifyUploadDetail("  ✓ " + lastServerMessage);
                    }
                    markUploadResult(sent, body.status == null
                            ? "success" : "success(" + body.status + ")");
                    if (listener != null) {
                        listener.onUploadResult(true, lastServerMessage);
                    }
                    updateForegroundNotification();
                    notifyStateChanged();
                }

                @Override
                public void onUploadFailure(PostData sent, String message, PostResponse response) {
                    uploadFailureCount++;
                    lastServerMessage = message;
                    addLog("전송 실패 · " + message);
                    notifyUploadDetail("  ✗ " + message);
                    // HTTP 400 은 key 오류 또는 필수 값 누락이다. 원인은 message 에 담긴다.
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
        locationTracker = new LocationTracker(this);
        backlogDb = new BacklogDb(this);
        haveTimestamps = backlogDb.timestamps();
        gapTimestamps = backlogDb.gaps();
        piOldestTs = backlogDb.oldestTimestamp();
        updateBacklogSummary();
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
        } else if (ACTION_SEND_PENDING.equals(action)) {
            sendPendingNow();
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
                uploadSuccessCount, uploadFailureCount, lastServerMessage, backlogSummary);
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

    /** Activity 에서 key·대상 장치·자동 전송 설정을 바꿀 때 호출한다. */
    public void setUploadConfig(UploadConfig config) {
        boolean autoChanged = uploadConfig.autoUpload != config.autoUpload;
        if (!config.apiKey.equals(uploadConfig.apiKey)
                || !config.deviceName.equals(uploadConfig.deviceName)) {
            queuePaused = false;
            consecutiveRejects = 0;
        }
        uploadConfig = config;
        config.save(this);
        if (autoChanged) {
            addLog(config.autoUpload
                    ? "자동 전송 켜짐 · 보관함의 대기 행을 들어오는 대로 POST "
                            + SensorUploader.BASE_URL + SensorUploader.SEND_PATH
                    : "자동 전송 꺼짐.");
        }
        drainUploadQueue();
        notifyStateChanged();
    }

    /** 보관함에서 아직 전송되지 않은 행을 자동 전송 설정과 관계없이 지금 모두 보낸다. */
    public void sendPendingNow() {
        if (uploadConfig.apiKey.isEmpty()) {
            addLog("전송 실패 · 팀별 key 를 입력해 주세요.");
            if (listener != null) {
                listener.onUploadResult(false, "key 없음");
            }
            return;
        }
        int pending = backlogDb.pendingCount();
        if (pending == 0) {
            String message = "보관함에 전송할 데이터가 없습니다.";
            addLog(message);
            if (listener != null) {
                listener.onUploadResult(false, message);
            }
            return;
        }
        addLog("지금 전송: 보관함의 대기 " + pending + "건을 timestamp 순으로 보냅니다.");
        queuePaused = false;
        consecutiveRejects = 0;
        manualDrain = true;
        mainHandler.removeCallbacks(drainRunnable);
        drainUploadQueue();
        notifyStateChanged();
    }

    // --- 보관함 관리 (관리 화면에서 호출) ---

    public BacklogDb.Stats getArchiveStats() {
        return backlogDb.stats();
    }

    public List<BacklogDb.Row> getArchiveRows(BacklogDb.Filter filter, int limit) {
        return backlogDb.rows(filter, limit);
    }

    /** 서버가 거부해 실패로 남은 행을 대기로 되돌리고 다시 보낸다. */
    public int retryFailedRows() {
        int count = backlogDb.retryFailed();
        if (count > 0) {
            addLog("실패 " + count + "건을 대기로 되돌렸습니다.");
            queuePaused = false;
            consecutiveRejects = 0;
            drainUploadQueue();
        }
        updateBacklogSummary();
        notifyStateChanged();
        return count;
    }

    /** 한 행을 대기로 되돌린다. 전송 완료였다면 서버로 한 번 더 간다. */
    public void resetArchiveRow(long ts) {
        backlogDb.resetToPending(ts);
        addLog("보관함 " + formatTs(ts) + " 을 대기로 되돌렸습니다.");
        drainUploadQueue();
        updateBacklogSummary();
        notifyStateChanged();
    }

    /** "데이터 없음" 기록을 지워 그 시각을 파이에 다시 요청하게 한다. */
    public void requestAgain(long ts) {
        backlogDb.deleteNoData(ts);
        gapTimestamps.remove(ts);
        addLog("보관함 " + formatTs(ts) + " 의 '데이터 없음' 을 지웠습니다. 다음 수집 때 다시 요청합니다.");
        refreshBacklogRequest();
        updateBacklogSummary();
        notifyStateChanged();
    }

    /** 서버에 이미 있다는 것을 알 때 보내지 않고 전송 완료로만 표시한다. */
    public void markArchiveRowUploaded(long ts) {
        backlogDb.markUploadedManually(ts);
        addLog("보관함 " + formatTs(ts) + " 을 전송 완료로 표시했습니다.");
        updateBacklogSummary();
        notifyStateChanged();
    }

    /**
     * 수집 CSV 나 보관함 CSV 를 보관함으로 가져온다. 서버로 보내는 것은 가져온 뒤
     * 보관함의 전송 큐가 맡는다(자동 전송이 꺼져 있으면 "지금 전송").
     */
    public void importCsv(final Uri uri, final String name, final ResultCallback callback) {
        final UploadConfig config = uploadConfig;
        fileExecutor.execute(() -> {
            String message;
            boolean ok;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("파일을 열 수 없습니다.");
                }
                ArchiveCsv.ReadResult read = ArchiveCsv.read(
                        new InputStreamReader(in, StandardCharsets.UTF_8), config);
                BacklogDb.ImportResult imported = backlogDb.importRows(read.entries);
                ok = true;
                message = String.format(Locale.getDefault(),
                        "%s 가져오기 완료\n%s\n보관함에 새로 추가: 대기 %,d · 전송 완료 %,d · "
                                + "데이터 없음 %,d\n데이터 없음 → 실제 샘플 %,d · 전송 완료로 갱신 %,d · "
                                + "이미 있음 %,d",
                        name, read.summary(), imported.added, imported.addedUploaded,
                        imported.addedNoData, imported.replacedNoData, imported.markedUploaded,
                        imported.existing);
            } catch (IOException | RuntimeException e) {
                ok = false;
                message = name + " 가져오기 실패: " + e.getMessage();
            }
            final boolean success = ok;
            final String result = message;
            mainHandler.post(() -> {
                haveTimestamps = backlogDb.timestamps();
                gapTimestamps = backlogDb.gaps();
                addLog(result.replace('\n', ' '));
                refreshBacklogRequest();
                drainUploadQueue();
                updateBacklogSummary();
                notifyStateChanged();
                callback.onResult(success, result);
            });
        });
    }

    /** 보관함 전체를 앱 폴더의 archive.csv 로 덮어쓴다(timestamp 순, 중복 없음). */
    public void saveArchiveCsv(final ResultCallback callback) {
        fileExecutor.execute(() -> {
            String message;
            boolean ok;
            try {
                List<BacklogDb.Row> rows = backlogDb.allRowsAscending();
                List<ArchiveCsv.Entry> entries = new ArrayList<>(rows.size());
                for (BacklogDb.Row row : rows) {
                    entries.add(row.toEntry());
                }
                File dir = getExternalFilesDir(null);
                if (dir == null) {
                    dir = getFilesDir();
                }
                File target = new File(dir, ArchiveCsv.ARCHIVE_FILE_NAME);
                File temp = new File(dir, ArchiveCsv.ARCHIVE_FILE_NAME + ".tmp");
                try (Writer writer = new OutputStreamWriter(
                        new FileOutputStream(temp), StandardCharsets.UTF_8)) {
                    ArchiveCsv.write(writer, entries);
                }
                // 쓰는 도중 앱이 죽어도 이전 보관함 파일이 깨지지 않게 다 쓴 뒤 바꿔 끼운다.
                if (!temp.renameTo(target)) {
                    throw new IOException("파일 이름을 바꿀 수 없습니다: " + target);
                }
                ok = true;
                message = "보관함 저장 완료 (" + entries.size() + "건)\n" + target.getAbsolutePath();
            } catch (IOException | RuntimeException e) {
                ok = false;
                message = "보관함 저장 실패: " + e.getMessage();
            }
            final boolean success = ok;
            final String result = message;
            mainHandler.post(() -> {
                addLog(result.replace('\n', ' '));
                callback.onResult(success, result);
            });
        });
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
        mainHandler.removeCallbacks(backlogTicker);
        stopBacklogRequest();
        locationTracker.stop();
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
                addLog("자동 전송 켜짐 · POST "
                        + SensorUploader.BASE_URL + SensorUploader.SEND_PATH);
            }
            if (locationTracker.start()) {
                addLog("GPS 위치 수신 시작 · lat/lon 은 패킷을 받은 순간의 폰 위치를 씁니다 "
                        + "(위치를 못 얻으면 화면의 좌표).");
            } else {
                addLog("위치를 받을 수 없어 lat/lon 에 화면에서 입력한 좌표를 씁니다.");
            }
            if (requestAdvertiser == null) {
                requestAdvertiser = new RequestAdvertiser(this, bluetoothAdapter, this::addLog);
            }
            piLost = false;
            mainHandler.postDelayed(backlogTicker, REQUEST_REFRESH_MILLIS);
            drainUploadQueue();
            updateForegroundNotification();
            notifyStateChanged();
        } catch (SecurityException e) {
            failStart("스캔 권한 확인 실패: " + e.getMessage());
        }
    }

    private void enqueueScanResult(ScanResult result) {
        long receivedAtMillis = System.currentTimeMillis();
        Location location = locationTracker.current();
        double latitude = location != null ? location.getLatitude() : uploadConfig.latitude;
        double longitude = location != null ? location.getLongitude() : uploadConfig.longitude;
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
        handleSensorRecord(record);
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
     * 우리 팀 파이의 패킷 처리. 실시간·과거 구분 없이 timestamp 가 처음이면 DB 에 저장하고
     * 업로드 큐로 보낸다. 같은 timestamp 는 한 번만 저장되므로 서버에도 한 번만 올라간다.
     */
    private void handleSensorRecord(BleRecord record) {
        if (record.sensor == null) {
            return;
        }
        // 다른 팀 파이의 광고는 CSV 에만 남기고 우리 팀 key 로 보내지 않는다.
        if (!uploadConfig.matchesDevice(record)) {
            record.setUploadResult("skipped_other_device");
            return;
        }
        onPiSeen(record.address);
        SensorPacket packet = record.sensor;
        if (packet.noData) {
            // 파이에 이 시각 근처 데이터가 없다는 특수값. 다시 요청하지 않도록 보관함에 남기지만
            // 측정값이 아니므로 전송 큐에는 넣지 않는다(보관함의 전송 대상 조건에서 빠진다).
            record.setUploadResult("no_data_marker");
            if (backlogDb.insertNoData(record)) {
                gapTimestamps.add(packet.timestamp);
                addLog("파이에 " + formatTs(packet.timestamp)
                        + " 근처 데이터가 없음 → 보관함에 기록, 서버로는 보내지 않습니다.");
                refreshBacklogRequest();
            }
            return;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        boolean past = packet.timestamp < nowSec - BacklogScheduler.LIVE_MARGIN_SECONDS;
        if (currentWantTs == 0L && past
                && (piOldestTs < 0L || packet.timestamp < piOldestTs)) {
            // want_ts=0 요청에 대한 응답 = 파이에 있는 가장 오래된 샘플
            piOldestTs = packet.timestamp;
            backlogDb.setOldestTimestamp(piOldestTs);
            addLog("파이 데이터 시작 시각: " + formatTs(piOldestTs));
        }
        if (!backlogDb.insert(record)) {
            record.setUploadResult("skipped_duplicate");
            return;
        }
        haveTimestamps.add(packet.timestamp);
        // "데이터 없음" 이던 시각에 실제 샘플이 오면 보관함에서 실제 샘플로 바뀐다.
        gapTimestamps.remove(packet.timestamp);
        record.setUploadResult(uploadConfig.autoUpload ? UPLOAD_QUEUED : "stored");
        if (past) {
            addLog("과거 데이터 수신: " + formatTs(packet.timestamp));
        }
        refreshBacklogRequest();
        drainUploadQueue();
    }

    private void onPiSeen(String address) {
        lastPiSeenElapsed = SystemClock.elapsedRealtime();
        boolean firstSeen = piId == null;
        if (firstSeen) {
            piId = RequestAdvertiser.piIdFromAddress(address);
        }
        if (firstSeen || piLost) {
            piLost = false;
            refreshBacklogRequest();
        }
    }

    /** 받은 데이터 기준으로 다음 요청을 계산해 요청 광고를 시작·갱신·중지한다. */
    private void refreshBacklogRequest() {
        if (!scanning || piId == null || requestAdvertiser == null || piLost) {
            return;
        }
        long want = piOldestTs < 0L
                ? 0L
                : BacklogScheduler.nextRequest(piOldestTs,
                        System.currentTimeMillis() / 1000L, haveTimestamps, gapTimestamps);
        if (want < 0L) {
            if (currentWantTs >= 0L) {
                addLog("파이의 과거 데이터를 모두 받았습니다.");
            }
            stopBacklogRequest();
            return;
        }
        if (want != currentWantTs) {
            currentWantTs = want;
            addLog("과거 데이터 요청: " + (want == 0L ? "가장 오래된 샘플" : formatTs(want)));
        }
        requestAdvertiser.request(piId, want);
        updateBacklogSummary();
    }

    private void stopBacklogRequest() {
        if (requestAdvertiser != null) {
            requestAdvertiser.stop();
        }
        currentWantTs = -1L;
        updateBacklogSummary();
    }

    /**
     * 보관함의 미전송 행을 timestamp 순서대로 한 건씩 보낸다. 응답이 오면 다음 건을 보낸다.
     * 서버로 가는 경로는 이것 하나뿐이라 같은 timestamp 가 두 번 가지 않는다.
     */
    private void drainUploadQueue() {
        if (queueUploadInFlight || queuePaused || uploadConfig.apiKey.isEmpty()
                || !(uploadConfig.autoUpload || manualDrain)) {
            return;
        }
        BacklogDb.Row row = backlogDb.nextPending();
        if (row == null) {
            if (manualDrain) {
                manualDrain = false;
                addLog("보관함의 대기 데이터를 모두 보냈습니다.");
            }
            updateBacklogSummary();
            notifyStateChanged();
            return;
        }
        // sensor = 대상 장치 이름. 행마다 저장된 이름을 쓰면 광고 이름을 바꾸기 전 행
        // (opensrc_team9)과 후 행(Opensrc_team9)이 서버에서 다른 센서로 갈린다.
        String sensorName = uploadConfig.sensorName(row.name);
        queueUploadInFlight = true;
        PostData body = uploader.send(row, uploadConfig.apiKey, sensorName, senderId,
                queueCallback);
        addLog("POST " + SensorUploader.SEND_PATH + " · " + body.summary());
    }

    private void updateBacklogSummary() {
        String request;
        if (currentWantTs < 0L) {
            request = "요청 없음";
        } else if (currentWantTs == 0L) {
            request = "파이 데이터 시작 시각 확인 중";
        } else {
            long step = BacklogScheduler.levelOf(
                    BacklogScheduler.dayStart(piOldestTs), currentWantTs);
            request = "요청 " + formatTs(currentWantTs) + " (" + formatStep(step) + " 간격)";
        }
        backlogSummary = String.format(Locale.getDefault(),
                "보관함 %,d건 · 전송 대기 %,d건%s · %s",
                haveTimestamps.size(), backlogDb.pendingCount(),
                queuePaused ? " (전송 멈춤)" : "", request);
    }

    private void notifyUploadDetail(String line) {
        if (listener != null) {
            listener.onUploadDetail(line);
        }
    }

    /** 전송 결과를 해당 레코드에 되돌려 기록해 CSV 에 남긴다. */
    private void markUploadResult(PostData sent, String result) {
        for (int i = collectedRecords.size() - 1; i >= 0; i--) {
            BleRecord record = collectedRecords.get(i);
            if (record.sensor != null
                    && record.sensor.timestamp == sent.getTimestamp()
                    && (UPLOAD_QUEUED.equals(record.getUploadResult())
                    || "stored".equals(record.getUploadResult()))) {
                record.setUploadResult(result);
                return;
            }
        }
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
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
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (locationTracker.hasPermission()) {
                // 화면이 꺼져도 위치를 받으려면 location 유형이 필요하다.
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            startForeground(NOTIFICATION_ID, notification, type);
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

    private static String formatTs(long unixSeconds) {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(unixSeconds * 1000L));
    }

    private static String formatStep(long seconds) {
        return seconds >= 3_600L ? (seconds / 3_600L) + "시간" : (seconds / 60L) + "분";
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
        mainHandler.removeCallbacks(backlogTicker);
        mainHandler.removeCallbacks(drainRunnable);
        stopBacklogRequest();
        locationTracker.stop();
        recordExecutor.shutdown();
        fileExecutor.shutdown();
        backlogDb.close();
        super.onDestroy();
    }
}
