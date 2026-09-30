package com.example.rpiblecollector;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

/**
 * 요청 양식의 lat / lon 에 쓸 폰의 현재 위치 (5주차 PDF 48쪽).
 *
 * <p>GPS 와 네트워크 위치를 둘 다 받아 가장 최근 값을 쓴다. BLE 는 수십 m 안에서만
 * 수신되므로 패킷을 받은 순간의 폰 위치가 곧 파이 근처의 위치가 된다.
 * 위치를 아직 못 얻었거나 오래됐으면 null 을 돌려주고, 호출하는 쪽이 화면에서
 * 입력한 좌표로 대신한다.
 */
public final class LocationTracker implements LocationListener {
    private static final long MIN_TIME_MILLIS = 5_000L;
    /** 이보다 오래된 위치는 쓰지 않는다. */
    private static final long MAX_AGE_MILLIS = 10L * 60L * 1000L;

    private final Context context;
    private final LocationManager manager;
    private volatile Location latest;
    private boolean running;

    public LocationTracker(Context context) {
        this.context = context;
        this.manager = context.getSystemService(LocationManager.class);
    }

    public boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** @return 위치 업데이트를 시작했으면 true */
    @SuppressLint("MissingPermission")
    public boolean start() {
        if (running || manager == null || !hasPermission()) {
            return running;
        }
        boolean requested = false;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!manager.getAllProviders().contains(provider)) {
                continue;
            }
            offer(manager.getLastKnownLocation(provider));
            manager.requestLocationUpdates(provider, MIN_TIME_MILLIS, 0f, this,
                    Looper.getMainLooper());
            requested = true;
        }
        running = requested;
        return running;
    }

    public void stop() {
        if (running && manager != null) {
            manager.removeUpdates(this);
        }
        running = false;
    }

    /** 최근 10분 안의 위치. 없으면 null. 어느 스레드에서 불러도 된다. */
    public Location current() {
        Location location = latest;
        if (location == null
                || System.currentTimeMillis() - location.getTime() > MAX_AGE_MILLIS) {
            return null;
        }
        return location;
    }

    private void offer(Location location) {
        if (location == null) {
            return;
        }
        Location previous = latest;
        // 더 새로운 위치를 쓰되, 같은 무렵(30초 안)이면 더 정확한 쪽을 남긴다.
        if (previous == null
                || location.getTime() - previous.getTime() > 30_000L
                || location.getAccuracy() <= previous.getAccuracy()) {
            latest = location;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        offer(location);
    }

    // Android 10 이하에서는 아래 메서드들이 인터페이스의 추상 메서드라 직접 구현해야 한다.
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}
