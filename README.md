# Raspberry Pi BLE Collector (Java / Android Studio)

수업 PDF의 3주차 예제를 기반으로 만든 BLE 광고 패킷 수집 앱입니다.

## 구현된 기능

- 환경 센싱 Service UUID `0x181A`로 BLE 광고 필터링
- `opensrc_week_3` 장치의 이름, MAC, RSSI 및 ServiceData 표시
- PDF에 제시된 13바이트 little-endian 패킷 파싱
  - 온도, 습도, AQI, TVOC, eCO2, Unix timestamp
- 각 스캔 기록을 눌러 BLE 패킷 상세 화면 표시
  - AD 구조별 offset, length, type, 헥스, 디코딩값
  - Flags, UUID, TX Power, PHY, SID, 제조사 데이터, Service Data
  - 원본을 HEX·DEC·ASCII·hex dump로 확인 및 복사
- 실시간 스캔 기록과 동작 로그 표시
- 수집 경과 시간과 유효 패킷 수 표시
- Foreground Service와 지속 알림을 통한 백그라운드 수집
- 알림의 `중지 후 저장` 작업으로 앱을 열지 않고 수집 종료
- 수집 결과를 CSV로 저장
- Android 6~11, Android 12 이상 BLE 권한 및 Android 13 이상 알림 권한 분기

## 실행 방법

1. Android Studio에서 이 폴더를 엽니다.
2. Gradle Sync 후 BLE를 지원하는 실제 Android 기기를 연결합니다.
3. 앱을 실행하고 `주변 기기` 권한을 허용합니다. Android 13 이상에서는 수집 상태 표시용 알림 권한도 허용하는 것이 좋습니다.
4. `스캔 시작`을 누릅니다.
5. 이후 다른 앱을 사용하거나 화면을 꺼도 지속 알림이 표시되는 동안 BLE 수집이 계속됩니다.
6. 수집이 끝나면 앱의 `스캔 중지`, `CSV 저장`을 순서대로 누르거나, 지속 알림에서 `중지 후 저장`을 누릅니다.

사용자가 앱을 강제 종료하거나 기기를 재부팅하면 수집은 종료됩니다. CSV는 중간에도 저장할 수 있으며, 스캔 중 저장하면 해당 시점까지의 스냅샷이 새 파일로 생성됩니다.

에뮬레이터에서는 실제 BLE 광고 스캔을 검증할 수 없으므로 실제 기기가 필요합니다.

## CSV 위치와 형식

파일은 다음 앱 전용 외부 저장소에 생성됩니다.

```text
/storage/emulated/0/Android/data/com.example.rpiblecollector/files/ble_data_YYYYMMDD_HHMMSS.csv
```

Android Studio의 Device Explorer로 내려받을 수 있습니다. 저장 컬럼은 수신 시각, 장치명,
MAC, RSSI, UUID, 온도, 습도, AQI, TVOC, eCO2, 센서 Unix timestamp,
ServiceData raw hex, 전체 광고 raw hex, 패킷 상세 해석 결과입니다.

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
