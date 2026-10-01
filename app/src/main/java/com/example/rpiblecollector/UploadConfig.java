package com.example.rpiblecollector;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * 5주차 PDF 48쪽 요청 양식 중 사용자가 직접 정해야 하는 값(key)과
 * 전송 대상 BLE 장치 이름, 자동 전송 주기를 보관한다. 값은 SharedPreferences 에 유지된다.
 *
 * <p>요청의 {@code sensor} 는 따로 입력받지 않고 대상 장치 이름(파이의 광고 로컬네임)을
 * 그대로 쓴다. 이름이 두 개면 헷갈리기 쉽고, 서버에는 정렬·중복 처리가 없어 중간에
 * 이름이 바뀌면 센서가 둘인 것처럼 보이기 때문이다.
 */
public final class UploadConfig {
    private static final String PREFS_NAME = "upload_preferences";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_AUTO_UPLOAD = "auto_upload";
    private static final String KEY_LATITUDE = "latitude";
    private static final String KEY_LONGITUDE = "longitude";

    /**
     * 팀별로 발급된 key. PDF 48쪽 예시(3조 = opensrc-team3)의 형식을 따른 기본값이며,
     * 조교에게 받은 키가 다르면 화면에서 바꾼다.
     */
    public static final String DEFAULT_API_KEY = "opensrc-team9";
    /**
     * 우리 팀 라즈베리 파이의 광고 localname (PDF 44쪽: 팀 번호로 설정).
     * 주변의 다른 팀 파이도 같은 0x181A 로 광고하므로 이 이름인 패킷만 서버로 보낸다.
     */
    public static final String DEFAULT_DEVICE_NAME = "Opensrc_team9";
    /** 센서(라즈베리 파이)를 설치한 위치. 설치 위치를 옮기면 화면에서 바꾼다. */
    public static final double DEFAULT_LATITUDE = 36.629011;
    public static final double DEFAULT_LONGITUDE = 127.457092;

    public final String apiKey;
    /** 전송 대상 BLE 장치 이름. 비어 있으면 0x181A 패킷을 모두 전송한다. */
    public final String deviceName;
    /** 켜져 있으면 장부에 대기 행이 생기는 대로 보낸다. 꺼져 있으면 "지금 전송" 때만 보낸다. */
    public final boolean autoUpload;
    /**
     * 요청 양식의 lat / lon 기본값. 실제 전송에는 GPS 위치(LocationTracker)를 쓰고,
     * 위치를 얻지 못했을 때만 화면에서 입력한 이 좌표를 쓴다.
     */
    public final double latitude;
    public final double longitude;

    public UploadConfig(String apiKey, String deviceName,
                        boolean autoUpload,
                        double latitude, double longitude) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.deviceName = deviceName == null ? "" : deviceName.trim();
        this.autoUpload = autoUpload;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    /**
     * 요청의 {@code sensor} 값: 대상 장치 이름. 이름을 비워 두어 모든 장치를 받는 경우에만
     * 그 데이터가 수신된 장치 이름(fallback)을 쓴다.
     */
    public String sensorName(String fallback) {
        if (!deviceName.isEmpty()) {
            return deviceName;
        }
        return fallback == null ? "" : fallback;
    }

    /** 이 레코드가 우리 팀 센서에서 온 것인지. 장치 이름을 비워 두면 모두 허용한다. */
    public boolean matchesDevice(BleRecord record) {
        return deviceName.isEmpty() || deviceName.equalsIgnoreCase(record.name);
    }

    public static UploadConfig load(Context context) {
        SharedPreferences prefs = prefs(context);
        return new UploadConfig(
                prefs.getString(KEY_API_KEY, DEFAULT_API_KEY),
                prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME),
                prefs.getBoolean(KEY_AUTO_UPLOAD, false),
                Double.longBitsToDouble(prefs.getLong(KEY_LATITUDE,
                        Double.doubleToRawLongBits(DEFAULT_LATITUDE))),
                Double.longBitsToDouble(prefs.getLong(KEY_LONGITUDE,
                        Double.doubleToRawLongBits(DEFAULT_LONGITUDE))));
    }

    public void save(Context context) {
        prefs(context).edit()
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_DEVICE_NAME, deviceName)
                .putBoolean(KEY_AUTO_UPLOAD, autoUpload)
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
