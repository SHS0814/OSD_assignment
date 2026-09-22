package com.example.rpiblecollector;

public final class BleRecord {
    /** 아직 서버로 보내지 않은 레코드의 전송 상태. */
    public static final String UPLOAD_NOT_SENT = "not_sent";

    public final long receivedAtMillis;
    public final String name;
    public final String address;
    public final int rssi;
    public final String uuid;
    public final SensorPacket sensor;
    public final String rawHex;
    public final String scanRecordHex;
    /** PDF 21쪽 요청 양식의 lat / lon. 위치를 얻지 못하면 0.0. */
    public final double latitude;
    public final double longitude;
    /** 광고 페이로드에 들어 있지 않은 무선 계층 정보(잘림 여부, PHY 등). */
    public final AdvertisingMeta meta;

    /** 서버 전송 결과. 메인 스레드에서만 갱신한다. */
    private String uploadResult = UPLOAD_NOT_SENT;

    public BleRecord(long receivedAtMillis, String name, String address, int rssi,
                     String uuid, SensorPacket sensor, String rawHex,
                     String scanRecordHex, double latitude, double longitude,
                     AdvertisingMeta meta) {
        this.receivedAtMillis = receivedAtMillis;
        this.name = name;
        this.address = address;
        this.rssi = rssi;
        this.uuid = uuid;
        this.sensor = sensor;
        this.rawHex = rawHex;
        this.scanRecordHex = scanRecordHex;
        this.latitude = latitude;
        this.longitude = longitude;
        this.meta = meta == null ? AdvertisingMeta.UNKNOWN : meta;
    }

    public String getUploadResult() {
        return uploadResult;
    }

    public void setUploadResult(String uploadResult) {
        this.uploadResult = uploadResult;
    }
}
