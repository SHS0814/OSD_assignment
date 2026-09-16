package com.example.rpiblecollector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * PDF 24쪽의 0x181A ServiceData 패킷 구조를 그대로 해석한다.
 * 총 13바이트, little-endian:
 * [0..1] int16 온도*100, [2..3] uint16 습도*100, [4] uint8 AQI,
 * [5..6] uint16 TVOC, [7..8] uint16 eCO2, [9..12] uint32 Unix timestamp.
 */
public final class SensorPacket {
    public final float temperature;
    public final float humidity;
    public final int aqi;
    public final int tvoc;
    public final int eco2;
    public final long timestamp;

    private SensorPacket(float temperature, float humidity, int aqi,
                         int tvoc, int eco2, long timestamp) {
        this.temperature = temperature;
        this.humidity = humidity;
        this.aqi = aqi;
        this.tvoc = tvoc;
        this.eco2 = eco2;
        this.timestamp = timestamp;
    }

    public static SensorPacket parse(byte[] data) {
        if (data == null || data.length < 13) {
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        float temperature = buf.getShort() / 100.0f;
        float humidity = (buf.getShort() & 0xFFFF) / 100.0f;
        int aqi = buf.get() & 0xFF;
        int tvoc = buf.getShort() & 0xFFFF;
        int eco2 = buf.getShort() & 0xFFFF;
        long timestamp = buf.getInt() & 0xFFFFFFFFL;
        return new SensorPacket(temperature, humidity, aqi, tvoc, eco2, timestamp);
    }

    @Override
    public String toString() {
        return String.format(Locale.getDefault(),
                "온도: %.2f°C | 습도: %.2f%% | AQI: %d | TVOC: %d ppb | eCO2: %d ppm",
                temperature, humidity, aqi, tvoc, eco2);
    }
}
