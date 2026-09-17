package com.example.rpiblecollector;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.SparseArray;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/** BLE advertising data와 Android가 제공하는 스캔 메타데이터를 디버기 용 문자로 풀어낸다. */
public final class PacketInspector {
    private PacketInspector() {
    }

    public static String inspect(ScanResult result, ScanRecord record, String name,
                                 String address, ParcelUuid targetUuid, String targetName,
                                 long receivedAtMillis) {
        StringBuilder out = new StringBuilder(2048);
        byte[] raw = record.getBytes();

        out.append("[RECEPTION]\n")
                .append("Received (local)  : ").append(formatLocalTime(receivedAtMillis)).append('\n')
                .append("Received epoch ms : ").append(receivedAtMillis).append('\n')
                .append("Device name       : ").append(name).append('\n')
                .append("Target name match : ").append(targetName.equals(name)).append('\n')
                .append("Device address    : ").append(address).append('\n')
                .append("RSSI              : ").append(result.getRssi()).append(" dBm\n")
                .append("Timestamp nanos   : ").append(result.getTimestampNanos()).append('\n');

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            out.append("Legacy advertising: ").append(result.isLegacy()).append('\n')
                    .append("Connectable      : ").append(result.isConnectable()).append('\n')
                    .append("Primary PHY      : ").append(phyName(result.getPrimaryPhy())).append('\n')
                    .append("Secondary PHY    : ").append(phyName(result.getSecondaryPhy())).append('\n')
                    .append("Advertising SID  : ").append(optionalValue(result.getAdvertisingSid(), 0xFF)).append('\n')
                    .append("Periodic interval: ").append(periodicInterval(result.getPeriodicAdvertisingInterval())).append('\n')
                    .append("Data status      : ").append(dataStatusName(result.getDataStatus())).append('\n')
                    .append("Event TX power   : ").append(txPower(result.getTxPower())).append('\n');
        }

        out.append('\n').append("[ANDROID PARSED FIELDS]\n")
                .append("Advertise flags   : ").append(flags(record.getAdvertiseFlags())).append('\n')
                .append("Local name        : ").append(valueOrAbsent(record.getDeviceName())).append('\n')
                .append("TX power          : ").append(txPower(record.getTxPowerLevel())).append('\n');

        appendServiceUuids(out, record.getServiceUuids());
        appendServiceData(out, record.getServiceData(), targetUuid);
        appendManufacturerData(out, record.getManufacturerSpecificData());
        appendTargetSensor(out, record.getServiceData(targetUuid));

        out.append('\n').append("[AD STRUCTURES]\n");
        appendAdStructures(out, raw);

        out.append('\n').append("[RAW ADVERTISEMENT]\n")
                .append("Length: ").append(raw == null ? 0 : raw.length).append(" bytes\n")
                .append("HEX   : ").append(hex(raw)).append('\n')
                .append("DEC   : ").append(decimalBytes(raw)).append('\n')
                .append("ASCII : ").append(ascii(raw)).append('\n')
                .append("Dump:\n").append(hexDump(raw));
        return out.toString();
    }

    public static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "(none)";
        }
        StringBuilder out = new StringBuilder(bytes.length * 3 - 1);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return out.toString();
    }

    private static void appendServiceUuids(StringBuilder out, List<ParcelUuid> uuids) {
        out.append("Service UUIDs      : ");
        if (uuids == null || uuids.isEmpty()) {
            out.append("(none)\n");
            return;
        }
        out.append('\n');
        for (ParcelUuid uuid : uuids) {
            out.append("  - ").append(uuid).append('\n');
        }
    }

    private static void appendServiceData(StringBuilder out, Map<ParcelUuid, byte[]> values,
                                          ParcelUuid targetUuid) {
        out.append("Service data       : ");
        if (values == null || values.isEmpty()) {
            out.append("(none)\n");
            return;
        }
        out.append('\n');
        for (Map.Entry<ParcelUuid, byte[]> entry : values.entrySet()) {
            byte[] data = entry.getValue();
            out.append("  - UUID: ").append(entry.getKey());
            if (entry.getKey().equals(targetUuid)) {
                out.append(" (target)");
            }
            out.append("\n    Length: ").append(data == null ? 0 : data.length)
                    .append(" bytes\n    Value : ").append(hex(data)).append('\n');
        }
    }

    private static void appendManufacturerData(StringBuilder out, SparseArray<byte[]> values) {
        out.append("Manufacturer data  : ");
        if (values == null || values.size() == 0) {
            out.append("(none)\n");
            return;
        }
        out.append('\n');
        for (int i = 0; i < values.size(); i++) {
            int companyId = values.keyAt(i);
            byte[] data = values.valueAt(i);
            out.append(String.format(Locale.US,
                    "  - Company ID: 0x%04X (%d)\n    Length    : %d bytes\n    Value     : %s\n",
                    companyId, companyId, data == null ? 0 : data.length, hex(data)));
        }
    }

    private static void appendTargetSensor(StringBuilder out, byte[] data) {
        out.append('\n').append("[TARGET 0x181A SENSOR PAYLOAD]\n")
                .append("Payload length: ").append(data == null ? 0 : data.length).append(" bytes\n")
                .append("Payload HEX   : ").append(hex(data)).append('\n');
        if (data == null || data.length < 13) {
            out.append("Parse result  : invalid; 13 bytes required\n");
            return;
        }

        int temperatureRaw = (short) u16(data, 0);
        int humidityRaw = u16(data, 2);
        int aqi = data[4] & 0xFF;
        int tvoc = u16(data, 5);
        int eco2 = u16(data, 7);
        long timestamp = (data[9] & 0xFFL)
                | ((data[10] & 0xFFL) << 8)
                | ((data[11] & 0xFFL) << 16)
                | ((data[12] & 0xFFL) << 24);

        out.append(String.format(Locale.US,
                "[00..01] Temperature : raw=%d (0x%04X), %.2f °C\n",
                temperatureRaw, temperatureRaw & 0xFFFF, temperatureRaw / 100.0))
                .append(String.format(Locale.US,
                        "[02..03] Humidity    : raw=%d (0x%04X), %.2f %%\n",
                        humidityRaw, humidityRaw, humidityRaw / 100.0))
                .append(String.format(Locale.US,
                        "[04]     AQI         : %d (0x%02X)\n", aqi, aqi))
                .append(String.format(Locale.US,
                        "[05..06] TVOC        : %d ppb (0x%04X)\n", tvoc, tvoc))
                .append(String.format(Locale.US,
                        "[07..08] eCO2        : %d ppm (0x%04X)\n", eco2, eco2))
                .append(String.format(Locale.US,
                        "[09..12] Unix time   : %d (0x%08X)\n", timestamp, timestamp))
                .append("           UTC         : ").append(formatUtcTime(timestamp * 1000L)).append('\n')
                .append("           Local       : ").append(formatLocalTime(timestamp * 1000L)).append('\n');

        if (data.length > 13) {
            out.append("[13..]   Extra bytes : ")
                    .append(hexRange(data, 13, data.length - 13)).append('\n');
        }
    }

    private static void appendAdStructures(StringBuilder out, byte[] raw) {
        if (raw == null || raw.length == 0) {
            out.append("(none)\n");
            return;
        }

        int offset = 0;
        int number = 1;
        while (offset < raw.length) {
            int length = raw[offset] & 0xFF;
            if (length == 0) {
                out.append(String.format(Locale.US,
                        "#%02d @0x%02X  padding/end marker\n", number, offset));
                break;
            }
            if (offset + 1 >= raw.length) {
                out.append(String.format(Locale.US,
                        "#%02d @0x%02X  malformed: missing AD type\n", number, offset));
                break;
            }

            int declaredEnd = offset + 1 + length;
            int end = Math.min(declaredEnd, raw.length);
            int type = raw[offset + 1] & 0xFF;
            int dataOffset = offset + 2;
            int dataLength = Math.max(0, end - dataOffset);

            out.append(String.format(Locale.US,
                    "#%02d @0x%02X  len=%d  type=0x%02X (%s)%s\n",
                    number, offset, length, type, adTypeName(type),
                    declaredEnd > raw.length ? "  [TRUNCATED]" : ""));
            out.append("    HEX    : ").append(hexRange(raw, dataOffset, dataLength)).append('\n');
            String decoded = decodeAdValue(type, raw, dataOffset, dataLength);
            if (!decoded.isEmpty()) {
                out.append("    Decoded: ").append(decoded).append('\n');
            }

            if (declaredEnd > raw.length) {
                out.append("    Error  : declared structure exceeds packet by ")
                        .append(declaredEnd - raw.length).append(" bytes\n");
                break;
            }
            offset = declaredEnd;
            number++;
        }
    }

    private static String decodeAdValue(int type, byte[] raw, int offset, int length) {
        switch (type) {
            case 0x01:
                return length < 1 ? "missing flags byte" : flags(raw[offset] & 0xFF);
            case 0x02:
            case 0x03:
            case 0x14:
                return uuidList(raw, offset, length, 2);
            case 0x04:
            case 0x05:
            case 0x1F:
                return uuidList(raw, offset, length, 4);
            case 0x06:
            case 0x07:
            case 0x15:
                return uuidList(raw, offset, length, 16);
            case 0x08:
            case 0x09:
            case 0x30:
                return '"' + new String(raw, offset, length, StandardCharsets.UTF_8) + '"';
            case 0x0A:
                return length < 1 ? "missing TX power byte" : ((byte) raw[offset]) + " dBm";
            case 0x12:
                if (length < 4) return "requires 4 bytes";
                return String.format(Locale.US, "min=%.2f ms, max=%.2f ms",
                        u16(raw, offset) * 1.25, u16(raw, offset + 2) * 1.25);
            case 0x16:
                return serviceData(raw, offset, length, 2);
            case 0x20:
                return serviceData(raw, offset, length, 4);
            case 0x21:
                return serviceData(raw, offset, length, 16);
            case 0x19:
                return length < 2 ? "requires 2 bytes" :
                        String.format(Locale.US, "appearance=0x%04X (%d)",
                                u16(raw, offset), u16(raw, offset));
            case 0x1A:
                return length < 2 ? "requires 2 bytes" :
                        String.format(Locale.US, "%.3f ms (%d units)",
                                u16(raw, offset) * 0.625, u16(raw, offset));
            case 0x1C:
                return length < 1 ? "missing role byte" : leRole(raw[offset] & 0xFF);
            case 0x2F:
                if (length < 3) return "requires 3 bytes";
                int interval = (raw[offset] & 0xFF)
                        | ((raw[offset + 1] & 0xFF) << 8)
                        | ((raw[offset + 2] & 0xFF) << 16);
                return String.format(Locale.US, "%.3f ms (%d units)", interval * 0.625, interval);
            case 0xFF:
                if (length < 2) return "missing company identifier";
                int companyId = u16(raw, offset);
                return String.format(Locale.US, "company=0x%04X (%d), payload=%s",
                        companyId, companyId, hexRange(raw, offset + 2, length - 2));
            default:
                return length == 0 ? "(empty)" : "unsigned bytes=" + unsignedBytes(raw, offset, length);
        }
    }

    private static String serviceData(byte[] raw, int offset, int length, int uuidSize) {
        if (length < uuidSize) {
            return "missing " + (uuidSize * 8) + "-bit service UUID";
        }
        return "UUID=" + uuidAt(raw, offset, uuidSize)
                + ", payload=" + hexRange(raw, offset + uuidSize, length - uuidSize);
    }

    private static String uuidList(byte[] raw, int offset, int length, int uuidSize) {
        if (length == 0) {
            return "(empty UUID list)";
        }
        StringBuilder out = new StringBuilder();
        int count = length / uuidSize;
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(", ");
            out.append(uuidAt(raw, offset + i * uuidSize, uuidSize));
        }
        if (length % uuidSize != 0) {
            out.append(" [+").append(length % uuidSize).append(" malformed byte(s)]");
        }
        return out.toString();
    }

    private static String uuidAt(byte[] raw, int offset, int size) {
        if (size == 2) {
            return String.format(Locale.US, "0x%04X", u16(raw, offset));
        }
        if (size == 4) {
            long value = (raw[offset] & 0xFFL)
                    | ((raw[offset + 1] & 0xFFL) << 8)
                    | ((raw[offset + 2] & 0xFFL) << 16)
                    | ((raw[offset + 3] & 0xFFL) << 24);
            return String.format(Locale.US, "0x%08X", value);
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw, offset, 16).order(ByteOrder.LITTLE_ENDIAN);
        long leastSignificant = buffer.getLong();
        long mostSignificant = buffer.getLong();
        return new UUID(mostSignificant, leastSignificant).toString().toUpperCase(Locale.US);
    }

    private static int u16(byte[] raw, int offset) {
        return (raw[offset] & 0xFF) | ((raw[offset + 1] & 0xFF) << 8);
    }

    private static String flags(int value) {
        if (value < 0) {
            return "(not present)";
        }
        StringBuilder out = new StringBuilder(String.format(Locale.US, "0x%02X", value));
        appendBit(out, value, 0x01, "LE Limited Discoverable");
        appendBit(out, value, 0x02, "LE General Discoverable");
        appendBit(out, value, 0x04, "BR/EDR Not Supported");
        appendBit(out, value, 0x08, "Simultaneous LE+BR/EDR (Controller)");
        appendBit(out, value, 0x10, "Simultaneous LE+BR/EDR (Host)");
        int unknown = value & ~0x1F;
        if (unknown != 0) {
            out.append(String.format(Locale.US, ", unknown-bits=0x%02X", unknown));
        }
        return out.toString();
    }

    private static void appendBit(StringBuilder out, int value, int mask, String label) {
        if ((value & mask) != 0) {
            out.append(out.indexOf("[") < 0 ? " [" : ", ").append(label);
        }
        if (mask == 0x10 && out.indexOf("[") >= 0) {
            out.append(']');
        }
    }

    private static String phyName(int phy) {
        switch (phy) {
            case 1: return "LE 1M (1)";
            case 2: return "LE 2M (2)";
            case 3: return "LE Coded (3)";
            case 0: return "unused (0)";
            default: return "unknown (" + phy + ")";
        }
    }

    private static String dataStatusName(int status) {
        switch (status) {
            case ScanResult.DATA_COMPLETE: return "complete (" + status + ")";
            case ScanResult.DATA_TRUNCATED: return "truncated (" + status + ")";
            default: return "unknown (" + status + ")";
        }
    }

    private static String periodicInterval(int interval) {
        if (interval == 0) {
            return "(not present)";
        }
        return String.format(Locale.US, "%.2f ms (%d units)", interval * 1.25, interval);
    }

    private static String txPower(int value) {
        return value == Integer.MIN_VALUE || value == 127
                ? "(not present)" : value + " dBm";
    }

    private static String formatLocalTime(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
                .format(new Date(millis));
    }

    private static String formatUtcTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static String optionalValue(int value, int absentValue) {
        return value == absentValue ? "(not present)" : String.valueOf(value);
    }

    private static String valueOrAbsent(String value) {
        return value == null || value.isEmpty() ? "(not present)" : value;
    }

    private static String decimalBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "(none)";
        return unsignedBytes(bytes, 0, bytes.length);
    }

    private static String unsignedBytes(byte[] bytes, int offset, int length) {
        StringBuilder out = new StringBuilder(length * 4);
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(' ');
            out.append(bytes[offset + i] & 0xFF);
        }
        return out.toString();
    }

    private static String ascii(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "(none)";
        StringBuilder out = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int c = value & 0xFF;
            out.append(c >= 0x20 && c <= 0x7E ? (char) c : '.');
        }
        return out.toString();
    }

    private static String hexDump(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "(none)\n";
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < bytes.length; offset += 16) {
            int length = Math.min(16, bytes.length - offset);
            out.append(String.format(Locale.US, "%04X  ", offset));
            for (int i = 0; i < 16; i++) {
                if (i < length) {
                    out.append(String.format(Locale.US, "%02X ", bytes[offset + i] & 0xFF));
                } else {
                    out.append("   ");
                }
                if (i == 7) out.append(' ');
            }
            out.append(" |");
            for (int i = 0; i < length; i++) {
                int c = bytes[offset + i] & 0xFF;
                out.append(c >= 0x20 && c <= 0x7E ? (char) c : '.');
            }
            out.append("|\n");
        }
        return out.toString();
    }

    private static String hexRange(byte[] bytes, int offset, int length) {
        if (length <= 0) return "(empty)";
        StringBuilder out = new StringBuilder(length * 3 - 1);
        for (int i = 0; i < length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", bytes[offset + i] & 0xFF));
        }
        return out.toString();
    }

    private static String leRole(int role) {
        switch (role) {
            case 0x00: return "Only Peripheral";
            case 0x01: return "Only Central";
            case 0x02: return "Peripheral preferred, Central supported";
            case 0x03: return "Central preferred, Peripheral supported";
            default: return String.format(Locale.US, "unknown (0x%02X)", role);
        }
    }

    private static String adTypeName(int type) {
        switch (type) {
            case 0x01: return "Flags";
            case 0x02: return "Incomplete 16-bit Service UUIDs";
            case 0x03: return "Complete 16-bit Service UUIDs";
            case 0x04: return "Incomplete 32-bit Service UUIDs";
            case 0x05: return "Complete 32-bit Service UUIDs";
            case 0x06: return "Incomplete 128-bit Service UUIDs";
            case 0x07: return "Complete 128-bit Service UUIDs";
            case 0x08: return "Shortened Local Name";
            case 0x09: return "Complete Local Name";
            case 0x0A: return "TX Power Level";
            case 0x0D: return "Class of Device";
            case 0x12: return "Peripheral Connection Interval Range";
            case 0x14: return "16-bit Service Solicitation UUIDs";
            case 0x15: return "128-bit Service Solicitation UUIDs";
            case 0x16: return "16-bit Service Data";
            case 0x17: return "Public Target Address";
            case 0x18: return "Random Target Address";
            case 0x19: return "Appearance";
            case 0x1A: return "Advertising Interval";
            case 0x1B: return "LE Bluetooth Device Address";
            case 0x1C: return "LE Role";
            case 0x1F: return "32-bit Service Solicitation UUIDs";
            case 0x20: return "32-bit Service Data";
            case 0x21: return "128-bit Service Data";
            case 0x24: return "URI";
            case 0x25: return "Indoor Positioning";
            case 0x26: return "Transport Discovery Data";
            case 0x27: return "LE Supported Features";
            case 0x28: return "Channel Map Update Indication";
            case 0x29: return "PB-ADV";
            case 0x2A: return "Mesh Message";
            case 0x2B: return "Mesh Beacon";
            case 0x2C: return "BIGInfo";
            case 0x2D: return "Broadcast Code";
            case 0x2E: return "Resolvable Set Identifier";
            case 0x2F: return "Advertising Interval - long";
            case 0x30: return "Broadcast Name";
            case 0x31: return "Encrypted Advertising Data";
            case 0x32: return "Periodic Advertising Response Timing";
            case 0x3D: return "3D Information Data";
            case 0xFF: return "Manufacturer Specific Data";
            default: return "Unknown/assigned AD type";
        }
    }
}
