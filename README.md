# Raspberry Pi BLE Collector (Java / Android Studio)

수업 PDF의 3주차(BLE Communication)와 4주차(HTTP Communication) 예제를 기반으로 만든
BLE 광고 패킷 수집 · 서버 전송 앱입니다. 5주차(Raspberry Pi Programming)부터는
우리 팀이 만든 라즈베리 파이 센서(`Opensrc_team9`)의 데이터를 받아 서버로 올립니다.

```text
팀 라즈베리 파이 ──BLE(0x181A)──▶ Android 앱 ──HTTP POST(JSON)──▶ 203.255.81.72:10021/sensor/opensrc/upload/
(Opensrc_team9)                                  ◀── Response(result/message/received_data/status)
```

## 5주차 변경 사항 (PDF 46~50쪽)

| | 4주차 | 5주차 |
|---|---|---|
| 센서 | 연구실 라즈베리 파이 | 팀별 라즈베리 파이 (`Opensrc_team9`) |
| API URL | `/sensor/opensrc/test/` | `/sensor/opensrc/upload/` |
| key | 공용 `opensrc2026` | 팀별 키 (화면에서 입력, 기본값 `opensrc-team9`) |
| `team` 필드 | 보냄 | 없음 (서버가 key 로 팀 판별) |
| 검증용 `raw` / HMAC | 필요 | 불필요 |
| 응답 `status` | - | `ok` / `out_of_range`(저장은 됨, `detail` 확인) |
| 수집 현황 | `/sensor/opensrc/check/` | `/sensor/opensrc/teams/` |

주변의 다른 팀 파이도 같은 0x181A 로 광고하므로, 화면의 **대상 장치 이름**
(기본 `Opensrc_team9`)과 이름이 같은 패킷만 서버로 보냅니다. 다른 장치의 패킷은
CSV 에만 남고 `upload_result` 가 `skipped_other_device` 가 됩니다.
대상 장치 이름을 비우면 0x181A 패킷을 모두 보냅니다.

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
- `자동 전송` 체크 시 장부에 대기 행이 생기는 대로 timestamp 순으로 서버에 전송 (아래 "장부" 참고)
- 장부 탭의 `지금 전송` 버튼으로 장부의 미전송 데이터를 수동 전송 (아래 "장부" 참고)
- 팀별 API key · 대상 장치 이름을 화면에서 입력하고 SharedPreferences 에 유지 (대상 장치 이름이 요청의 `sensor` 로 그대로 전송됨)
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
| `UploadConfig.java` | 21쪽 | key·대상 장치(=sensor)·자동 전송 설정과 `Settings.Secure.ANDROID_ID` |
| `LocationTracker.java` | 21쪽 | 요청 양식의 `lat` / `lon` |
| `BleScanService.java` | 7쪽 | BLE 수신 → HTTP 전송 연결 |

`targetSdk` 는 PDF 16쪽 화면의 33 대신 기존 프로젝트 값인 35를 유지했습니다.
이 앱은 Android 14 이상의 Foreground Service 유형(`connectedDevice`) API 를 사용하므로
targetSdk 를 낮추면 백그라운드 수집 동작이 달라집니다.

## API 요청 / 응답

요청 (5주차 PDF 48쪽, `POST /sensor/opensrc/upload/`)

```json
{
  "key": "opensrc-team9",
  "sensor": "Opensrc_team9",
  "mac": "B8:27:EB:2A:11:0F",
  "temp": 29.35,
  "humidity": 51.1,
  "AQI": 1,
  "TVOC": 63,
  "eCO2": 483,
  "timestamp": 1790742407,
  "lat": 36.629011,
  "lon": 127.457092,
  "sender": "abcd-1234"
}
```

응답 (5주차 PDF 49쪽)

```json
{
  "result": "Success",
  "message": "Data received from Team9 successfully!",
  "received_data": { "team": "Team9", "sensor": "Opensrc_team9" },
  "status": "ok"
}
```

| status | 의미 | 조치 |
|---|---|---|
| `ok` | 정상 | - |
| `out_of_range` | 값이 물리적 범위를 벗어남 (저장은 됨) | `detail` 확인. 배선, 단위 변환, Pi 시계(NTP) 점검 |
| (HTTP 400) | key 오류 또는 필수 값 누락 | `message` 확인. 앱이 `errorBody()` 로 읽어 로그에 표시 |

전송한 데이터는 <http://203.255.81.72:10021/sensor/opensrc/teams/> 에서 실시간으로 확인할 수 있습니다.
(접속이 안 되면 `203` 을 `10` 으로 바꿔서 접속)

## 실행 방법

1. Android Studio에서 이 폴더를 엽니다.
2. Gradle Sync 후 BLE를 지원하는 실제 Android 기기를 연결합니다.
3. 앱을 실행하고 `주변 기기` 권한을 허용합니다. Android 13 이상에서는 수집 상태 표시용 알림 권한도 허용하는 것이 좋습니다.
4. `서버 전송` 구역에서 **팀별 API key**와 **대상 장치 이름**을 확인합니다. 대상 장치 이름(파이의 광고 로컬네임)이 서버 요청의 `sensor` 로도 쓰입니다.
5. `자동 전송` 을 켭니다(끄면 장부에만 쌓이고 `지금 전송` 때 보냅니다). 위치 권한을 허용하면 `lat` / `lon` 이 함께 전송됩니다.
6. `스캔 시작`을 누릅니다. 수신한 샘플이 장부에 기록되고, 장부를 거쳐 서버로 POST 됩니다.
7. 이후 다른 앱을 사용하거나 화면을 꺼도 지속 알림이 표시되는 동안 BLE 수집과 서버 전송이 계속됩니다.
8. 수집이 끝나면 앱의 `스캔 중지`, `CSV 저장`을 순서대로 누르거나, 지속 알림에서 `중지 후 저장`을 누릅니다.

사용자가 앱을 강제 종료하거나 기기를 재부팅하면 수집은 종료됩니다. CSV는 중간에도 저장할 수 있으며, 스캔 중 저장하면 해당 시점까지의 스냅샷이 새 파일로 생성됩니다.

에뮬레이터에서는 실제 BLE 광고 스캔을 검증할 수 없으므로 실제 기기가 필요합니다.

## 서버로 나가는 경로

서버로 보내는 경로는 **장부 → 서버** 하나뿐입니다. BLE 광고는 초당 여러 번 수신되지만, 같은 센서
timestamp 는 장부에 한 번만 기록되고 서버로도 한 번만 갑니다. 그래서 따로 전송 주기를 두지 않습니다.

- 요청은 `SensorUploader.send(BacklogDb.Row …)` 로만 만들 수 있고, `BacklogDb.Row` 는 장부 DB 에서만
  만들어집니다. 장부에 없는 데이터, 이미 전송된 행, "데이터 없음" 행은 보낼 수 없습니다.
- 수집 CSV 의 `upload_result` 는 그 패킷이 장부에서 어떻게 처리됐는지 기록합니다
  (`queued`/`stored` → 장부에 새로 기록, `skipped_duplicate` → 이미 장부에 있음, `success(…)`/`fail: …` → 전송 결과).

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

## 장부 (수집 → 장부 → 서버)

서버 API 에는 조회·정렬·중복 처리가 없어서, 무엇을 보냈는지는 앱의 장부가 관리합니다.

```text
BLE 수신 ─┐
          ├─▶ 장부 (앱 내부 SQLite, timestamp 당 1행) ─▶ 미전송 행만 timestamp 순으로 POST ─▶ 서버
CSV 가져오기 ┘                    │
                                 └─▶ 장부 저장 → ledger.csv
```

- 우리 팀 파이의 샘플은 timestamp 를 기본키로 장부에 **한 번만** 기록됩니다.
- 파이가 "데이터 없음" 특수값(온도 327.67, 습도 655.35)으로 답한 시각은 장부에 **데이터 없음** 상태로
  남깁니다. 그래서 같은 시각을 다시 요청하지 않지만, 측정값이 아니므로 **서버로는 절대 보내지 않습니다**.
  나중에 같은 timestamp 의 실제 샘플이 들어오면 실제 샘플(대기)로 바뀝니다.
- 서버로 보내는 경로는 장부의 전송 큐 하나뿐입니다. `자동 전송` 이 켜져 있으면 들어오는 대로,
  꺼져 있으면 장부 탭의 `지금 전송`을 누를 때 대기 행을 모두 보냅니다.
- 행마다 전송 여부, 전송 시각, 서버 status(`ok`/`out_of_range`), 서버 거부 횟수, 마지막 오류가 남습니다.
- 서버 응답이 아예 없으면(네트워크 끊김) 시도 횟수를 세지 않고 30초 뒤 다시 보냅니다.
  서버가 거부하면 횟수를 세고, 3번 거부된 행은 **실패**로 두고 자동으로 다시 보내지 않습니다.
  서버가 연속 5번 거부하면 key 문제로 보고 전송을 멈춥니다.

### 장부 탭

| 기능 | 설명 |
|---|---|
| 요약 | 전체 / 전송 완료 / 대기 / 실패 / 데이터 없음 건수, 데이터 범위, 마지막 전송 시각 |
| 필터 | 전체 · 대기 · 실패 · 완료 · 없음 (최신순 최대 500건) |
| 지금 전송 | 대기 행을 timestamp 순으로 모두 전송 |
| 실패 재시도 | 실패 행을 대기로 되돌려 다시 전송 |
| CSV 가져오기 | 수집 CSV(`ble_data_*.csv`)나 장부 CSV(`ledger.csv`)를 장부에 합침. 이미 있는 timestamp 는 건너뜀 |
| 장부 저장 | 장부 전체를 앱 폴더의 `ledger.csv` 로 덮어씀 (timestamp 순, 중복 없음) |
| 행 누르기 | 상세 보기. 대기·실패 행은 `전송 완료로 표시`, 실패 행은 `다시 시도`, 완료 행은 `다시 보내기`(경고 후), 데이터 없음 행은 `다시 요청`(기록을 지워 다음 수집 때 다시 요청) |

- 네트워크가 없을 때는 수집만 해 두면 장부에 대기로 쌓이고, 나중에 `지금 전송` 으로 보냅니다.
- 수집 CSV 를 가져올 때는 대상 장치 이름이 같은 유효 행만 들어가고, `upload_result` 가 `success…` 인 행은
  전송 완료로 들어갑니다.
- `ledger.csv` 는 "서버에 들어가 있어야 할 데이터" 목록입니다. 앱을 다시 설치하면 장부가 지워지므로,
  저장해 둔 `ledger.csv` 를 가져오면 전송 상태까지 복구되어 이미 보낸 데이터를 다시 보내지 않습니다.
- 요청이 서버에 도착했는데 응답만 끊긴 경우에는 다시 보내서 서버에 1건 중복될 수 있습니다.
  서버에 중복 처리가 없으면 앱에서는 막을 수 없습니다.

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
- `PostDataTest` — 5주차 PDF 48쪽 요청 양식(12개 필드), 49쪽 응답 status 파싱, 대상 장치 필터
- `LedgerCsvTest` — 수집 CSV·장부 CSV 를 장부 행으로 읽기(다른 장치·중복 제외, 데이터 없음은 전송 대상 아님으로 구분), 장부 CSV 저장·복구
