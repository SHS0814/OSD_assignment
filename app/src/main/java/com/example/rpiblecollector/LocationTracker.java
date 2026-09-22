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
 * PDF 21쪽 요청 양식의 {@code lat} / {@code lon} 을 채우기 위한 최소 구현.
 * 위치 권한이 없거나 아직 고정(fix)이 없으면 0.0 을 그대로 보낸다.
 */
public final class LocationTracker {
    private static final long MIN_UPDATE_MILLIS = 10_000L;
    private static final float MIN_UPDATE_METERS = 5.0f;

    private final Context context;
    private final LocationManager locationManager;
    private Location lastLocation;
    private boolean listening;

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLocation = location;
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    public LocationTracker(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager)
                this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (listening || locationManager == null || !hasPermission()) {
            return;
        }
        listening = true;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (!locationManager.isProviderEnabled(provider)) {
                    continue;
                }
                Location known = locationManager.getLastKnownLocation(provider);
                if (known != null && isBetter(known)) {
                    lastLocation = known;
                }
                locationManager.requestLocationUpdates(provider, MIN_UPDATE_MILLIS,
                        MIN_UPDATE_METERS, locationListener, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException ignored) {
                // 제공자를 쓸 수 없으면 lat/lon 은 0.0 으로 전송한다.
            }
        }
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        if (!listening || locationManager == null) {
            return;
        }
        listening = false;
        try {
            locationManager.removeUpdates(locationListener);
        } catch (SecurityException ignored) {
        }
    }

    public double latitude() {
        return lastLocation == null ? 0.0 : lastLocation.getLatitude();
    }

    public double longitude() {
        return lastLocation == null ? 0.0 : lastLocation.getLongitude();
    }

    public boolean hasFix() {
        return lastLocation != null;
    }

    private boolean isBetter(Location candidate) {
        return lastLocation == null || candidate.getTime() > lastLocation.getTime();
    }
}
