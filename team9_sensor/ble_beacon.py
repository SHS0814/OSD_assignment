"""팀 9 환경 센서 BLE 비콘.

ENS160+AHT21 값을 매 분 정각에 읽어 CSV와 SQLite에 저장하고, BLE 광고 패킷의
ServiceData(0x181A)에 13바이트 little-endian 페이로드로 실어 보낸다.
페이로드 포맷은 packet.py 참고 (4주차 앱의 SensorPacket.parse()와 같음).

폰이 과거 데이터 요청 광고(scanner.py)를 보내면 요청한 시각의 샘플을
실시간 값과 1초마다 번갈아 광고한다 (backlog.py).

실행: sudo python3 ble_beacon.py
"""
import csv
import os
import signal
import sys
import time
from datetime import datetime

import dbus
import dbus.mainloop.glib
import dbus.service
from gi.repository import GLib

import bluetooth_constants as bc
from backlog import Backlog
from packet import build_payload, is_no_data
from scanner import RequestScanner
from sensors import ClimateSensor
from store import SampleStore

LOCAL_NAME = "Opensrc_team9"
MEASURE_INTERVAL_SEC = 60        # 매 분 정각에 측정
SLOT_INTERVAL_MS = 1000          # 요청이 있을 때 실시간 / 과거를 바꾸는 주기
REQUEST_TOLERANCE_SEC = 30       # 요청 시각에서 이 범위 안의 샘플만 응답
REQUEST_TIMEOUT_SEC = 15         # 이 시간 동안 요청이 안 보이면 실시간만 광고
DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
CSV_PATH = os.path.join(DATA_DIR, "sensor_log.csv")
DB_PATH = os.path.join(DATA_DIR, "samples.db")
CSV_HEADER = ["timestamp", "datetime", "temp", "humidity", "AQI", "TVOC",
              "eCO2", "validity", "payload_hex"]


class Advertisement(dbus.service.Object):
    PATH_BASE = "/org/bluez/team9/advertisement"

    def __init__(self, bus, index, advertising_type):
        self.path = self.PATH_BASE + str(index)
        self.bus = bus
        self.ad_type = advertising_type

        self.local_name = LOCAL_NAME
        self.service_uuids = [bc.SERVICE_UUID]
        self.service_data = {}
        self.include_tx_power = False

        self._last_payload = None

        super().__init__(bus, self.path)

    def get_path(self):
        return dbus.ObjectPath(self.path)

    def get_properties(self):
        props = {
            "Type": self.ad_type,
            "LocalName": dbus.String(self.local_name),
            "ServiceUUIDs": dbus.Array(self.service_uuids, signature="s"),
            "ServiceData": dbus.Dictionary(self.service_data, signature="sv"),
        }
        if self.include_tx_power:
            props["IncludeTxPower"] = dbus.Boolean(True)
        return {bc.ADVERTISEMENT_INTERFACE: props}

    @dbus.service.method(bc.DBUS_PROPERTIES, in_signature="s",
                         out_signature="a{sv}")
    def GetAll(self, interface):
        if interface != bc.ADVERTISEMENT_INTERFACE:
            raise dbus.exceptions.DBusException(
                "org.freedesktop.DBus.Error.InvalidArgs",
                f"Unknown interface: {interface}")
        return self.get_properties()[bc.ADVERTISEMENT_INTERFACE]

    @dbus.service.method(bc.ADVERTISEMENT_INTERFACE, in_signature="",
                         out_signature="")
    def Release(self):
        print(f"{self.path}: Released")

    @dbus.service.signal(bc.DBUS_PROPERTIES, signature="sa{sv}as")
    def PropertiesChanged(self, interface, changed, invalidated):
        pass

    def update_data(self, payload):
        # 값이 안 바뀌면 굳이 갱신 안 함
        if payload == self._last_payload:
            return True

        self.service_data[bc.SERVICE_UUID] = dbus.Array(payload, signature="y")
        self._last_payload = payload

        changed_props = {
            "ServiceData": dbus.Dictionary(self.service_data, signature="sv"),
        }
        self.PropertiesChanged(bc.ADVERTISEMENT_INTERFACE, changed_props, [])
        return True


def find_adapter(bus):
    om = dbus.Interface(bus.get_object(bc.BLUEZ_SERVICE_NAME, "/"),
                        bc.DBUS_OM_IFACE)
    for path, ifaces in om.GetManagedObjects().items():
        if bc.ADVERTISING_MANAGER_INTERFACE in ifaces and path.endswith(bc.ADAPTER_NAME):
            return path
    return None


def append_csv(d, payload):
    new_file = not os.path.exists(CSV_PATH)
    with open(CSV_PATH, "a", newline="") as f:
        w = csv.writer(f)
        if new_file:
            w.writerow(CSV_HEADER)
        w.writerow([d["timestamp"],
                    datetime.fromtimestamp(d["timestamp"]).isoformat(),
                    d["temp"], d["humidity"], d["AQI"], d["TVOC"], d["eCO2"],
                    d["validity"], payload.hex()])


def fmt_ts(ts):
    return datetime.fromtimestamp(ts).strftime("%m-%d %H:%M:%S")


def main():
    os.makedirs(DATA_DIR, exist_ok=True)
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()

    adapter_path = find_adapter(bus)
    if adapter_path is None:
        sys.exit("LEAdvertisingManager1을 지원하는 어댑터가 없음")
    print(f"Found adapter: {adapter_path}")

    adapter_props = dbus.Interface(
        bus.get_object(bc.BLUEZ_SERVICE_NAME, adapter_path), bc.DBUS_PROPERTIES)
    adapter_props.Set(bc.ADAPTER_INTERFACE, "Powered", dbus.Boolean(True))
    address = str(adapter_props.Get(bc.ADAPTER_INTERFACE, "Address"))
    pi_id = bytes.fromhex(address.replace(":", ""))[-2:]
    print(f"Adapter address {address}, request pi_id {pi_id.hex()}")

    ad_manager = dbus.Interface(
        bus.get_object(bc.BLUEZ_SERVICE_NAME, adapter_path),
        bc.ADVERTISING_MANAGER_INTERFACE)

    store = SampleStore(DB_PATH)
    imported = store.import_csv(CSV_PATH)
    if imported:
        print(f"기존 CSV에서 {imported}행을 DB로 가져옴")
    print(f"DB 샘플 {store.count()}개")
    backlog = Backlog(store, REQUEST_TOLERANCE_SEC, REQUEST_TIMEOUT_SEC)

    sensor = ClimateSensor()
    advertisement = Advertisement(bus, 0, "peripheral")
    mainloop = GLib.MainLoop()
    live = {"payload": None}

    def read_sensor():
        d = sensor.read()
        if d["AQI"] == 0:  # ENS160 첫 측정 전 (값 없음)
            return None, None
        return d, build_payload(d)

    def save(d, payload):
        store.add(d)
        append_csv(d, payload)
        live["payload"] = payload

    def measure():
        try:
            d, payload = read_sensor()
            if d is None:
                print("ENS160 데이터 준비 중...")
                return
            save(d, payload)
            print(f"[{datetime.fromtimestamp(d['timestamp']):%H:%M:%S}] "
                  f"Temp {d['temp']:.2f}°C | Hum {d['humidity']:.2f}% | "
                  f"AQI {d['AQI']} | TVOC {d['TVOC']} ppb | eCO2 {d['eCO2']} ppm "
                  f"({d['validity']})")
            print(f"Payload: {payload.hex()}")
        except Exception as e:
            print(f"Error in measure: {e}")

    def schedule_measure():
        # 다음 분 정각. GLib 타이머가 조금 일찍 깨도 이전 초로 찍히지 않게 50ms 여유
        delay = MEASURE_INTERVAL_SEC - time.time() % MEASURE_INTERVAL_SEC + 0.05
        GLib.timeout_add(int(delay * 1000), measure_and_reschedule)

    def measure_and_reschedule():
        measure()
        schedule_measure()
        return False  # 타이머는 매번 새로 건다 (정각 유지)

    def update_slot():
        try:
            payload = backlog.next_payload(live["payload"], time.monotonic())
            advertisement.update_data(payload)
        except Exception as e:
            print(f"Error in update_slot: {e}")
        return True  # GLib 타이머 유지

    last_offset = {"value": None}

    def on_request(want_ts, phone_time):
        if backlog.on_request(want_ts, time.monotonic()):
            if is_no_data(backlog.payload):
                answer = "데이터 없음"
            else:
                sent = int.from_bytes(backlog.payload[9:13], "little")
                answer = f"샘플 {fmt_ts(sent)}"
            wanted = "가장 오래된 샘플" if want_ts == 0 else fmt_ts(want_ts)
            print(f"요청 {wanted} → {answer}")
        # 시계 보정은 아직 하지 않고 차이만 기록 (NTP가 동작 중).
        # 전파 지연으로 1초씩 흔들리므로 마지막 기록보다 2초 이상 달라졌을 때만 남긴다.
        offset = phone_time - int(time.time())
        logged = last_offset["value"]
        if logged is None or abs(offset - logged) >= 2:
            print(f"폰 시각과 차이 {offset:+d}초")
            last_offset["value"] = offset

    scanner = RequestScanner(bus, adapter_path, pi_id, on_request)

    def on_registered():
        print("Advertisement registered successfully")

    def on_register_error(error):
        print(f"Failed to register advertisement: {error}")
        mainloop.quit()

    def shutdown(*_):
        scanner.stop()
        try:
            ad_manager.UnregisterAdvertisement(advertisement.get_path())
        except dbus.exceptions.DBusException:
            pass
        sensor.close()
        store.close()
        mainloop.quit()

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    # 첫 값이 준비될 때까지 기다렸다가 등록
    for _ in range(20):
        d, payload = read_sensor()
        if d is not None:
            break
        GLib.usleep(500_000)
    else:
        sys.exit("ENS160에서 값을 읽지 못함")
    save(d, payload)
    advertisement.update_data(payload)

    print("Registering advertisement...")
    ad_manager.RegisterAdvertisement(advertisement.get_path(), {},
                                     reply_handler=on_registered,
                                     error_handler=on_register_error)

    scanner.start()
    print("요청 스캔 시작")

    schedule_measure()
    GLib.timeout_add(SLOT_INTERVAL_MS, update_slot)
    print("Starting main loop...")
    mainloop.run()


if __name__ == "__main__":
    main()
