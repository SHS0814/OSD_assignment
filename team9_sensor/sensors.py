"""ENS160 + AHT21 기후 센서 드라이버 (I2C bus 1).

AHT21 (0x38): 온도(°C), 습도(%)
ENS160 (0x53): AQI(UBA 1~5), TVOC(ppb), eCO2(ppm)
레지스터는 각 제조사 데이터시트 기준.
"""
import time

from smbus2 import SMBus, i2c_msg

AHT21_ADDR = 0x38
ENS160_ADDR = 0x53

# ENS160 레지스터
ENS160_PART_ID = 0x00
ENS160_OPMODE = 0x10
ENS160_TEMP_IN = 0x13
ENS160_RH_IN = 0x15
ENS160_DEVICE_STATUS = 0x20
ENS160_DATA_AQI = 0x21
ENS160_DATA_TVOC = 0x22
ENS160_DATA_ECO2 = 0x24

ENS160_MODE_STANDARD = 0x02

# DEVICE_STATUS의 VALIDITY_FLAG (bit 3:2)
VALIDITY = {0: "normal", 1: "warm-up", 2: "initial start-up", 3: "invalid"}


class AHT21:
    def __init__(self, bus):
        self.bus = bus
        time.sleep(0.1)  # 전원 인가 후 대기
        if self._status() & 0x08 == 0:  # 캘리브레이션 안 됨 -> 초기화
            self.bus.write_i2c_block_data(AHT21_ADDR, 0xBE, [0x08, 0x00])
            time.sleep(0.01)

    def _status(self):
        msg = i2c_msg.read(AHT21_ADDR, 1)
        self.bus.i2c_rdwr(msg)
        return list(msg)[0]

    def read(self):
        """(온도 °C, 습도 %) 반환."""
        self.bus.write_i2c_block_data(AHT21_ADDR, 0xAC, [0x33, 0x00])
        for _ in range(10):
            time.sleep(0.08)
            msg = i2c_msg.read(AHT21_ADDR, 7)
            self.bus.i2c_rdwr(msg)
            d = list(msg)
            if d[0] & 0x80 == 0:  # busy 해제
                break
        else:
            raise RuntimeError("AHT21 측정 시간 초과")

        hum_raw = (d[1] << 12) | (d[2] << 4) | (d[3] >> 4)
        temp_raw = ((d[3] & 0x0F) << 16) | (d[4] << 8) | d[5]
        humidity = hum_raw * 100 / 1048576
        temp = temp_raw * 200 / 1048576 - 50
        return temp, humidity


class ENS160:
    def __init__(self, bus):
        self.bus = bus
        part = self.bus.read_i2c_block_data(ENS160_ADDR, ENS160_PART_ID, 2)
        part_id = part[0] | (part[1] << 8)
        if part_id != 0x0160:
            raise RuntimeError(f"ENS160 PART_ID 불일치: 0x{part_id:04x}")
        self.bus.write_byte_data(ENS160_ADDR, ENS160_OPMODE, ENS160_MODE_STANDARD)
        time.sleep(0.05)

    def set_compensation(self, temp, humidity):
        """AHT21 값으로 ENS160 온습도 보정."""
        t = int((temp + 273.15) * 64)
        h = int(humidity * 512)
        self.bus.write_i2c_block_data(ENS160_ADDR, ENS160_TEMP_IN,
                                      [t & 0xFF, t >> 8, h & 0xFF, h >> 8])

    def validity(self):
        status = self.bus.read_byte_data(ENS160_ADDR, ENS160_DEVICE_STATUS)
        return VALIDITY[(status >> 2) & 0x03]

    def read(self):
        """(AQI, TVOC ppb, eCO2 ppm) 반환."""
        aqi = self.bus.read_byte_data(ENS160_ADDR, ENS160_DATA_AQI) & 0x07
        tvoc = self.bus.read_i2c_block_data(ENS160_ADDR, ENS160_DATA_TVOC, 2)
        eco2 = self.bus.read_i2c_block_data(ENS160_ADDR, ENS160_DATA_ECO2, 2)
        return aqi, tvoc[0] | (tvoc[1] << 8), eco2[0] | (eco2[1] << 8)


class ClimateSensor:
    """AHT21 + ENS160을 묶어 다섯 값을 한 번에 읽는다."""

    def __init__(self, bus_no=1):
        self.bus = SMBus(bus_no)
        self.aht = AHT21(self.bus)
        self.ens = ENS160(self.bus)

    def read(self):
        temp, humidity = self.aht.read()
        self.ens.set_compensation(temp, humidity)
        aqi, tvoc, eco2 = self.ens.read()
        return {
            "temp": round(temp, 2),
            "humidity": round(humidity, 2),
            "AQI": aqi,
            "TVOC": tvoc,
            "eCO2": eco2,
            "timestamp": int(time.time()),
            "validity": self.ens.validity(),
        }

    def close(self):
        self.bus.close()


if __name__ == "__main__":
    s = ClimateSensor()
    print(f"대상 센서: AHT21 (0x{AHT21_ADDR:02x}), ENS160 (0x{ENS160_ADDR:02x})\n")
    try:
        while True:
            d = s.read()
            print(f"[ENVIRONMENT] Temp: {d['temp']:.2f} °C, Hum: {d['humidity']:.2f} %")
            print(f"[AIR QUALITY] AQI: {d['AQI']}단계, TVOC: {d['TVOC']} ppb, "
                  f"eCO2: {d['eCO2']} ppm ({d['validity']})")
            print("-" * 50)
            time.sleep(2)
    except KeyboardInterrupt:
        s.close()
