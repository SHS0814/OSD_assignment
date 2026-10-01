package com.example.rpiblecollector;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 보관함: 우리 팀 파이에서 받은 샘플과 서버 전송 기록.
 *
 * <p>수집 → 보관함 → 서버. BLE 로 받은 샘플과 CSV 에서 가져온 샘플은 모두 이 보관함에
 * timestamp 를 기본키로 한 번만 들어가고, 서버 전송은 보관함에서 아직 전송되지 않은 행만
 * timestamp 순서대로 보낸다. 그래서 같은 timestamp 가 서버로 두 번 가지 않고, 앱이
 * 종료돼도 보내지 못한 데이터가 남는다.
 *
 * <p>상태: 전송 완료(uploaded=1) / 대기(uploaded=0, 시도 {@link #MAX_ATTEMPTS} 회 미만) /
 * 실패(uploaded=0, 서버가 {@link #MAX_ATTEMPTS} 회 거부) / 데이터 없음(no_data=1).
 * 실패한 행은 자동으로 다시 보내지 않고, 관리 화면의 "실패 재시도" 로 대기로 되돌린다.
 *
 * <p>데이터 없음: 파이가 "요청한 시각 근처에 샘플이 없다" 고 답한 특수값 패킷이다. 같은 시각을
 * 다시 요청하지 않도록 보관함에는 남기지만 측정값이 아니므로 서버로는 절대 보내지 않는다.
 * 나중에 같은 timestamp 의 실제 샘플이 들어오면 실제 샘플로 바꾼다.
 */
public final class BacklogDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "backlog.db";
    private static final int DB_VERSION = 3;
    private static final String META_OLDEST_TS = "pi_oldest_ts";

    /** 서버가 이 횟수만큼 거부하면 자동 전송을 멈추고 실패로 둔다. */
    public static final int MAX_ATTEMPTS = 3;

    public static final String SOURCE_BLE = "ble";
    public static final String SOURCE_CSV = "csv";

    public static final String STATUS_UPLOADED = "uploaded";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_NO_DATA = "no_data";

    /** 관리 화면 필터. */
    public enum Filter { ALL, PENDING, FAILED, UPLOADED, NO_DATA }

    private static final String COLUMNS =
            "ts, temp, humidity, aqi, tvoc, eco2, mac, name, lat, lon, received_at, "
                    + "uploaded, uploaded_at, server_status, attempts, last_error, source, no_data";

    /** 서버로 보낼 행: 측정값이고, 아직 안 보냈고, 실패 처리되지 않은 행. */
    private static final String PENDING_WHERE =
            "no_data = 0 AND uploaded = 0 AND attempts < " + MAX_ATTEMPTS;
    private static final String FAILED_WHERE =
            "no_data = 0 AND uploaded = 0 AND attempts >= " + MAX_ATTEMPTS;

    /** 보관함 한 행. */
    public static final class Row {
        public final long timestamp;
        public final double temp;
        public final double humidity;
        public final int aqi;
        public final int tvoc;
        public final int eco2;
        public final String mac;
        public final String name;
        public final double lat;
        public final double lon;
        public final long receivedAt;
        public final boolean uploaded;
        /** 서버 응답을 받은 시각(ms). 모르면 0. */
        public final long uploadedAt;
        /** 서버가 준 status (ok / out_of_range). */
        public final String serverStatus;
        public final int attempts;
        public final String lastError;
        public final String source;
        /** 파이가 "데이터 없음" 으로 답한 시각. 서버로 보내지 않는다. */
        public final boolean noData;

        Row(Cursor c) {
            timestamp = c.getLong(0);
            temp = c.getDouble(1);
            humidity = c.getDouble(2);
            aqi = c.getInt(3);
            tvoc = c.getInt(4);
            eco2 = c.getInt(5);
            mac = c.getString(6);
            name = c.getString(7);
            lat = c.getDouble(8);
            lon = c.getDouble(9);
            receivedAt = c.getLong(10);
            uploaded = c.getInt(11) != 0;
            uploadedAt = c.isNull(12) ? 0L : c.getLong(12);
            serverStatus = c.getString(13);
            attempts = c.getInt(14);
            lastError = c.getString(15);
            source = c.getString(16);
            noData = c.getInt(17) != 0;
        }

        public ArchiveCsv.Entry toEntry() {
            ArchiveCsv.Entry e = new ArchiveCsv.Entry();
            e.timestamp = timestamp;
            e.temp = temp;
            e.humidity = humidity;
            e.aqi = aqi;
            e.tvoc = tvoc;
            e.eco2 = eco2;
            e.mac = mac;
            e.name = name;
            e.lat = lat;
            e.lon = lon;
            e.receivedAt = receivedAt;
            e.source = source;
            e.uploaded = uploaded;
            e.uploadedAt = uploadedAt;
            e.serverStatus = serverStatus;
            e.status = status();
            e.attempts = attempts;
            e.lastError = lastError;
            e.noData = noData;
            return e;
        }

        public String status() {
            if (noData) {
                return STATUS_NO_DATA;
            }
            if (uploaded) {
                return STATUS_UPLOADED;
            }
            return attempts >= MAX_ATTEMPTS ? STATUS_FAILED : STATUS_PENDING;
        }
    }

    /** 보관함 건수 요약. */
    public static final class Stats {
        public int total;
        public int uploaded;
        public int pending;
        public int failed;
        public int noData;
        public long firstTs = -1L;
        public long lastTs = -1L;
        public long lastUploadedAt;
    }

    public BacklogDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE samples ("
                + "ts INTEGER PRIMARY KEY, temp REAL NOT NULL, humidity REAL NOT NULL, "
                + "aqi INTEGER NOT NULL, tvoc INTEGER NOT NULL, eco2 INTEGER NOT NULL, "
                + "mac TEXT, name TEXT, lat REAL, lon REAL, "
                + "received_at INTEGER NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0, "
                + "uploaded_at INTEGER, server_status TEXT, "
                + "attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT, "
                + "source TEXT NOT NULL DEFAULT 'ble', no_data INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX samples_pending ON samples(uploaded, ts)");
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v1 보관함에는 전송 여부만 있었다. 기록 항목을 붙이고 전송 순서를 timestamp 순으로 바꾼다.
            db.execSQL("ALTER TABLE samples ADD COLUMN uploaded_at INTEGER");
            db.execSQL("ALTER TABLE samples ADD COLUMN server_status TEXT");
            db.execSQL("ALTER TABLE samples ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE samples ADD COLUMN last_error TEXT");
            db.execSQL("ALTER TABLE samples ADD COLUMN source TEXT NOT NULL DEFAULT 'ble'");
            db.execSQL("DROP INDEX IF EXISTS samples_pending");
            db.execSQL("CREATE INDEX samples_pending ON samples(uploaded, ts)");
        }
        if (oldVersion < 3) {
            // v2 까지 "데이터 없음" 은 별도 gaps 테이블에만 있었다. 보관함으로 옮긴다.
            db.execSQL("ALTER TABLE samples ADD COLUMN no_data INTEGER NOT NULL DEFAULT 0");
            db.execSQL("INSERT OR IGNORE INTO samples "
                    + "(ts, temp, humidity, aqi, tvoc, eco2, received_at, source, no_data) "
                    + "SELECT ts, " + NO_DATA_TEMP + ", " + NO_DATA_HUMIDITY
                    + ", 0, 0, 0, ts * 1000, '" + SOURCE_BLE + "', 1 FROM gaps");
            db.execSQL("DROP TABLE gaps");
        }
    }

    /** 파이의 "데이터 없음" 패킷 값 (온도 0x7FFF, 습도 0xFFFF 를 100 으로 나눈 값). */
    static final double NO_DATA_TEMP = 327.67;
    static final double NO_DATA_HUMIDITY = 655.35;

    /** BLE 로 받은 샘플. 새 timestamp 면 저장하고 true. 이미 있으면 false. */
    public boolean insert(BleRecord record) {
        SensorPacket p = record.sensor;
        ContentValues v = new ContentValues();
        v.put("ts", p.timestamp);
        v.put("temp", round2(p.temperature));
        v.put("humidity", round2(p.humidity));
        v.put("aqi", p.aqi);
        v.put("tvoc", p.tvoc);
        v.put("eco2", p.eco2);
        v.put("mac", record.address);
        v.put("name", record.name);
        v.put("lat", record.latitude);
        v.put("lon", record.longitude);
        v.put("received_at", record.receivedAtMillis);
        v.put("source", SOURCE_BLE);
        SQLiteDatabase db = getWritableDatabase();
        return db.insertWithOnConflict("samples", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
                || replaceNoData(db, p.timestamp, v);
    }

    /** 파이의 "데이터 없음" 응답. 새로 기록했으면 true. 이미 무엇이든 있으면 false. */
    public boolean insertNoData(BleRecord record) {
        ContentValues v = new ContentValues();
        v.put("ts", record.sensor.timestamp);
        v.put("temp", NO_DATA_TEMP);
        v.put("humidity", NO_DATA_HUMIDITY);
        v.put("aqi", 0);
        v.put("tvoc", 0);
        v.put("eco2", 0);
        v.put("mac", record.address);
        v.put("name", record.name);
        v.put("lat", record.latitude);
        v.put("lon", record.longitude);
        v.put("received_at", record.receivedAtMillis);
        v.put("source", SOURCE_BLE);
        v.put("no_data", 1);
        return getWritableDatabase().insertWithOnConflict(
                "samples", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L;
    }

    /** 같은 timestamp 가 "데이터 없음" 으로만 있으면 실제 샘플로 바꾼다. */
    private static boolean replaceNoData(SQLiteDatabase db, long ts, ContentValues v) {
        ContentValues u = new ContentValues(v);
        u.remove("ts");
        u.put("no_data", 0);
        u.put("uploaded", 0);
        u.put("attempts", 0);
        u.putNull("uploaded_at");
        u.putNull("server_status");
        u.putNull("last_error");
        return db.update("samples", u, "ts = ? AND no_data = 1",
                new String[]{String.valueOf(ts)}) > 0;
    }

    /** CSV 가져오기 결과. */
    public static final class ImportResult {
        public int added;
        public int addedUploaded;
        /** 보관함에는 대기였지만 CSV 에 전송 완료로 기록돼 있어 완료로 바꾼 행. */
        public int markedUploaded;
        /** 새로 기록한 "데이터 없음" 시각. */
        public int addedNoData;
        /** "데이터 없음" 이던 시각을 CSV 의 실제 샘플로 바꾼 행. */
        public int replacedNoData;
        public int existing;
    }

    /**
     * CSV 에서 읽은 행을 보관함에 합친다. 이미 있는 timestamp 는 값을 덮어쓰지 않고,
     * CSV 쪽이 전송 완료면 보관함도 전송 완료로 올린다(완료 → 대기로 내리지는 않는다).
     */
    public ImportResult importRows(List<ArchiveCsv.Entry> entries) {
        ImportResult result = new ImportResult();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (ArchiveCsv.Entry e : entries) {
                ContentValues v = new ContentValues();
                v.put("ts", e.timestamp);
                v.put("temp", e.temp);
                v.put("humidity", e.humidity);
                v.put("aqi", e.aqi);
                v.put("tvoc", e.tvoc);
                v.put("eco2", e.eco2);
                v.put("mac", e.mac);
                v.put("name", e.name);
                v.put("lat", e.lat);
                v.put("lon", e.lon);
                v.put("received_at", e.receivedAt);
                v.put("source", e.source == null ? SOURCE_CSV : e.source);
                if (e.noData) {
                    v.put("no_data", 1);
                    if (db.insertWithOnConflict("samples", null, v,
                            SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                        result.addedNoData++;
                    } else {
                        result.existing++;
                    }
                    continue;
                }
                v.put("uploaded", e.uploaded ? 1 : 0);
                if (e.uploaded) {
                    if (e.uploadedAt > 0L) {
                        v.put("uploaded_at", e.uploadedAt);
                    }
                    v.put("server_status", e.serverStatus);
                }
                if (db.insertWithOnConflict("samples", null, v,
                        SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    if (e.uploaded) {
                        result.addedUploaded++;
                    } else {
                        result.added++;
                    }
                } else if (replaceNoData(db, e.timestamp, v)) {
                    if (e.uploaded) {
                        markUploaded(db, e.timestamp,
                                e.uploadedAt > 0L ? e.uploadedAt : null, e.serverStatus);
                    }
                    result.replacedNoData++;
                } else if (e.uploaded && markUploaded(db, e.timestamp,
                        e.uploadedAt > 0L ? e.uploadedAt : null, e.serverStatus)) {
                    result.markedUploaded++;
                } else {
                    result.existing++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return result;
    }

    public TreeSet<Long> timestamps() {
        TreeSet<Long> out = new TreeSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts FROM samples WHERE no_data = 0", null)) {
            while (c.moveToNext()) {
                out.add(c.getLong(0));
            }
        }
        return out;
    }

    /** 파이가 "데이터 없음" 이라고 답한 시각. 다시 요청하지 않는다. */
    public Set<Long> gaps() {
        Set<Long> out = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts FROM samples WHERE no_data = 1", null)) {
            while (c.moveToNext()) {
                out.add(c.getLong(0));
            }
        }
        return out;
    }

    /** "데이터 없음" 기록을 지워 그 시각을 다시 요청하게 한다. */
    public void deleteNoData(long ts) {
        getWritableDatabase().delete("samples", "ts = ? AND no_data = 1",
                new String[]{String.valueOf(ts)});
    }

    /** 다음에 보낼 행: 전송 안 됐고 실패 처리되지 않은 가장 오래된 timestamp. 없으면 null. */
    public Row nextPending() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLUMNS + " FROM samples WHERE " + PENDING_WHERE
                        + " ORDER BY ts LIMIT 1", null)) {
            return c.moveToFirst() ? new Row(c) : null;
        }
    }

    public Row get(long ts) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLUMNS + " FROM samples WHERE ts = ?",
                new String[]{String.valueOf(ts)})) {
            return c.moveToFirst() ? new Row(c) : null;
        }
    }

    /** 서버가 받았다고 응답한 행. */
    public void markUploaded(long ts, long uploadedAtMillis, String serverStatus) {
        markUploaded(getWritableDatabase(), ts, uploadedAtMillis, serverStatus);
    }

    private static boolean markUploaded(SQLiteDatabase db, long ts, Long uploadedAt,
                                        String serverStatus) {
        ContentValues v = new ContentValues();
        v.put("uploaded", 1);
        if (uploadedAt != null) {
            v.put("uploaded_at", uploadedAt);
        }
        v.put("server_status", serverStatus);
        v.putNull("last_error");
        return db.update("samples", v, "ts = ? AND uploaded = 0 AND no_data = 0",
                new String[]{String.valueOf(ts)}) > 0;
    }

    /**
     * 전송 실패 기록.
     *
     * @param countAttempt 서버가 거부했으면 true (시도 횟수 증가).
     *                     네트워크 오류처럼 서버 응답이 없으면 false.
     */
    public void markFailed(long ts, String error, boolean countAttempt) {
        getWritableDatabase().execSQL(
                "UPDATE samples SET last_error = ?, attempts = attempts + ? WHERE ts = ?",
                new Object[]{error, countAttempt ? 1 : 0, ts});
    }

    /** 실패한 행을 모두 대기로 되돌린다. 되돌린 행 수. */
    public int retryFailed() {
        ContentValues v = new ContentValues();
        v.put("attempts", 0);
        return getWritableDatabase().update("samples", v, FAILED_WHERE, null);
    }

    /** 한 행을 대기로 되돌린다. 전송 완료 행을 되돌리면 서버로 한 번 더 간다. */
    public void resetToPending(long ts) {
        ContentValues v = new ContentValues();
        v.put("uploaded", 0);
        v.put("attempts", 0);
        v.putNull("uploaded_at");
        v.putNull("server_status");
        v.putNull("last_error");
        getWritableDatabase().update("samples", v, "ts = ? AND no_data = 0",
                new String[]{String.valueOf(ts)});
    }

    /** 다른 경로로 서버에 들어간 것을 알고 있을 때 전송 완료로만 표시한다. */
    public void markUploadedManually(long ts) {
        markUploaded(getWritableDatabase(), ts, System.currentTimeMillis(), "manual");
    }

    public int pendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM samples WHERE " + PENDING_WHERE, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public Stats stats() {
        Stats s = new Stats();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), "
                        + "SUM(no_data = 0 AND uploaded = 1), "
                        + "SUM(" + PENDING_WHERE + "), "
                        + "SUM(" + FAILED_WHERE + "), "
                        + "MIN(ts), MAX(ts), MAX(uploaded_at), SUM(no_data = 1) FROM samples",
                null)) {
            if (c.moveToFirst()) {
                s.total = c.getInt(0);
                s.uploaded = c.getInt(1);
                s.pending = c.getInt(2);
                s.failed = c.getInt(3);
                s.firstTs = c.isNull(4) ? -1L : c.getLong(4);
                s.lastTs = c.isNull(5) ? -1L : c.getLong(5);
                s.lastUploadedAt = c.isNull(6) ? 0L : c.getLong(6);
                s.noData = c.getInt(7);
            }
        }
        return s;
    }

    /** 관리 화면 목록. 최신 timestamp 가 위. */
    public List<Row> rows(Filter filter, int limit) {
        String where;
        switch (filter) {
            case PENDING:
                where = " WHERE " + PENDING_WHERE;
                break;
            case FAILED:
                where = " WHERE " + FAILED_WHERE;
                break;
            case UPLOADED:
                where = " WHERE no_data = 0 AND uploaded = 1";
                break;
            case NO_DATA:
                where = " WHERE no_data = 1";
                break;
            default:
                where = "";
        }
        List<Row> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLUMNS + " FROM samples" + where + " ORDER BY ts DESC LIMIT "
                        + limit, null)) {
            while (c.moveToNext()) {
                out.add(new Row(c));
            }
        }
        return out;
    }

    /** 보관함 CSV 저장용 전체 행. timestamp 순. */
    public List<Row> allRowsAscending() {
        List<Row> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COLUMNS + " FROM samples ORDER BY ts", null)) {
            while (c.moveToNext()) {
                out.add(new Row(c));
            }
        }
        return out;
    }

    /** 파이 데이터가 처음 시작된 시각. 아직 모르면 -1. */
    public long oldestTimestamp() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT value FROM meta WHERE key = ?", new String[]{META_OLDEST_TS})) {
            return c.moveToFirst() ? Long.parseLong(c.getString(0)) : -1L;
        }
    }

    public void setOldestTimestamp(long ts) {
        ContentValues v = new ContentValues();
        v.put("key", META_OLDEST_TS);
        v.put("value", String.valueOf(ts));
        getWritableDatabase().insertWithOnConflict(
                "meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static double round2(float value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
