package com.example.rpiblecollector;

import android.bluetooth.le.ScanResult;
import android.os.Build;

/**
 * {@link ScanResult} 의 무선 계층 메타데이터.
 *
 * <p>이 값들은 광고 페이로드(scan_record_hex)에 들어 있지 않으므로 따로 보존한다.
 * 특히 {@link #dataStatus} 는 수신한 광고가 잘렸는지를 알려주기 때문에,
 * 페이로드가 커져서 HMAC 태그가 유실되는 상황을 잡아낼 수 있는 유일한 단서다.
 *
 * <p>대부분 API 26 부터 제공되므로 그 이하에서는 {@link #UNKNOWN} 을 쓴다.
 */
public final class AdvertisingMeta {
    /** API 26 미만이라 값을 알 수 없을 때. */
    public static final int UNKNOWN_INT = -1;

    public static final AdvertisingMeta UNKNOWN = new AdvertisingMeta(
            UNKNOWN_INT, UNKNOWN_INT, UNKNOWN_INT, UNKNOWN_INT, UNKNOWN_INT, false);

    /** 광고에 실린 TX Power. {@code ScanResult.TX_POWER_NOT_PRESENT}(127) 이면 없음. */
    public final int txPower;
    public final int primaryPhy;
    public final int secondaryPhy;
    /** Advertising Set ID. {@code ScanResult.SID_NOT_PRESENT}(0xFF) 이면 없음. */
    public final int advertisingSid;
    /** {@code ScanResult.DATA_COMPLETE}(0) 또는 {@code DATA_TRUNCATED}(2). */
    public final int dataStatus;
    /** legacy(31바이트) 광고인지, BLE 5 확장 광고인지. */
    public final boolean legacy;

    public AdvertisingMeta(int txPower, int primaryPhy, int secondaryPhy,
                           int advertisingSid, int dataStatus, boolean legacy) {
        this.txPower = txPower;
        this.primaryPhy = primaryPhy;
        this.secondaryPhy = secondaryPhy;
        this.advertisingSid = advertisingSid;
        this.dataStatus = dataStatus;
        this.legacy = legacy;
    }

    public static AdvertisingMeta from(ScanResult result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return UNKNOWN;
        }
        return new AdvertisingMeta(
                result.getTxPower(),
                result.getPrimaryPhy(),
                result.getSecondaryPhy(),
                result.getAdvertisingSid(),
                result.getDataStatus(),
                result.isLegacy());
    }

    /** 광고 데이터가 잘려서 도착했는지. 페이로드가 커졌을 때 태그 유실을 잡는 지표. */
    public boolean isTruncated() {
        return dataStatus == 2; // ScanResult.DATA_TRUNCATED
    }

    public String dataStatusName() {
        switch (dataStatus) {
            case 0:
                return "complete";
            case 2:
                return "truncated";
            case UNKNOWN_INT:
                return "";
            default:
                return String.valueOf(dataStatus);
        }
    }

    public String phyName(int phy) {
        switch (phy) {
            case 0:
                return "unused";
            case 1:
                return "1M";
            case 2:
                return "2M";
            case 3:
                return "coded";
            case UNKNOWN_INT:
                return "";
            default:
                return String.valueOf(phy);
        }
    }

    /** CSV 에 넣을 값. 알 수 없거나 "없음"을 뜻하는 표준값이면 빈 칸으로 둔다. */
    public String txPowerText() {
        return txPower == UNKNOWN_INT || txPower == 127 ? "" : String.valueOf(txPower);
    }

    public String advertisingSidText() {
        return advertisingSid == UNKNOWN_INT || advertisingSid == 0xFF
                ? "" : String.valueOf(advertisingSid);
    }

    public String legacyText() {
        return dataStatus == UNKNOWN_INT ? "" : String.valueOf(legacy);
    }
}
