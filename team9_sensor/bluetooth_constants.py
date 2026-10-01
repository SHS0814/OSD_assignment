"""BlueZ 관련 Static 상수 및 경로."""
ADAPTER_NAME = "hci0"

BLUEZ_SERVICE_NAME = "org.bluez"
BLUEZ_NAMESPACE = "/org/bluez/"
DBUS_PROPERTIES = "org.freedesktop.DBus.Properties"
DBUS_OM_IFACE = "org.freedesktop.DBus.ObjectManager"

ADAPTER_INTERFACE = BLUEZ_SERVICE_NAME + ".Adapter1"
ADVERTISEMENT_INTERFACE = BLUEZ_SERVICE_NAME + ".LEAdvertisement1"
ADVERTISING_MANAGER_INTERFACE = BLUEZ_SERVICE_NAME + ".LEAdvertisingManager1"
DEVICE_INTERFACE = BLUEZ_SERVICE_NAME + ".Device1"

# 폰의 과거 데이터 요청 광고 (Manufacturer Specific Data, 테스트용 예약 회사 ID)
REQUEST_COMPANY_ID = 0xFFFF

# 환경 센싱 서비스 (Environmental Sensing, 0x181A)
SERVICE_UUID = "0000181a-0000-1000-8000-00805f9b34fb"
