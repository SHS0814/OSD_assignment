package com.example.rpiblecollector;

public final class BleRecord {
    public final long receivedAtMillis;
    public final String name;
    public final String address;
    public final int rssi;
    public final String uuid;
    public final SensorPacket sensor;
    public final String rawHex;

    public BleRecord(long receivedAtMillis, String name, String address, int rssi,
                     String uuid, SensorPacket sensor, String rawHex) {
        this.receivedAtMillis = receivedAtMillis;
        this.name = name;
        this.address = address;
        this.rssi = rssi;
        this.uuid = uuid;
        this.sensor = sensor;
        this.rawHex = rawHex;
    }
}
