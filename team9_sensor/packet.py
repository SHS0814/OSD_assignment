"""광고 ServiceData(0x181A) 13바이트 페이로드.

  [0..1]  int16  온도 x100 (°C)
  [2..3]  uint16 습도 x100 (%)
  [4]     uint8  AQI
  [5..6]  uint16 TVOC (ppb)
  [7..8]  uint16 eCO2 (ppm)
  [9..12] uint32 Unix timestamp

4주차 앱의 SensorPacket.parse()와 같은 포맷.
"""
import struct

FORMAT = "<hHBHHI"

# 요청한 시각에 샘플이 없을 때 보내는 표시 패킷의 온도/습도 값.
# 물리적으로 나올 수 없는 값이라 실측과 헷갈리지 않는다.
NO_DATA_TEMP_RAW = 0x7FFF
NO_DATA_HUMIDITY_RAW = 0xFFFF


def build_payload(d):
    return struct.pack(FORMAT,
                       int(round(d["temp"] * 100)),
                       int(round(d["humidity"] * 100)),
                       d["AQI"], d["TVOC"], d["eCO2"], d["timestamp"])


def build_no_data(ts):
    """ts 근처에 샘플이 없다는 표시. timestamp는 요청한 ts 그대로."""
    return struct.pack(FORMAT, NO_DATA_TEMP_RAW, NO_DATA_HUMIDITY_RAW,
                       0, 0, 0, ts)


def is_no_data(payload):
    return struct.unpack_from("<hH", payload) == (NO_DATA_TEMP_RAW, NO_DATA_HUMIDITY_RAW)
