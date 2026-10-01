package com.example.rpiblecollector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 라즈베리 파이에 과거 데이터를 요청하는 BLE 광고.
 *
 * <p>Manufacturer Specific Data (회사 ID 0xFFFF, 블루투스 규격의 테스트용 예약 ID):
 * [0..1] pi_id(파이 MAC 마지막 2바이트), [2..5] want_ts, [6..9] phone_time (uint32 LE).
 * 파이는 want_ts 에 가장 가까운 샘플을 실시간 값과 번갈아 광고한다.
 * phone_time 을 계속 갱신해 광고 내용이 바뀌므로 파이의 BlueZ 가 요청이 살아 있음을 안다.
 *
 * <p>광고 내용을 끊지 않고 바꾸기 위해 AdvertisingSet(API 26+)을 쓴다.
 */
public final class RequestAdvertiser {
    public static final int COMPANY_ID = 0xFFFF;

    public interface Logger {
        void log(String message);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Logger logger;

    private AdvertisingSet advertisingSet;
    private boolean starting;
    private byte[] piId;
    private long wantTs = -1L;
    private boolean unsupportedLogged;

    public RequestAdvertiser(Context context, BluetoothAdapter adapter, Logger logger) {
        this.context = context;
        this.adapter = adapter;
        this.logger = logger;
    }

    public boolean isActive() {
        return advertisingSet != null || starting;
    }

    /** 요청 광고를 시작하거나 내용을 바꾼다. */
    public void request(byte[] piId, long wantTs) {
        this.piId = piId;
        this.wantTs = wantTs;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            logUnsupportedOnce("Android 8.0 미만이라 과거 데이터 요청 광고를 쓸 수 없습니다.");
            return;
        }
        if (advertisingSet != null) {
            refresh();
        } else if (!starting) {
            start();
        }
    }

    /** phone_time 을 갱신한다. 주기적으로 호출한다. */
    public void refresh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && advertisingSet != null) {
            setData();
        }
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        wantTs = -1L;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isActive()) {
            return;
        }
        BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser != null && hasPermission()) {
            advertiser.stopAdvertisingSet(callback);
        }
        advertisingSet = null;
        starting = false;
    }

    static byte[] buildPayload(byte[] piId, long wantTs, long phoneTime) {
        return ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                .put(piId, 0, 2)
                .putInt((int) wantTs)
                .putInt((int) phoneTime)
                .array();
    }

    /** "B8:27:EB:2A:11:0F" → {0x11, 0x0F} */
    static byte[] piIdFromAddress(String address) {
        String[] parts = address.split(":");
        if (parts.length != 6) {
            return null;
        }
        return new byte[]{
                (byte) Integer.parseInt(parts[4], 16),
                (byte) Integer.parseInt(parts[5], 16)
        };
    }

    @TargetApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    private void start() {
        if (!hasPermission()) {
            logUnsupportedOnce("광고 권한(BLUETOOTH_ADVERTISE)이 없어 과거 데이터를 요청할 수 없습니다.");
            return;
        }
        BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            logUnsupportedOnce("이 폰은 BLE 광고를 지원하지 않아 과거 데이터를 요청할 수 없습니다.");
            return;
        }
        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build();
        starting = true;
        advertiser.startAdvertisingSet(parameters, buildData(), null, null, null, callback);
    }

    @TargetApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    private void setData() {
        if (wantTs >= 0L && hasPermission()) {
            advertisingSet.setAdvertisingData(buildData());
        }
    }

    private AdvertiseData buildData() {
        long phoneTime = System.currentTimeMillis() / 1000L;
        return new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(COMPANY_ID, buildPayload(piId, wantTs, phoneTime))
                .build();
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void logUnsupportedOnce(String message) {
        if (!unsupportedLogged) {
            unsupportedLogged = true;
            logger.log(message);
        }
    }

    private final AdvertisingSetCallback callback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new AdvertisingSetCallback() {
                @Override
                public void onAdvertisingSetStarted(AdvertisingSet set, int txPower, int status) {
                    starting = false;
                    if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                        logger.log("과거 데이터 요청 광고 시작 실패 (status " + status + ")");
                        return;
                    }
                    if (wantTs < 0L) {
                        // 시작을 기다리는 사이에 stop() 이 불렸다.
                        stopPending();
                        return;
                    }
                    advertisingSet = set;
                    logger.log("과거 데이터 요청 광고 시작");
                }

                @Override
                public void onAdvertisingSetStopped(AdvertisingSet set) {
                    if (set == advertisingSet) {
                        advertisingSet = null;
                    }
                }
            }
            : null;

    @TargetApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    private void stopPending() {
        BluetoothLeAdvertiser advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser != null && hasPermission()) {
            advertiser.stopAdvertisingSet(callback);
        }
    }
}
