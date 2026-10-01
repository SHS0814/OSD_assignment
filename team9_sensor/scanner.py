"""폰의 과거 데이터 요청 광고 수신.

요청 광고는 Manufacturer Specific Data (회사 ID 0xFFFF, 테스트용 예약 ID):
  [0..1] pi_id       이 파이 MAC 주소의 마지막 2바이트
  [2..5] want_ts     uint32 LE, 원하는 시각 (0이면 가장 오래된 샘플)
  [6..9] phone_time  uint32 LE, 폰의 현재 시각

광고하는 동안 BlueZ discovery를 켜 두고, 장치의 ManufacturerData가
나타나거나 바뀔 때마다 콜백을 부른다. 폰은 phone_time을 계속 갱신하므로
같은 요청이어도 신호가 계속 온다.
"""
import struct

import dbus

import bluetooth_constants as bc

REQUEST_LEN = 10


def parse_request(pi_id, data):
    """요청 페이로드 → (want_ts, phone_time). 우리 파이 대상이 아니면 None."""
    data = bytes(data)
    if len(data) != REQUEST_LEN or data[:2] != pi_id:
        return None
    return struct.unpack_from("<II", data, 2)


class RequestScanner:
    def __init__(self, bus, adapter_path, pi_id, on_request):
        self.bus = bus
        self.adapter = dbus.Interface(
            bus.get_object(bc.BLUEZ_SERVICE_NAME, adapter_path), bc.ADAPTER_INTERFACE)
        self.pi_id = pi_id
        self.on_request = on_request

    def start(self):
        self.bus.add_signal_receiver(
            self._on_interfaces_added, "InterfacesAdded", bc.DBUS_OM_IFACE,
            bc.BLUEZ_SERVICE_NAME)
        self.bus.add_signal_receiver(
            self._on_properties_changed, "PropertiesChanged", bc.DBUS_PROPERTIES,
            bc.BLUEZ_SERVICE_NAME, path_keyword="path")
        self.adapter.SetDiscoveryFilter({"Transport": "le",
                                         "DuplicateData": dbus.Boolean(True)})
        self.adapter.StartDiscovery()

    def stop(self):
        try:
            self.adapter.StopDiscovery()
        except dbus.exceptions.DBusException:
            pass

    def _on_interfaces_added(self, path, interfaces):
        device = interfaces.get(bc.DEVICE_INTERFACE)
        if device and "ManufacturerData" in device:
            self._handle(device["ManufacturerData"])

    def _on_properties_changed(self, interface, changed, invalidated, path=None):
        if interface == bc.DEVICE_INTERFACE and "ManufacturerData" in changed:
            self._handle(changed["ManufacturerData"])

    def _handle(self, manufacturer_data):
        data = manufacturer_data.get(dbus.UInt16(bc.REQUEST_COMPANY_ID))
        if data is None:
            return
        request = parse_request(self.pi_id, data)
        if request:
            self.on_request(*request)
