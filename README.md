# Raspberry Pi BLE Collector (Java / Android Studio)

수업 PDF의 3주차 예제를 기반으로 만든 BLE 광고 패킷 수집 앱입니다.

## 구현된 기능

- 환경 센싱 Service UUID `0x181A`로 BLE 광고 필터링
- `opensrc_week_3` 장치의 이름, MAC, RSSI 및 ServiceData 표시
- PDF에 제시된 13바이트 little-endian 패킷 파싱
  - 온도, 습도, AQI, TVOC, eCO2, Unix timestamp
- 실시간 스캔 기록과 동작 로그 표시
- 수집 경과 시간과 유효 패킷 수 표시
- 수집 결과를 CSV로 저장
- Android 6~11 및 Android 12 이상 권한 분기

## 실행 방법

1. Android Studio에서 이 폴더를 엽니다.
2. Gradle Sync 후 BLE를 지원하는 실제 Android 기기를 연결합니다.
3. 앱을 실행하고 `주변 기기` 권한을 허용합니다.
4. `스캔 시작`을 누릅니다.
5. 수집이 끝나면 `스캔 중지`, `CSV 저장`을 누릅니다.

에뮬레이터에서는 실제 BLE 광고 스캔을 검증할 수 없으므로 실제 기기가 필요합니다.

## CSV 위치와 형식

파일은 다음 앱 전용 외부 저장소에 생성됩니다.

```text
/storage/emulated/0/Android/data/com.example.rpiblecollector/files/ble_data_YYYYMMDD_HHMMSS.csv
```

Android Studio의 Device Explorer로 내려받을 수 있습니다. 저장 컬럼은 수신 시각, 장치명,
MAC, RSSI, UUID, 온도, 습도, AQI, TVOC, eCO2, 센서 Unix timestamp, raw hex입니다.

## 패킷 구조

| Byte | 자료형 | 의미 |
|---|---|---|
| 0~1 | int16 LE | 온도 × 100 |
| 2~3 | uint16 LE | 습도 × 100 |
| 4 | uint8 | AQI (1~5) |
| 5~6 | uint16 LE | TVOC (ppb) |
| 7~8 | uint16 LE | eCO2 (ppm) |
| 9~12 | uint32 LE | Unix timestamp |

PDF의 실습 조건에 따라 데이터는 10분 이상 수집하는 것을 권장합니다.
