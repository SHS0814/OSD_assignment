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
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";

    /** 서버 수신 현황 페이지의 다른 팀 표기가 "4" 이므로 숫자만 쓴다. */
    public static final String DEFAULT_TEAM = "9";
    /**
     * 서버 수신 현황 페이지에서 "검증 성공" 한 레코드들이 쓰는 센서명.
     * BLE 광고의 장치 이름(opensrc_week_3)과는 다르다.
     */
    public static final String DEFAULT_SENSOR = "environment_sensor";
    public static final int DEFAULT_INTERVAL_SECONDS = 10;
    public static final int MIN_INTERVAL_SECONDS = 1;
    /**
     * 라즈베리 파이(D8:3A:DD:C1:89:2E)의 위치.
     * 서버 수신 현황 페이지에서 같은 MAC 으로 "검증 성공" 한 레코드들이 쓰는 좌표를 기본값으로 둔다.
     */
    public static final double DEFAULT_LATITUDE = 36.629011;
    public static final double DEFAULT_LONGITUDE = 127.457092;

    public final String team;
    public final String sensor;
    public final boolean autoUpload;
    public final int intervalSeconds;
    /**
     * PDF 21쪽 요청 양식의 lat / lon. 센서(라즈베리 파이)가 고정 위치에 있으므로
     * GPS 대신 화면에서 입력받는다. Android 12+ 에서 위치 권한을 선언하면
     * BLUETOOTH_SCAN 의 neverForLocation 과 충돌해 스캔이 막히기 때문이다.
     */
    public final double latitude;
    public final double longitude;

    public UploadConfig(String team, String sensor, boolean autoUpload, int intervalSeconds,
                        double latitude, double longitude) {
        this.team = TextUtils.isEmpty(team) ? DEFAULT_TEAM : team.trim();
        this.sensor = sensor == null ? "" : sensor.trim();
        this.autoUpload = autoUpload;
        this.intervalSeconds = Math.max(MIN_INTERVAL_SECONDS, intervalSeconds);
        this.latitude = latitude;
        this.longitude = longitude;
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
                prefs.getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS),
                Double.longBitsToDouble(prefs.getLong(KEY_LATITUDE,
                        Double.doubleToRawLongBits(DEFAULT_LATITUDE))),
                Double.longBitsToDouble(prefs.getLong(KEY_LONGITUDE,
                        Double.doubleToRawLongBits(DEFAULT_LONGITUDE))));
    }

    public void save(Context context) {
        prefs(context).edit()
                .putString(KEY_TEAM, team)
                .putString(KEY_SENSOR, sensor)
                .putBoolean(KEY_AUTO_UPLOAD, autoUpload)
                .putInt(KEY_INTERVAL_SECONDS, intervalSeconds)
                .putLong(KEY_LATITUDE, Double.doubleToRawLongBits(latitude))
                .putLong(KEY_LONGITUDE, Double.doubleToRawLongBits(longitude))
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
