# Raspberry Pi BLE Collector (Java / Android Studio)

수업 PDF의 3주차(BLE Communication)와 4주차(HTTP Communication) 예제를 기반으로 만든
BLE 광고 패킷 수집 · 서버 전송 앱입니다.

4주차 PDF 7쪽의 아키텍처 중 모바일 디바이스 구간을 그대로 구현합니다.

```text
Raspberry Pi ──BLE(0x181A)──▶ Android 앱 ──HTTP POST(JSON)──▶ 203.255.81.72:10021
                                             ◀── Response(result/message/received_data)
```

## 구현된 기능

### BLE 수집 (3주차)

- 환경 센싱 Service UUID `0x181A`로 BLE 광고 필터링
- `opensrc_week_3` 장치의 이름, MAC, RSSI 및 ServiceData 표시
- PDF에 제시된 13바이트 little-endian 패킷 파싱
  - 온도, 습도, AQI, TVOC, eCO2, Unix timestamp
- 실시간 스캔 기록과 동작 로그 표시
- 수집 경과 시간과 유효 패킷 수 표시
- Foreground Service와 지속 알림을 통한 백그라운드 수집
- 알림의 `중지 후 저장` 작업으로 앱을 열지 않고 수집 종료
- 수집 결과를 CSV로 저장
- Android 6~11, Android 12 이상 BLE 권한 및 Android 13 이상 알림 권한 분기

### HTTP 통신 (4주차)

- Retrofit 2 + Gson + Scalars 로 REST API 호출 (PDF 16·18쪽)
- PDF 21쪽 요청 양식 그대로 JSON Body 생성 후 `POST /sensor/opensrc/test/`
- PDF 22쪽 응답 양식(`result` / `message` / `received_data`)을 파싱해 화면과 로그에 표시
- `자동 전송` 체크 시 설정한 주기(기본 10초)마다 최신 패킷을 서버로 전송
- `지금 전송` 버튼으로 최근 센서 패킷 1건을 수동 전송
- 팀 번호 · 센서 이름을 화면에서 입력하고 SharedPreferences 에 유지
- `sender` 는 PDF 21쪽 지시대로 `Settings.Secure.ANDROID_ID` 사용
- `lat` / `lon` 은 LocationManager 의 최근 위치를 사용하고, 위치 권한이 없으면 `0.0`
- 전송 성공/실패 건수와 최근 서버 응답을 화면·알림에 표시
- `수집 현황` 버튼으로 PDF 26쪽의 실시간 확인 페이지를 브라우저로 열기

## 구성 (4주차 관련 소스)

| 파일 | PDF 대응 | 역할 |
|---|---|---|
| `app/build.gradle` | 16쪽 | retrofit / converter-gson / gson / converter-scalars 의존성 |
| `AndroidManifest.xml` | 17쪽 | `INTERNET` 권한과 `android:usesCleartextTraffic="true"` |
| `SensorUploader.java` | 18·21쪽 | `Retrofit.Builder` 설정과 `enqueue` / `onResponse` / `onFailure` |
| `CommData.java` | 19·20쪽 | `@POST @Body`, `@FormUrlEncoded @Field`, `@GET @Query` 인터페이스 |
| `PostData.java` | 20·21쪽 | 요청 양식 JSON 모델 (`@Expose` / `@SerializedName`) |
| `PostResponse.java` | 22쪽 | 응답 양식 모델 |
| `UploadConfig.java` | 21쪽 | 팀·센서·전송 주기 설정과 `Settings.Secure.ANDROID_ID` |
| `LocationTracker.java` | 21쪽 | 요청 양식의 `lat` / `lon` |
| `BleScanService.java` | 7쪽 | BLE 수신 → HTTP 전송 연결 |

`targetSdk` 는 PDF 16쪽 화면의 33 대신 기존 프로젝트 값인 35를 유지했습니다.
이 앱은 Android 14 이상의 Foreground Service 유형(`connectedDevice`) API 를 사용하므로
targetSdk 를 낮추면 백그라운드 수집 동작이 달라집니다.

## API 요청 / 응답

요청 (PDF 21쪽)

```json
{
  "key": "opensrc2026",
  "team": "9",
  "sensor": "environment_sensor",
  "mac": "AA:BB:CC:DD:EE:FF",
  "temp": 27.64,
  "humidity": 51.71,
  "AQI": 1,
  "TVOC": 0,
  "eCO2": 400,
  "timestamp": 1784154027,
  "lat": 36.6291,
  "lon": 127.4565,
  "sender": "abcd-1234-5679-11"
}
```

응답 (PDF 22쪽)

```json
{
  "result": "Success",
  "message": "Data received from team TA successfully!",
  "received_data": { "team": "team TA", "sensor": "sensor TA" }
}
```

전송한 데이터는 <http://203.255.81.72:10021/sensor/opensrc/check/> 에서 실시간으로 확인할 수 있습니다.

## 실행 방법

1. Android Studio에서 이 폴더를 엽니다.
2. Gradle Sync 후 BLE를 지원하는 실제 Android 기기를 연결합니다.
3. 앱을 실행하고 `주변 기기` 권한을 허용합니다. Android 13 이상에서는 수집 상태 표시용 알림 권한도 허용하는 것이 좋습니다.
4. `서버 전송` 구역에서 **팀 번호**와 **센서 이름**을 입력합니다.
5. `자동 전송` 을 켜고 전송 주기(초)를 정합니다. 위치 권한을 허용하면 `lat` / `lon` 이 함께 전송됩니다.
6. `스캔 시작`을 누릅니다. 수신한 패킷이 설정한 주기마다 서버로 POST 됩니다.
7. 이후 다른 앱을 사용하거나 화면을 꺼도 지속 알림이 표시되는 동안 BLE 수집과 서버 전송이 계속됩니다.
8. 수집이 끝나면 앱의 `스캔 중지`, `CSV 저장`을 순서대로 누르거나, 지속 알림에서 `중지 후 저장`을 누릅니다.

사용자가 앱을 강제 종료하거나 기기를 재부팅하면 수집은 종료됩니다. CSV는 중간에도 저장할 수 있으며, 스캔 중 저장하면 해당 시점까지의 스냅샷이 새 파일로 생성됩니다.

에뮬레이터에서는 실제 BLE 광고 스캔을 검증할 수 없으므로 실제 기기가 필요합니다.

## 전송 주기에 대해

BLE 광고는 초당 여러 번 수신되므로 모든 패킷을 그대로 전송하면 공용 서버에 과도한 요청이 갑니다.
그래서 자동 전송에는 두 가지 제한을 두었습니다.

- 설정한 주기(기본 10초)보다 짧은 간격의 패킷은 건너뜁니다 → CSV `upload_result` 에 `skipped_interval`
- 직전에 보낸 것과 센서 `timestamp` 가 같으면 건너뜁니다 → `skipped_duplicate`

## CSV 위치와 형식

파일은 다음 앱 전용 외부 저장소에 생성됩니다.

```text
/storage/emulated/0/Android/data/com.example.rpiblecollector/files/ble_data_YYYYMMDD_HHMMSS.csv
```

Android Studio의 Device Explorer로 내려받을 수 있습니다.

저장 컬럼은 세 묶음입니다.

- **해석한 값** — 수신 시각, 장치명, MAC, RSSI, UUID, 온도, 습도, AQI, TVOC, eCO2,
  센서 Unix timestamp, HMAC 태그 hex, 위도, 경도, 서버 전송 결과
- **무선 계층 정보** — `tx_power`, `primary_phy`, `secondary_phy`, `advertising_sid`,
  `is_legacy`, `data_status`
- **원본** — `service_data_hex`(0x181A ServiceData 전체), `scan_record_hex`(광고 패킷 전체)

원본 두 컬럼이 항상 함께 저장되므로, 나중에 패킷 포맷 해석이 틀렸다고 밝혀져도
`scan_record_hex` 에서 다시 파싱할 수 있습니다. 해석 결과와 원본이 분리되어 있는 것이
이 CSV 형식의 목적입니다.

`data_status` 가 `truncated` 이면 광고가 잘려서 수신된 것으로, HMAC 태그 일부가
유실되었을 수 있습니다. 이 경우 앱 로그에도 경고가 남습니다.

## 패킷 구조

| Byte | 자료형 | 의미 |
|---|---|---|
| 0~1 | int16 LE | 온도 × 100 |
| 2~3 | uint16 LE | 습도 × 100 |
| 4 | uint8 | AQI (1~5) |
| 5~6 | uint16 LE | TVOC (ppb) |
| 7~8 | uint16 LE | eCO2 (ppm) |
| 9~12 | uint32 LE | Unix timestamp |
| 13~ | bytes | HMAC 태그 (PDF에 없는, 이후 펌웨어에서 추가된 필드) |

HMAC 태그는 길이를 고정하지 않고 13바이트 뒤의 나머지를 그대로 보존합니다.
태그가 없는 13바이트 패킷도 그대로 동작합니다. 실제 수신한 태그 길이는
첫 유효 패킷 수신 시 앱 로그에 표시됩니다.

현재 태그 검증(verify)은 구현하지 않았습니다. 알고리즘, 공유 키, MAC 대상 범위가
정해지면 추가할 수 있습니다.

PDF의 실습 조건에 따라 데이터는 10분 이상 수집하는 것을 권장합니다.

## 테스트

```bash
./gradlew :app:testDebugUnitTest
```

- `SensorPacketTest` — 13바이트 little-endian 패킷 파싱
- `PostDataTest` — PDF 21쪽 요청 양식 key 이름/값, 22쪽 응답 양식 파싱
