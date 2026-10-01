package com.example.rpiblecollector;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * 장부와 CSV 사이의 변환.
 *
 * <ul>
 *   <li>읽기: 수집 CSV({@link CsvExporter} 의 ble_data_*.csv)와 장부 CSV(ledger.csv) 둘 다
 *       읽어 장부에 넣을 {@link Entry} 로 만든다.</li>
 *   <li>쓰기: 장부 전체를 timestamp 순, 중복 없이 ledger.csv 형식으로 쓴다.
 *       이 파일이 곧 "서버에 들어가 있어야 할 데이터" 목록이고, 앱을 다시 설치했을 때
 *       가져오면 전송 상태까지 복구된다.</li>
 * </ul>
 */
public final class LedgerCsv {
    public static final String LEDGER_FILE_NAME = "ledger.csv";

    static final String[] LEDGER_HEADER = {
            "ts", "time_kst", "status", "temp", "humidity", "aqi", "tvoc", "eco2",
            "mac", "name", "lat", "lon", "received_at", "source",
            "uploaded_at", "server_status", "attempts", "last_error"
    };

    /** 장부 한 행에 해당하는 값. */
    public static final class Entry {
        public long timestamp;
        public double temp;
        public double humidity;
        public int aqi;
        public int tvoc;
        public int eco2;
        public String mac;
        public String name;
        public double lat;
        public double lon;
        public long receivedAt;
        public String source;
        public boolean uploaded;
        /** 파이가 "데이터 없음" 으로 답한 시각. 장부에만 남기고 서버로 보내지 않는다. */
        public boolean noData;
        public long uploadedAt;
        public String serverStatus;
        /** 쓰기 전용: uploaded / pending / failed */
        public String status;
        public int attempts;
        public String lastError;
    }

    /** CSV 를 읽은 결과. */
    public static final class ReadResult {
        public final List<Entry> entries = new ArrayList<>();
        public boolean ledgerFormat;
        public int totalRows;
        public int otherDevice;
        public int invalid;
        public int duplicate;
        public int noData;

        public String summary() {
            return String.format(Locale.getDefault(),
                    "%s · %,d행 중 장부 대상 %,d건(측정값 %,d · 데이터 없음 %,d) · "
                            + "다른 장치 %,d · 파일 내 중복 %,d · 읽을 수 없음 %,d",
                    ledgerFormat ? "장부 CSV" : "수집 CSV",
                    totalRows, entries.size(), entries.size() - noData, noData,
                    otherDevice, duplicate, invalid);
        }
    }

    private LedgerCsv() {
    }

    /**
     * @param config 수집 CSV 의 대상 장치 필터와 위치 기본값. 장부 CSV 에는 적용하지 않는다.
     */
    public static ReadResult read(Reader reader, UploadConfig config) throws IOException {
        List<List<String>> lines = parse(reader.markSupported() ? reader : new BufferedReader(reader));
        if (lines.isEmpty()) {
            throw new IOException("빈 파일입니다.");
        }
        List<String> header = lines.get(0);
        if (!header.isEmpty() && header.get(0).startsWith("﻿")) {
            header.set(0, header.get(0).substring(1));
        }
        ReadResult result = new ReadResult();
        if (header.contains("ts") && header.contains("status")) {
            result.ledgerFormat = true;
            readLedger(header, lines.subList(1, lines.size()), result);
        } else if (header.contains("sensor_unix_timestamp")) {
            readCollection(header, lines.subList(1, lines.size()), config, result);
        } else {
            throw new IOException("이 앱이 저장한 수집 CSV 나 장부 CSV 가 아닙니다.");
        }
        return result;
    }

    public static void write(Writer writer, List<Entry> entries) throws IOException {
        writeLine(writer, LEDGER_HEADER);
        for (Entry e : entries) {
            writeLine(writer, new String[]{
                    String.valueOf(e.timestamp),
                    formatKst(e.timestamp * 1000L),
                    e.status,
                    String.format(Locale.US, "%.2f", e.temp),
                    String.format(Locale.US, "%.2f", e.humidity),
                    String.valueOf(e.aqi),
                    String.valueOf(e.tvoc),
                    String.valueOf(e.eco2),
                    nullToEmpty(e.mac),
                    nullToEmpty(e.name),
                    String.format(Locale.US, "%.6f", e.lat),
                    String.format(Locale.US, "%.6f", e.lon),
                    e.receivedAt > 0L ? formatKst(e.receivedAt) : "",
                    nullToEmpty(e.source),
                    e.uploadedAt > 0L ? formatKst(e.uploadedAt) : "",
                    nullToEmpty(e.serverStatus),
                    String.valueOf(e.attempts),
                    nullToEmpty(e.lastError)
            });
        }
        writer.flush();
    }

    private static void readLedger(List<String> header, List<List<String>> rows,
                                   ReadResult result) {
        Set<Long> seen = new HashSet<>();
        for (List<String> row : rows) {
            if (isBlank(row)) {
                continue;
            }
            result.totalRows++;
            Entry e = new Entry();
            try {
                e.timestamp = Long.parseLong(get(header, row, "ts"));
                e.temp = Double.parseDouble(get(header, row, "temp"));
                e.humidity = Double.parseDouble(get(header, row, "humidity"));
                e.aqi = Integer.parseInt(get(header, row, "aqi"));
                e.tvoc = Integer.parseInt(get(header, row, "tvoc"));
                e.eco2 = Integer.parseInt(get(header, row, "eco2"));
            } catch (NumberFormatException ex) {
                result.invalid++;
                continue;
            }
            e.mac = get(header, row, "mac");
            e.name = get(header, row, "name");
            e.lat = parseDoubleOr(get(header, row, "lat"), 0.0);
            e.lon = parseDoubleOr(get(header, row, "lon"), 0.0);
            e.receivedAt = parseKstOr(get(header, row, "received_at"), e.timestamp * 1000L);
            String source = get(header, row, "source");
            e.source = source.isEmpty() ? BacklogDb.SOURCE_CSV : source;
            String status = get(header, row, "status");
            e.noData = BacklogDb.STATUS_NO_DATA.equals(status);
            e.uploaded = !e.noData && BacklogDb.STATUS_UPLOADED.equals(status);
            e.uploadedAt = parseKstOr(get(header, row, "uploaded_at"), 0L);
            String serverStatus = get(header, row, "server_status");
            e.serverStatus = serverStatus.isEmpty() ? null : serverStatus;
            if (!seen.add(e.timestamp)) {
                result.duplicate++;
                continue;
            }
            if (e.noData) {
                result.noData++;
            }
            result.entries.add(e);
        }
    }

    /**
     * 수집 CSV: 대상 장치의 유효한 센서 행만 고르고, 같은 timestamp 는 처음 것만 쓴다.
     * upload_result 가 success 로 시작하면 이미 서버에 올라간 행으로 본다.
     * no_data_marker 행은 "데이터 없음" 으로 가져온다(서버로는 보내지 않는다).
     */
    private static void readCollection(List<String> header, List<List<String>> rows,
                                       UploadConfig config, ReadResult result) {
        Set<Long> seen = new HashSet<>();
        for (List<String> row : rows) {
            if (isBlank(row)) {
                continue;
            }
            result.totalRows++;
            String name = get(header, row, "device_name");
            if (!config.deviceName.isEmpty() && !config.deviceName.equalsIgnoreCase(name)) {
                result.otherDevice++;
                continue;
            }
            String uploadResult = get(header, row, "upload_result");
            Entry e = new Entry();
            e.noData = "no_data_marker".equals(uploadResult);
            try {
                e.timestamp = Long.parseLong(get(header, row, "sensor_unix_timestamp"));
                e.temp = Double.parseDouble(get(header, row, "temperature_c"));
                e.humidity = Double.parseDouble(get(header, row, "humidity_percent"));
                e.aqi = Integer.parseInt(get(header, row, "aqi"));
                e.tvoc = Integer.parseInt(get(header, row, "tvoc_ppb"));
                e.eco2 = Integer.parseInt(get(header, row, "eco2_ppm"));
            } catch (NumberFormatException ex) {
                result.invalid++;
                continue;
            }
            e.mac = get(header, row, "device_address");
            e.name = name;
            e.lat = parseDoubleOr(get(header, row, "lat"), 0.0);
            e.lon = parseDoubleOr(get(header, row, "lon"), 0.0);
            if (e.lat == 0.0 && e.lon == 0.0) {
                // 수집 당시 위치를 얻지 못했으면 화면에 입력한 설치 위치를 쓴다.
                e.lat = config.latitude;
                e.lon = config.longitude;
            }
            e.receivedAt = parseLocalOr(get(header, row, "received_at"), e.timestamp * 1000L);
            e.source = BacklogDb.SOURCE_CSV;
            if (!e.noData && uploadResult.startsWith("success")) {
                e.uploaded = true;
                int open = uploadResult.indexOf('(');
                int close = uploadResult.indexOf(')');
                e.serverStatus = open >= 0 && close > open
                        ? uploadResult.substring(open + 1, close) : null;
            }
            if (!seen.add(e.timestamp)) {
                result.duplicate++;
                continue;
            }
            if (e.noData) {
                result.noData++;
            }
            result.entries.add(e);
        }
    }

    private static String get(List<String> header, List<String> row, String column) {
        int index = header.indexOf(column);
        return index >= 0 && index < row.size() ? row.get(index).trim() : "";
    }

    private static boolean isBlank(List<String> row) {
        return row.size() == 1 && row.get(0).trim().isEmpty();
    }

    private static double parseDoubleOr(String raw, double fallback) {
        try {
            return raw.isEmpty() ? fallback : Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static SimpleDateFormat kstFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        return f;
    }

    static String formatKst(long millis) {
        return kstFormat().format(new Date(millis));
    }

    private static long parseKstOr(String raw, long fallback) {
        if (raw.isEmpty()) {
            return fallback;
        }
        try {
            return kstFormat().parse(raw).getTime();
        } catch (ParseException e) {
            return fallback;
        }
    }

    /** 수집 CSV 의 received_at 은 폰의 현지 시각으로 저장돼 있다. */
    private static long parseLocalOr(String raw, long fallback) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(raw).getTime();
        } catch (ParseException e) {
            return fallback;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** RFC 4180 형식(큰따옴표 이스케이프, 따옴표 안의 쉼표·줄바꿈)을 읽는다. */
    private static List<List<String>> parse(Reader reader) throws IOException {
        List<List<String>> lines = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean any = false;
        int c;
        while ((c = reader.read()) != -1) {
            any = true;
            char ch = (char) c;
            if (inQuotes) {
                if (ch == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        inQuotes = false;
                        if (next != -1) {
                            reader.reset();
                        }
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r') {
                    reader.mark(1);
                    if (reader.read() != '\n') {
                        reader.reset();
                    }
                }
                fields.add(field.toString());
                lines.add(fields);
                fields = new ArrayList<>();
                field.setLength(0);
                any = false;
            } else {
                field.append(ch);
            }
        }
        if (any) {
            fields.add(field.toString());
            lines.add(fields);
        }
        return lines;
    }

    private static void writeLine(Writer writer, String[] values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                writer.write(',');
            }
            String value = values[i] == null ? "" : values[i];
            if (value.contains(",") || value.contains("\"")
                    || value.contains("\n") || value.contains("\r")) {
                writer.write('"');
                writer.write(value.replace("\"", "\"\""));
                writer.write('"');
            } else {
                writer.write(value);
            }
        }
        writer.write('\n');
    }
}
