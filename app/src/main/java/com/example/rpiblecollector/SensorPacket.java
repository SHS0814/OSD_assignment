package com.example.rpiblecollector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/**
 * 0x181A ServiceData 패킷 해석기.
 *
 * <p>앞의 13바이트는 3주차 PDF 24쪽의 센서 페이로드이고, little-endian 이다.
 * [0..1] int16 온도*100, [2..3] uint16 습도*100, [4] uint8 AQI,
 * [5..6] uint16 TVOC, [7..8] uint16 eCO2, [9..12] uint32 Unix timestamp.
 *
 * <p>13바이트 뒤에 오는 나머지 바이트는 라즈베리 파이가 붙인 HMAC 태그로 보고
 * 그대로 보존한다(PDF에는 없는, 이후 펌웨어에서 추가된 필드).
 * 태그 길이를 상수로 고정하지 않고 실제 수신한 길이를 그대로 쓰므로
 * 태그가 없는 13바이트 패킷과 태그가 붙은 패킷을 모두 처리한다.
 */
public final class SensorPacket {
    /** 센서 페이로드 길이. 이 뒤부터가 HMAC 태그이다. */
    public static final int SENSOR_PAYLOAD_LENGTH = 13;

    private static final byte[] NO_TAG = new byte[0];

    public final float temperature;
    public final float humidity;
    public final int aqi;
    public final int tvoc;
    public final int eco2;
    public final long timestamp;
    /** 13바이트 이후의 원본 바이트(HMAC 태그). 태그가 없으면 길이 0. */
    private final byte[] hmacTag;

    private SensorPacket(float temperature, float humidity, int aqi,
                         int tvoc, int eco2, long timestamp, byte[] hmacTag) {
        this.temperature = temperature;
        this.humidity = humidity;
        this.aqi = aqi;
        this.tvoc = tvoc;
        this.eco2 = eco2;
        this.timestamp = timestamp;
        this.hmacTag = hmacTag;
    }

    public static SensorPacket parse(byte[] data) {
        if (data == null || data.length < SENSOR_PAYLOAD_LENGTH) {
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float temperature = buf.getShort() / 100.0f;
        float humidity = (buf.getShort() & 0xFFFF) / 100.0f;
        int aqi = buf.get() & 0xFF;
        int tvoc = buf.getShort() & 0xFFFF;
        int eco2 = buf.getShort() & 0xFFFF;
        long timestamp = buf.getInt() & 0xFFFFFFFFL;

        byte[] tag = data.length > SENSOR_PAYLOAD_LENGTH
                ? Arrays.copyOfRange(data, SENSOR_PAYLOAD_LENGTH, data.length)
                : NO_TAG;
        return new SensorPacket(temperature, humidity, aqi, tvoc, eco2, timestamp, tag);
    }

    public boolean hasHmacTag() {
        return hmacTag.length > 0;
    }

    public int hmacTagLength() {
        return hmacTag.length;
    }

    /** HMAC 태그 원본 바이트의 복사본. 검증기에 넘길 때 사용한다. */
    public byte[] hmacTag() {
        return hmacTag.length == 0 ? NO_TAG : hmacTag.clone();
    }

    public String hmacTagHex() {
        return Hex.encode(hmacTag);
    }

    @Override
    public String toString() {
        return String.format(Locale.getDefault(),
                "온도: %.2f°C | 습도: %.2f%% | AQI: %d | TVOC: %d ppb | eCO2: %d ppm",
                temperature, humidity, aqi, tvoc, eco2);
    }
}
