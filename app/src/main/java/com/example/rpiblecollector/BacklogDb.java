package com.example.rpiblecollector;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 우리 팀 파이에서 받은 샘플과 서버 전송 여부.
 *
 * <p>실시간·과거 구분 없이 timestamp 를 기본키로 한 번만 저장한다. 서버 전송은
 * 이 테이블의 uploaded=0 인 행을 순서대로 보내므로 같은 timestamp 가 두 번 올라가지 않고,
 * 앱이 종료돼도 보내지 못한 데이터가 남는다.
 */
public final class BacklogDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "backlog.db";
    private static final int DB_VERSION = 1;
    private static final String META_OLDEST_TS = "pi_oldest_ts";

    /** 서버로 보낼 한 행. */
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
        }
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
                + "received_at INTEGER NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX samples_pending ON samples(uploaded, received_at)");
        db.execSQL("CREATE TABLE gaps (ts INTEGER PRIMARY KEY)");
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    /** 새 timestamp 면 저장하고 true. 이미 있으면 false. */
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
        return getWritableDatabase().insertWithOnConflict(
                "samples", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L;
    }

    public TreeSet<Long> timestamps() {
        TreeSet<Long> out = new TreeSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts FROM samples", null)) {
            while (c.moveToNext()) {
                out.add(c.getLong(0));
            }
        }
        return out;
    }

    public Set<Long> gaps() {
        Set<Long> out = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT ts FROM gaps", null)) {
            while (c.moveToNext()) {
                out.add(c.getLong(0));
            }
        }
        return out;
    }

    public void addGap(long ts) {
        ContentValues v = new ContentValues();
        v.put("ts", ts);
        getWritableDatabase().insertWithOnConflict(
                "gaps", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** 가장 먼저 받은 미전송 행. 없으면 null. */
    public Row nextPending() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, temp, humidity, aqi, tvoc, eco2, mac, name, lat, lon FROM samples "
                        + "WHERE uploaded = 0 ORDER BY received_at, ts LIMIT 1", null)) {
            return c.moveToFirst() ? new Row(c) : null;
        }
    }

    public void markUploaded(long ts) {
        ContentValues v = new ContentValues();
        v.put("uploaded", 1);
        getWritableDatabase().update("samples", v, "ts = ?",
                new String[]{String.valueOf(ts)});
    }

    public int pendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM samples WHERE uploaded = 0", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
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
