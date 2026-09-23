package com.example.rpiblecollector;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link CsvExporter} 가 저장한 CSV 를 다시 읽어 {@link BleRecord} 로 복원한다.
 *
 * <p>이미 수집해 둔 파일을 전송 페이지에서 서버로 보내기 위한 것이다.
 * 컬럼은 이름으로 찾으므로 컬럼 구성이 다른 예전 파일도 읽을 수 있다.
 */
public final class CsvImporter {
    private CsvImporter() {
    }

    /** 앱 전용 외부 저장소의 CSV 목록을 최신순으로 반환한다. */
    public static List<File> listCsvFiles(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".csv"));
        List<File> out = new ArrayList<>();
        if (files != null) {
            out.addAll(Arrays.asList(files));
            out.sort(Comparator.comparingLong(File::lastModified).reversed());
        }
        return out;
    }

    /**
     * CSV 한 개를 읽어 레코드로 복원한다.
     * service_data_hex 가 없거나 센서 패킷 파싱에 실패한 행은 건너뛴다.
     */
    public static List<BleRecord> load(File file) throws IOException {
        List<BleRecord> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return records;
            }
            Map<String, Integer> index = new HashMap<>();
            List<String> header = splitCsv(headerLine);
            for (int i = 0; i < header.size(); i++) {
                index.put(header.get(i).trim(), i);
            }
            if (!index.containsKey("service_data_hex")) {
                return records;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                List<String> cols = splitCsv(line);
                String hex = get(cols, index, "service_data_hex");
                SensorPacket sensor = SensorPacket.parse(fromHex(hex));
                if (sensor == null) {
                    continue;
                }
                records.add(new BleRecord(
                        parseTime(get(cols, index, "received_at")),
                        get(cols, index, "device_name"),
                        get(cols, index, "device_address"),
                        parseInt(get(cols, index, "rssi")),
                        get(cols, index, "uuid"),
                        sensor,
                        hex,
                        get(cols, index, "scan_record_hex"),
                        parseDouble(get(cols, index, "lat")),
                        parseDouble(get(cols, index, "lon")),
                        AdvertisingMeta.UNKNOWN));
            }
        }
        return records;
    }

    /** CsvExporter 는 값을 큰따옴표로 감싸고 내부 따옴표는 두 번 겹쳐 쓴다. */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String get(List<String> cols, Map<String, Integer> index, String name) {
        Integer i = index.get(name);
        if (i == null || i >= cols.size()) {
            return "";
        }
        return cols.get(i);
    }

    private static byte[] fromHex(String hex) {
        if (hex == null || hex.length() < 2 || hex.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static long parseTime(String value) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .parse(value).getTime();
        } catch (ParseException | NullPointerException e) {
            return 0L;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return 0.0;
        }
    }
}
