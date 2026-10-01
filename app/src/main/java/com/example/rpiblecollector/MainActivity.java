package com.example.rpiblecollector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int ENABLE_BLUETOOTH_REQUEST_CODE = 101;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 102;
    private static final int PICK_CSV_REQUEST_CODE = 103;
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
    private String backlogSummary;
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
    private Button checkPageButton;
    private EditText keyInput;
    private EditText deviceNameInput;
    private EditText latInput;
    private EditText lonInput;
    private CheckBox autoUploadCheck;
    private CheckBox diagnosticCheck;
    private TextView sendLogText;
    private ScrollView sendLogScroll;
    private View pageCollect;
    private View pageSend;
    private TextView tabCollect;
    private TextView tabSend;
    private ArrayAdapter<String> scanListAdapter;

    // --- 보관함 관리 화면 ---
    private static final int ARCHIVE_ROW_LIMIT = 500;
    private static final long ARCHIVE_REFRESH_DELAY_MILLIS = 500L;
    private View pageArchive;
    private TextView tabArchive;
    private TextView archiveSummaryText;
    private Button archiveSendButton;
    private Button archiveRetryButton;
    private Button archiveImportButton;
    private Button archiveSaveButton;
    private TextView[] archiveFilterViews;
    private BacklogDb.Filter archiveFilter = BacklogDb.Filter.ALL;
    private final List<BacklogDb.Row> archiveRows = new ArrayList<>();
    private final List<String> archiveRowTexts = new ArrayList<>();
    private ArrayAdapter<String> archiveAdapter;
    private boolean archiveRefreshPosted;

    private final Runnable archiveRefresher = () -> {
        archiveRefreshPosted = false;
        refreshArchive();
    };

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
        checkPageButton = findViewById(R.id.checkPageButton);
        uploadStatusText = findViewById(R.id.uploadStatusText);
        keyInput = findViewById(R.id.keyInput);
        deviceNameInput = findViewById(R.id.deviceNameInput);
        latInput = findViewById(R.id.latInput);
        lonInput = findViewById(R.id.lonInput);
        autoUploadCheck = findViewById(R.id.autoUploadCheck);
        diagnosticCheck = findViewById(R.id.diagnosticCheck);
        sendLogText = findViewById(R.id.sendLogText);
        sendLogScroll = findViewById(R.id.sendLogScroll);
        pageCollect = findViewById(R.id.pageCollect);
        pageSend = findViewById(R.id.pageSend);
        tabCollect = findViewById(R.id.tabCollect);
        tabSend = findViewById(R.id.tabSend);
        ListView scanList = findViewById(R.id.scanList);
        pageArchive = findViewById(R.id.pageArchive);
        tabArchive = findViewById(R.id.tabArchive);
        archiveSummaryText = findViewById(R.id.archiveSummaryText);
        archiveSendButton = findViewById(R.id.archiveSendButton);
        archiveRetryButton = findViewById(R.id.archiveRetryButton);
        archiveImportButton = findViewById(R.id.archiveImportButton);
        archiveSaveButton = findViewById(R.id.archiveSaveButton);
        archiveFilterViews = new TextView[]{
                findViewById(R.id.archiveFilterAll),
                findViewById(R.id.archiveFilterPending),
                findViewById(R.id.archiveFilterFailed),
                findViewById(R.id.archiveFilterUploaded),
                findViewById(R.id.archiveFilterNoData)
        };
        ListView archiveList = findViewById(R.id.archiveList);
        archiveAdapter = new ArrayAdapter<>(this, R.layout.item_archive_row, archiveRowTexts);
        archiveList.setAdapter(archiveAdapter);
        archiveList.setOnItemClickListener((parent, view, position, id) ->
                showArchiveRowDialog(position));

        scanListAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, visibleRows);
        scanList.setAdapter(scanListAdapter);

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();

        startButton.setOnClickListener(v -> prepareAndStartScan());
        stopButton.setOnClickListener(v -> stopBleScan());
        saveButton.setOnClickListener(v -> saveCsv());
        checkPageButton.setOnClickListener(v -> openCheckPage());
        tabCollect.setOnClickListener(v -> showPage(PAGE_COLLECT));
        tabSend.setOnClickListener(v -> showPage(PAGE_SEND));
        tabArchive.setOnClickListener(v -> showPage(PAGE_ARCHIVE));
        archiveSendButton.setOnClickListener(v -> sendPending());
        archiveRetryButton.setOnClickListener(v -> retryFailed());
        archiveImportButton.setOnClickListener(v -> showCsvPicker());
        archiveSaveButton.setOnClickListener(v -> saveArchive());
        BacklogDb.Filter[] filters = BacklogDb.Filter.values();
        for (int i = 0; i < archiveFilterViews.length; i++) {
            final BacklogDb.Filter filter = filters[i];
            archiveFilterViews[i].setOnClickListener(v -> {
                archiveFilter = filter;
                refreshArchive();
            });
        }
        showPage(PAGE_COLLECT);

        applyUploadConfig(UploadConfig.load(this));
        keyInput.addTextChangedListener(configWatcher);
        deviceNameInput.addTextChangedListener(configWatcher);
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
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ADVERTISE: 파이에 과거 데이터를 요청하는 광고용
            // FINE/COARSE: 전송할 lat / lon (Android 12 부터는 둘을 같이 요청해야 한다)
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, PERMISSION_REQUEST_CODE);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, PERMISSION_REQUEST_CODE);
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
            // "대략적인 위치"만 허용해도 COARSE 결과 하나만 거부되므로 필요한 권한을 다시 확인한다.
            if (hasBlePermissions()) {
                appendLocalLog("BLE 권한 승인 완료.");
                prepareAndStartScan();
            } else {
                appendLocalLog("BLE·위치 권한이 거부되어 스캔할 수 없습니다.");
                Toast.makeText(this, "주변 기기와 정확한 위치 권한을 허용해 주세요.",
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
        if (requestCode == PICK_CSV_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                importCsv(uri, displayName(uri));
            }
        } else if (requestCode == ENABLE_BLUETOOTH_REQUEST_CODE) {
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

    /** 보관함에서 아직 전송되지 않은 행을 5주차 PDF 48쪽 API 로 지금 모두 POST 한다. */
    private void sendPending() {
        if (TextUtils.isEmpty(keyInput.getText().toString().trim())) {
            Toast.makeText(this, "팀별 API key 를 입력해 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        pushUploadConfig();
        if (serviceBound) {
            scanService.sendPendingNow();
        } else {
            startService(new Intent(this, BleScanService.class)
                    .setAction(BleScanService.ACTION_SEND_PENDING));
        }
    }

    /**
     * 앱 폴더(CSV 저장 위치)의 CSV 를 최신순으로 보여 준다. Android 11 부터는 시스템 파일
     * 선택기에서 Android/data 폴더가 보이지 않으므로 앱 폴더는 직접 나열하고,
     * 다른 곳으로 옮긴 파일은 "다른 위치…" 로 고른다.
     */
    private void showCsvPicker() {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            dir = getFilesDir();
        }
        File[] found = dir.listFiles((d, name) -> name.toLowerCase(Locale.US).endsWith(".csv"));
        final List<File> files = found == null
                ? new ArrayList<>() : new ArrayList<>(Arrays.asList(found));
        Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        String[] labels = new String[files.size()];
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            labels[i] = String.format(Locale.getDefault(), "%s  (%,d KB)",
                    f.getName(), Math.max(1L, f.length() / 1024L));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.csv_pick_title)
                .setNeutralButton(R.string.csv_pick_other, (d, w) -> openDocumentPicker())
                .setNegativeButton(R.string.cancel, null);
        if (files.isEmpty()) {
            builder.setMessage(R.string.csv_no_files);
        } else {
            builder.setItems(labels, (d, which) -> {
                File f = files.get(which);
                importCsv(Uri.fromFile(f), f.getName());
            });
        }
        builder.show();
    }

    private void openDocumentPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "text/csv", "text/comma-separated-values", "text/plain",
                        "application/csv", "application/octet-stream"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            startActivityForResult(intent, PICK_CSV_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        } catch (RuntimeException ignored) {
            // 이름을 못 얻으면 URI 의 마지막 부분을 쓴다.
        }
        String last = uri.getLastPathSegment();
        return last == null ? uri.toString() : last;
    }

    /** 수집 CSV·보관함 CSV 를 보관함으로 가져온다. 서버 전송은 보관함의 전송 큐가 맡는다. */
    private void importCsv(Uri uri, String name) {
        if (!serviceBound) {
            Toast.makeText(this, "잠시 후 다시 시도해 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        pushUploadConfig();
        archiveImportButton.setEnabled(false);
        archiveSummaryText.setText(getString(R.string.csv_loading, name));
        scanService.importCsv(uri, name, (success, message) -> {
            if (isDestroyed()) {
                return;
            }
            archiveImportButton.setEnabled(true);
            refreshArchive();
            new AlertDialog.Builder(this)
                    .setTitle(success ? R.string.archive_import_done : R.string.archive_import_failed)
                    .setMessage(message + (success && !autoUploadCheck.isChecked()
                            ? "\n\n자동 전송이 꺼져 있습니다. '지금 전송' 을 누르면 대기 행을 보냅니다."
                            : ""))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        });
    }

    private void saveArchive() {
        if (!serviceBound) {
            return;
        }
        archiveSaveButton.setEnabled(false);
        scanService.saveArchiveCsv((success, message) -> {
            if (isDestroyed()) {
                return;
            }
            archiveSaveButton.setEnabled(true);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void retryFailed() {
        if (!serviceBound) {
            return;
        }
        pushUploadConfig();
        int count = scanService.retryFailedRows();
        Toast.makeText(this, count == 0 ? "실패한 행이 없습니다." : "실패 " + count + "건을 대기로 되돌렸습니다.",
                Toast.LENGTH_SHORT).show();
    }

    /** 보관함 행을 누르면 자세한 내용과 관리 동작을 보여 준다. */
    private void showArchiveRowDialog(int position) {
        if (position < 0 || position >= archiveRows.size() || !serviceBound) {
            return;
        }
        final BacklogDb.Row row = archiveRows.get(position);
        if (row.noData) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.archive_row_title)
                    .setMessage(ArchiveCsv.formatKst(row.timestamp * 1000L) + " (KST)\n\n"
                            + getString(R.string.archive_no_data_detail))
                    .setNeutralButton(R.string.archive_request_again,
                            (d, w) -> scanService.requestAgain(row.timestamp))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        StringBuilder detail = new StringBuilder()
                .append("timestamp ").append(row.timestamp).append('\n')
                .append(ArchiveCsv.formatKst(row.timestamp * 1000L)).append(" (KST)\n\n")
                .append(String.format(Locale.getDefault(),
                        "온도 %.2f°C · 습도 %.2f%%\nAQI %d · TVOC %d ppb · eCO2 %d ppm\n",
                        row.temp, row.humidity, row.aqi, row.tvoc, row.eco2))
                .append(String.format(Locale.US, "lat %.6f · lon %.6f\n", row.lat, row.lon))
                .append(row.name).append(" · ").append(row.mac).append('\n')
                .append("출처 ").append(row.source).append(" · 수신 ")
                .append(ArchiveCsv.formatKst(row.receivedAt)).append("\n\n")
                .append("상태 ").append(statusLabel(row));
        if (row.uploadedAt > 0L) {
            detail.append("\n전송 ").append(ArchiveCsv.formatKst(row.uploadedAt));
        }
        if (row.attempts > 0) {
            detail.append("\n서버 거부 ").append(row.attempts).append("회");
        }
        if (!TextUtils.isEmpty(row.lastError)) {
            detail.append("\n마지막 오류: ").append(row.lastError);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.archive_row_title)
                .setMessage(detail.toString())
                .setNegativeButton(R.string.cancel, null);
        if (row.uploaded) {
            builder.setNeutralButton(R.string.archive_resend, (d, w) -> confirmResend(row));
        } else {
            builder.setNeutralButton(R.string.archive_mark_uploaded,
                    (d, w) -> scanService.markArchiveRowUploaded(row.timestamp));
            if (BacklogDb.STATUS_FAILED.equals(row.status())) {
                builder.setPositiveButton(R.string.archive_retry_one,
                        (d, w) -> scanService.resetArchiveRow(row.timestamp));
            }
        }
        builder.show();
    }

    private void confirmResend(BacklogDb.Row row) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.archive_resend)
                .setMessage(R.string.archive_resend_warning)
                .setPositiveButton(R.string.archive_resend,
                        (d, w) -> scanService.resetArchiveRow(row.timestamp))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 서비스 상태가 바뀔 때마다 부르지만, 보관함 화면이 보일 때만 0.5초에 한 번 다시 읽는다. */
    private void scheduleArchiveRefresh() {
        if (pageArchive.getVisibility() != View.VISIBLE || archiveRefreshPosted) {
            return;
        }
        archiveRefreshPosted = true;
        handler.postDelayed(archiveRefresher, ARCHIVE_REFRESH_DELAY_MILLIS);
    }

    private void refreshArchive() {
        BacklogDb.Filter[] filters = BacklogDb.Filter.values();
        for (int i = 0; i < archiveFilterViews.length; i++) {
            boolean selected = filters[i] == archiveFilter;
            archiveFilterViews[i].setTextColor(getColor(selected ? R.color.blue : R.color.text_secondary));
            archiveFilterViews[i].setTypeface(null,
                    selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (!serviceBound) {
            return;
        }
        BacklogDb.Stats stats = scanService.getArchiveStats();
        String range = stats.total == 0 ? "비어 있음"
                : ArchiveCsv.formatKst(stats.firstTs * 1000L).substring(5, 16) + " ~ "
                + ArchiveCsv.formatKst(stats.lastTs * 1000L).substring(5, 16);
        String lastSent = stats.lastUploadedAt > 0L
                ? ArchiveCsv.formatKst(stats.lastUploadedAt).substring(5) : "없음";
        archiveSummaryText.setText(getString(R.string.archive_summary_format,
                stats.total, stats.uploaded, stats.pending, stats.failed, stats.noData,
                range, lastSent));
        archiveRetryButton.setEnabled(stats.failed > 0);
        archiveSendButton.setEnabled(stats.pending > 0);

        archiveRows.clear();
        archiveRows.addAll(scanService.getArchiveRows(archiveFilter, ARCHIVE_ROW_LIMIT));
        archiveRowTexts.clear();
        for (BacklogDb.Row row : archiveRows) {
            archiveRowTexts.add(formatArchiveRow(row));
        }
        archiveAdapter.notifyDataSetChanged();
    }

    private String formatArchiveRow(BacklogDb.Row row) {
        if (row.noData) {
            return ArchiveCsv.formatKst(row.timestamp * 1000L).substring(5) + "   "
                    + statusLabel(row) + "\n파이에 이 시각 근처 데이터 없음 · 서버로 보내지 않음";
        }
        String line2 = String.format(Locale.getDefault(),
                "%.2f°C · %.1f%% · AQI %d · TVOC %d · eCO2 %d",
                row.temp, row.humidity, row.aqi, row.tvoc, row.eco2);
        return ArchiveCsv.formatKst(row.timestamp * 1000L).substring(5) + "   "
                + statusLabel(row) + "\n" + line2;
    }

    private static String statusLabel(BacklogDb.Row row) {
        switch (row.status()) {
            case BacklogDb.STATUS_UPLOADED:
                return "✓ 전송 완료" + (row.serverStatus == null ? "" : "(" + row.serverStatus + ")");
            case BacklogDb.STATUS_FAILED:
                return "✗ 실패";
            case BacklogDb.STATUS_NO_DATA:
                return "– 데이터 없음";
            default:
                return TextUtils.isEmpty(row.lastError) ? "· 대기" : "· 대기(재시도)";
        }
    }

    private static final int PAGE_COLLECT = 0;
    private static final int PAGE_SEND = 1;
    private static final int PAGE_ARCHIVE = 2;

    /** 하단 탭 전환. 앱을 열면 수집 페이지가 먼저 보인다. */
    private void showPage(int page) {
        pageCollect.setVisibility(page == PAGE_COLLECT ? View.VISIBLE : View.GONE);
        pageSend.setVisibility(page == PAGE_SEND ? View.VISIBLE : View.GONE);
        pageArchive.setVisibility(page == PAGE_ARCHIVE ? View.VISIBLE : View.GONE);
        tabCollect.setTextColor(getColor(page == PAGE_COLLECT ? R.color.blue : R.color.text_secondary));
        tabSend.setTextColor(getColor(page == PAGE_SEND ? R.color.blue : R.color.text_secondary));
        tabArchive.setTextColor(getColor(page == PAGE_ARCHIVE ? R.color.blue : R.color.text_secondary));
        if (page == PAGE_ARCHIVE) {
            refreshArchive();
        }
    }

    private void appendSendLog(String line) {
        sendLogText.append(line + "\n");
        sendLogScroll.post(() -> sendLogScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 5주차 PDF 50쪽: 팀별 수집 현황 페이지를 브라우저로 연다. */
    private void openCheckPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SensorUploader.CHECK_URL)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, SensorUploader.CHECK_URL, Toast.LENGTH_LONG).show();
        }
    }

    private void applyUploadConfig(UploadConfig config) {
        applyingConfig = true;
        keyInput.setText(config.apiKey);
        deviceNameInput.setText(config.deviceName);
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
        return new UploadConfig(
                keyInput.getText().toString(),
                deviceNameInput.getText().toString(),
                autoUploadCheck.isChecked(),
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
        backlogSummary = state.backlogSummary;

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
        scheduleArchiveRefresh();
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
        String status = getString(R.string.upload_status_format,
                uploadSuccessCount, uploadFailureCount, response);
        if (!TextUtils.isEmpty(backlogSummary)) {
            status += "\n" + backlogSummary;
        }
        uploadStatusText.setText(status);
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
