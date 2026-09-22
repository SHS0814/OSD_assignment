package com.example.rpiblecollector;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * PDF 21쪽 요청 양식 중 사용자가 직접 정해야 하는 값(team, sensor)과
 * 자동 전송 주기를 보관한다. 값은 SharedPreferences 에 유지된다.
 */
public final class UploadConfig {
    private static final String PREFS_NAME = "upload_preferences";
    private static final String KEY_TEAM = "team";
    private static final String KEY_SENSOR = "sensor";
    private static final String KEY_AUTO_UPLOAD = "auto_upload";
    private static final String KEY_INTERVAL_SECONDS = "interval_seconds";

    public static final String DEFAULT_TEAM = "team9";
    public static final String DEFAULT_SENSOR = "opensrc_week_3";
    public static final int DEFAULT_INTERVAL_SECONDS = 10;
    public static final int MIN_INTERVAL_SECONDS = 1;

    public final String team;
    public final String sensor;
    public final boolean autoUpload;
    public final int intervalSeconds;

    public UploadConfig(String team, String sensor, boolean autoUpload, int intervalSeconds) {
        this.team = TextUtils.isEmpty(team) ? DEFAULT_TEAM : team.trim();
        this.sensor = sensor == null ? "" : sensor.trim();
        this.autoUpload = autoUpload;
        this.intervalSeconds = Math.max(MIN_INTERVAL_SECONDS, intervalSeconds);
    }

    public long intervalMillis() {
        return intervalSeconds * 1000L;
    }

    public static UploadConfig load(Context context) {
        SharedPreferences prefs = prefs(context);
        return new UploadConfig(
                prefs.getString(KEY_TEAM, DEFAULT_TEAM),
                prefs.getString(KEY_SENSOR, DEFAULT_SENSOR),
                prefs.getBoolean(KEY_AUTO_UPLOAD, false),
                prefs.getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS));
    }

    public void save(Context context) {
        prefs(context).edit()
                .putString(KEY_TEAM, team)
                .putString(KEY_SENSOR, sensor)
                .putBoolean(KEY_AUTO_UPLOAD, autoUpload)
                .putInt(KEY_INTERVAL_SECONDS, intervalSeconds)
                .apply();
    }

    /**
     * PDF 21쪽: 스마트폰 UUID
     * {@code Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID)}
     */
    @SuppressLint("HardwareIds")
    public static String senderId(Context context) {
        String deviceId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        return TextUtils.isEmpty(deviceId) ? "unknown-device" : deviceId;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
