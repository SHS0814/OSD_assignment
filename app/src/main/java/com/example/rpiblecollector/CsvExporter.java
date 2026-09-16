package com.example.rpiblecollector;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** PDF 23쪽의 getExternalFilesDir + FileWriter 방식을 확장한 CSV 저장기. */
public final class CsvExporter {
    private static final String HEADER =
            "received_at,device_name,device_address,rssi,uuid,temperature_c," +
            "humidity_percent,aqi,tvoc_ppb,eco2_ppm,sensor_unix_timestamp,raw_hex\n";

    private CsvExporter() {
    }

    public static File save(Context context, List<BleRecord> records) throws IOException {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("저장 폴더를 만들 수 없습니다: " + dir);
        }

        String suffix = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "ble_data_" + suffix + ".csv");

        try (FileWriter fw = new FileWriter(file, false)) {
            fw.append(HEADER);
            for (BleRecord record : records) {
                SensorPacket s = record.sensor;
                fw.append(formatTime(record.receivedAtMillis)).append(',')
                        .append(csv(record.name)).append(',')
                        .append(csv(record.address)).append(',')
                        .append(String.valueOf(record.rssi)).append(',')
                        .append(csv(record.uuid)).append(',')
                        .append(formatFloat(s.temperature)).append(',')
                        .append(formatFloat(s.humidity)).append(',')
                        .append(String.valueOf(s.aqi)).append(',')
                        .append(String.valueOf(s.tvoc)).append(',')
                        .append(String.valueOf(s.eco2)).append(',')
                        .append(String.valueOf(s.timestamp)).append(',')
                        .append(csv(record.rawHex)).append('\n');
            }
            fw.flush();
        }
        return file;
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(millis));
    }

    private static String formatFloat(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String csv(String value) {
        String safe = value == null ? "unknown" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
