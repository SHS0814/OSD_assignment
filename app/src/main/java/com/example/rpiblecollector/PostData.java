package com.example.rpiblecollector;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.util.Locale;

/**
 * 4주차 PDF 20쪽의 {@code postdata} 클래스에 해당한다.
 * JSON 형식으로 보낼 때에는 JSON 형식을 만들어 줘야 하므로,
 * PDF 21쪽 "요청 양식"의 key 이름을 {@link SerializedName} 으로 그대로 고정한다.
 *
 * <pre>
 * {
 *   "key": "opensrc2026",
 *   "team": "team TA",
 *   "sensor": "sensor TA",
 *   "mac": "AA:BB:CC:DD:EE:FF",
 *   "temp": 11,
 *   "humidity": 22,
 *   "AQI": 33,
 *   "TVOC": 44,
 *   "eCO2": 55,
 *   "timestamp": 66,
 *   "lat": 7.7,
 *   "lon": 8.8,
 *   "sender": "abcd-1234-5679-11"
 * }
 * </pre>
 */
public final class PostData {
    /** PDF 21쪽: 서버로 보낼 때 일치해야 하는 key. */
    public static final String API_KEY = "opensrc2026";

    @Expose
    @SerializedName("key")
    private String key;

    @Expose
    @SerializedName("team")
    private String team;

    @Expose
    @SerializedName("sensor")
    private String sensor;

    @Expose
    @SerializedName("mac")
    private String mac;

    @Expose
    @SerializedName("temp")
    private double temp;

    @Expose
    @SerializedName("humidity")
    private double humidity;

    @Expose
    @SerializedName("AQI")
    private int AQI;

    @Expose
    @SerializedName("TVOC")
    private int TVOC;

    @Expose
    @SerializedName("eCO2")
    private int eCO2;

    @Expose
    @SerializedName("timestamp")
    private long timestamp;

    @Expose
    @SerializedName("lat")
    private double lat;

    @Expose
    @SerializedName("lon")
    private double lon;

    @Expose
    @SerializedName("sender")
    private String sender;

    /**
     * 0x181A ServiceData 원본 hex (센서 13바이트 + HMAC 태그).
     *
     * <p>PDF 21쪽 요청 양식에는 없지만 서버가 요구한다. 서버는 이 raw 를 다시 해석해
     * 위 센서 값들과 대조하고, raw 안의 HMAC 태그를 mac 주소와 함께 검증한다.
     * 이 필드가 없으면 서버가 {@code no_raw "raw 필드가 없습니다"} 로 거절한다.
     */
    @Expose
    @SerializedName("raw")
    private String raw;

    public PostData() {
    }

    /** PDF 20쪽 {@code set_data()} 에 대응하는 설정 메서드. */
    public void set_data(String team, String sensor, String mac,
                         double temp, double humidity, int aqi, int tvoc, int eco2,
                         long timestamp, double lat, double lon, String sender,
                         String raw) {
        this.key = API_KEY;
        this.team = team;
        this.sensor = sensor;
        this.mac = mac;
        this.temp = temp;
        this.humidity = humidity;
        this.AQI = aqi;
        this.TVOC = tvoc;
        this.eCO2 = eco2;
        this.timestamp = timestamp;
        this.lat = lat;
        this.lon = lon;
        this.sender = sender;
        this.raw = raw;
    }

    /**
     * 수집한 BLE 레코드 하나를 PDF 21쪽 요청 양식으로 변환한다.
     *
     * @param team   본인 팀 번호
     * @param sensor 센서 이름 (비워 두면 BLE 광고의 장치 이름을 사용)
     * @param sender 스마트폰 UUID (Settings.Secure.ANDROID_ID)
     * @return 센서 패킷 파싱에 실패한 레코드면 {@code null}
     */
    public static PostData from(BleRecord record, String team, String sensor, String sender) {
        return from(record, team, sensor, sender, TestMode.NORMAL);
    }

    /** 테스트 모드에 따라 일부러 잘못된 값을 넣어 서버 검증을 확인한다. */
    public static PostData from(BleRecord record, String team, String sensor, String sender,
                                TestMode mode) {
        SensorPacket packet = record.sensor;
        if (packet == null) {
            return null;
        }
        String sensorName = sensor == null || sensor.trim().isEmpty()
                ? record.name
                : sensor.trim();
        PostData body = new PostData();
        double temp = round2(packet.temperature);
        if (mode == TestMode.BAD_VALUE) {
            temp = round2(packet.temperature + 10.0f); // raw 와 어긋나게 만든다
        }
        String raw = record.rawHex;
        if (mode == TestMode.NO_RAW) {
            raw = null; // Gson 기본 설정에서 null 필드는 JSON 에 실리지 않는다
        } else if (mode == TestMode.BAD_TAG) {
            raw = flipLastByte(raw);
        }
        body.set_data(team, sensorName, record.address,
                temp, round2(packet.humidity),
                packet.aqi, packet.tvoc, packet.eco2, packet.timestamp,
                record.latitude, record.longitude, sender, raw);
        return body;
    }

    public String getTeam() {
        return team;
    }

    public String getSensor() {
        return sensor;
    }

    public String getMac() {
        return mac;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getRaw() {
        return raw;
    }

    /** PDF 20쪽 {@code data_show()} 처럼 로그에 한 줄로 남기기 위한 요약. */
    public String summary() {
        return String.format(Locale.US,
                "team=%s sensor=%s mac=%s temp=%.2f humidity=%.2f AQI=%d TVOC=%d eCO2=%d ts=%d lat=%.5f lon=%.5f",
                team, sensor, mac, temp, humidity, AQI, TVOC, eCO2, timestamp, lat, lon)
                + " raw=" + raw;
    }

    /** HMAC 태그의 마지막 바이트를 뒤집어 태그 검증을 일부러 실패시킨다. */
    private static String flipLastByte(String hex) {
        if (hex == null || hex.length() < 2) {
            return hex;
        }
        int last = Integer.parseInt(hex.substring(hex.length() - 2), 16) ^ 0xFF;
        return hex.substring(0, hex.length() - 2) + String.format(Locale.US, "%02X", last);
    }

    private static double round2(float value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
