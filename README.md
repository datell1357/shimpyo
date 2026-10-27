# 쉼표, — 졸음쉼터 15분 휴식 앱 (Pleos Connect)

졸음이 오면 쉼터로, 쉼터에선 15분 꿀잠 — 차가 졸음쉼터 휴식의 처음부터 끝까지를 대신 챙겨 주는 차량용 앱입니다.

1. **쉼표 제안(주행 중, 음성)** — 연속 운전 시간과 시간대를 보고 진행 방향 앞쪽의 졸음쉼터를 음성으로 제안하고, "응" 한마디로 경유지에 넣습니다. 쉬기 직전 커피를 권합니다(카페인 냅). 쉼터 도착은 FusedLocation 근접 알림으로 판단합니다.
2. **쉼표 모드(쉼터, P단)** — 운전석 등받이를 젖히고, 외기 유입·온도를 맞추고, 창문을 살짝 열고(동승자가 있으면 그대로), 실내 조명을 낮추고, 문 잠김을 확인합니다. 문이 열리면 시간을 멈췄다가 닫으면 이어 갑니다.
3. **깨우기·출발 준비** — 빛 → 바람·시트 통풍 → 시트 원위치 → 알람 순서로 깨우고 "깨셨어요?"를 확인한 뒤, 시트·공조·창문·조명을 쉼표 전으로 되돌리고, 안전벨트를 확인하고 원래 목적지로 안내를 잇습니다.
4. **Gleo AI 음성 명령** — `assets/schema.json`의 함수 3개(`START_REST_BREAK`, `FIND_REST_AREA`, `END_REST_BREAK`)로 Gleo가 쉼표를 직접 부릅니다.
5. **쉼표 지킴이** — 포그라운드 서비스로 내비가 앞에 있어도 타이머·도착 판정이 멈추지 않고, 쉼표 상태를 저장해 앱이 꺼졌다 켜져도 이어 가거나 쉼표 전으로 되돌립니다.

차종마다 지원 기능을 먼저 확인(capability)해 있는 기능만 쓰고, 없는 기능은 음성 안내로 대신합니다.

## 실행 방법

### 준비

| 항목 | 버전 |
|---|---|
| Android Studio | 최신 버전 (JDK 17 이상, 이 저장소는 JBR 21로 빌드 확인) |
| Gradle / AGP / Kotlin | 8.13 / 8.13.2 / 2.2.21 (wrapper 포함) |
| compileSdk / targetSdk / minSdk | 36 / 34 / 28 |
| 에뮬레이터 | Pleos Connect Emulator v2.1.0 (Android 14, API 34) |
| Pleos Connect SDK | Vehicle 2.3.2, NaviHelper 3.0.5, FusedLocation 1.1.3, TextToSpeech 3.3.1, SpeechToText 3.3.1 |

Pleos Connect Emulator 설치는 [Pleos Connect Emulator 설치 및 실행](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)을 따릅니다.

### 빌드·설치

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

release APK: [`apk/Shimpyo_쉼표_release.apk`](apk/) (v0.2.1, R8 축소·서명). 직접 만들려면 `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword — 저장소에 올리지 않음)를 두고 `./gradlew assembleRelease`를 실행합니다.

### 테스트

```bash
./gradlew testDebugUnitTest
```

쉼터 찾기(진행 방향), 제안 기준(시간대·거절 뒤 재제안·최대 횟수), 연속 운전 계산(10분 정차 시 초기화), 깨우기 순서, 음성 대답 판별(제안·깨운 뒤), 쉼표 상태 저장·복원과 이어 가기 판단, 동승자 판단, Gleo 응답 문장을 검사합니다(15개).

## 테스트 환경과 시연 방법

1. Pleos Connect Emulator를 켜고 앱을 설치·실행합니다. 위치 권한을 허용합니다.
2. Virtual Console을 엽니다: `adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088
3. 위치를 경부고속도로 옥산 졸음쉼터 남쪽으로 둡니다(에뮬레이터 확장 컨트롤 → Location, 또는 `adb emu geo fix 127.334 36.588`).
4. 앱 오른쪽 위 설정 → **시연 모드**를 켭니다(3초 = 1분). 설정의 **운전 +60분**으로 연속 운전 시간을 앞당길 수 있습니다.
5. Virtual Console **Motion**에서 기어를 D로 두면 연속 운전 시간이 쌓이고, 기준(시간대별 1시간 20분~2시간)을 넘으면 쉼표가 음성으로 제안합니다.
6. "쉬어 갈게요"를 누르거나 "응"이라고 말하면 쉼터를 경유지로 넣습니다.
7. Virtual Console에서 기어를 **P**로 바꾸면 쉼표 모드가 시작되고, Virtual Console의 Convenience(시트·창문·조명)·Climate 카드에서 값이 바뀌는 것을 볼 수 있습니다.
8. 시간이 되면 깨우기가 진행되고 "깨셨으면 '네'라고 말해 주세요"라고 묻습니다. 화면의 **깼어요**를 누르거나 대답하면 출발 준비로 넘어갑니다(대답이 없으면 세 번 부른 뒤 넘어감). 기어를 D로 바꾸면 출발합니다.

### 더 시험해 볼 것

```bash
# Gleo AI 음성 명령 흉내(에뮬레이터에는 Gleo 음성 앞단이 없음) — 응답은 logcat 태그 Shimpyo/Gleo
adb shell am start -n app.shimpyo/.ui.MainActivity -a START_REST_BREAK --es ACTION_PARAM '{"minutes":15}'
adb shell am broadcast -a FIND_REST_AREA -p app.shimpyo --es ACTION_PARAM '{}'
adb shell am start -n app.shimpyo/.ui.MainActivity -a END_REST_BREAK --es ACTION_PARAM '{}'

# 쉼표 중 문 열기·닫기, 동승석 착석(Virtual Console HTTP API)
curl -X PUT 'http://127.0.0.1:8088/vehicle/body?door_fl=1'
curl -X PUT 'http://127.0.0.1:8088/vehicle/seat?kind=occupied&area=passenger&value=true'

# 쉼표 중 앱 강제 종료 → 쉼표 지킴이가 다시 떠서 남은 시간으로 이어 감
adb shell am crash app.shimpyo
```

차량이 아닌 기기(폰 에뮬레이터)에서는 가상 차량으로 실행되며, 설정 화면에서 기어·안전벨트를 바꿀 수 있습니다.

### 에뮬레이터에서 확인한 것 (Pleos Connect Emulator v2.1.0)

| 기능 | 결과 |
|---|---|
| 기어·속도 구독, 주행 규제(CarUxRestrictions) | ◯ 주행 중에는 설정 버튼이 숨겨짐 |
| FusedLocation 위치 → 앞쪽 졸음쉼터 계산 | ◯ |
| TextToSpeech(ON_DEVICE, 한국어) | ◯ 안내 음성 재생 |
| SpeechToText(ON_DEVICE) | ◯ 듣기 시작·종료 (에뮬레이터 마이크 입력은 미검증) |
| NaviHelper 경로 요청·취소 | ◯ Pleos 내비에 쉼터 경로 표시, 출발 시 경로 정리 |
| 등받이 젖힘·복귀 | ◯ Virtual Console 등받이 값 100 → 0 → 100 |
| 공조 전원·외기·온도·풍량 | ◯ 22℃·내기 → 21℃·외기 → 20℃·풍량 5 → 복원 |
| 운전석 창문, 앰비언트 조명, 문 잠김 | ◯ 창문 10%, 앰비언트 100% → 10% → 80% → 100% |
| 트립 운전 시간 | ✕ 에뮬레이터가 지원하지 않음(ERROR_NOT_SUPPORTED_PROPERTY) → 앱이 기어로 연속 운전 시간을 직접 계산 |
| FusedLocation 근접 알림 | ◯ 옥산 졸음쉼터 반경 350m 진입 이벤트로 도착 판정 |
| Gleo 음성 명령(Intent 흉내) | ◯ 시작·쉼터 찾기·깨우기, 응답 `APP_ACTION_RESPONSE`를 수 ms 안에 보냄 |
| 문 열림 | ◯ 쉼표 중 운전석 문 열림 → 남은 시간 그대로 멈춤 → 닫으면 이어 감 |
| 동승자 | ◯ 동승석 착석이면 창문 그대로·경고음 없음, 없으면 창문 10% |
| 쉼표 중 기어 D | ◯ 공조·창문·조명 즉시 복원. 등받이는 주행 기어에서 차량이 거부(오류 50001) → 안내 후 P단에서 0 → 100 |
| 앱 종료·백그라운드 | ◯ 설정 앱을 앞에 띄워도 FGS 상태로 동결 안 됨, `am crash` 뒤 서비스가 다시 떠 남은 시간으로 이어 감 → 깨우기·복원 |
| 깨운 뒤 확인 | ◯ "깼어요" 버튼·대답 없음 3회 → 출발 준비 |

시연할 때 알아 둘 점:
- Virtual Console 페이지가 열려 **활성 상태일 때만** 차량 값이 시뮬레이션됩니다(닫혀 있으면 `emulator_inactive`).
- 에뮬레이터 기본 GPS는 미국이라 `adb emu geo fix`로 한국 고속도로 위치를 넣어야 쉼터가 잡힙니다.
- 에뮬레이터 내비는 GPS와 별개로 자체 기본 위치(성남) 기준으로 경로를 그립니다.
- Vehicle SDK로 읽는 등받이 위치 값은 에뮬레이터에서 0으로 고정돼, 복귀는 젖힌 시간만큼 되돌리는 방식으로 동작합니다.
- 좌석 착석 값은 SDK가 원시값 1을 착석(true)으로 돌려주는데 AAOS에서 1은 빈 좌석이라, 에뮬레이터에서는 빈 좌석이 true·앉은 좌석이 false로 읽힙니다. 쉼표 중 반드시 앉아 있는 운전석 값을 "착석" 기준으로 삼아 판단합니다(콘솔에서 운전석 착석을 켜 두세요).
- 에뮬레이터는 `use_freezer=true`라 백그라운드 앱이 동결될 수 있습니다. 쉼표 지킴이(포그라운드 서비스, 위치 유형)가 이를 막습니다.
- Gleo 음성 연동은 Gleo 검토·승인 뒤 활성화됩니다. 에뮬레이터에는 Gleo 음성 앞단이 없어 Intent를 adb로 흉내 내 확인했습니다.

## 사용 권한

| 권한 | 용도 |
|---|---|
| `pleos.car.permission.CAR_DRIVE` | 기어, 트립 운전 시간 |
| `CAR_SEATS` / `CONTROL_CAR_SEATS` | 등받이 위치·이동, 시트 통풍, 안전벨트, 착석 |
| `CAR_CLIMATE` / `CONTROL_CAR_CLIMATE` | 공조 전원·온도·풍량·외기 |
| `CAR_WINDOWS` / `CONTROL_CAR_WINDOWS` | 운전석 창문 |
| `CAR_LIGHTS` / `CONTROL_CAR_LIGHTS` | 실내등, 앰비언트 조명 |
| `CAR_DOORS` / `CONTROL_CAR_DOORS` | 문 잠김 확인·잠금, 쉼표 중 문 열림 감지 |
| `CAR_ENERGY` | EV 배터리 잔량(낮으면 일찍 깨움) |
| `FUSED_LOCATION` | 차량 위치(다음 졸음쉼터 계산) |
| `NAVI_ROUTE` / `NAVI_ROUTE_SEARCH` | 경유지 추가·제거, 남은 거리·시간 |
| `TTS_SERVICE` / `STT_SERVICE` | 음성 안내, 짧은 대답 인식(ON_DEVICE 모드) |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` / `POST_NOTIFICATIONS` | 쉼표 지킴이(위치 유형 포그라운드 서비스)와 그 알림 |

차량 제어 권한(`CONTROL_CAR_*`)은 Pleos App Market과 협의·승인된 범위에서만 쓸 수 있습니다.

## 구조

```
app/src/main/
├── assets/schema.json   Gleo AI App Schema(함수 3개)
└── java/app/shimpyo/
    ├── TripService.kt  쉼표 지킴이(포그라운드 서비스)
    ├── core/      흐름(ShimpyoController), 쉼표 상태 저장(RestSession), 제안 기준, 연속 운전, 깨우기 순서, 졸음쉼터 검색, 기록, 주행 규제
    ├── vehicle/   Vehicle SDK 연동(PleosVehicleGateway), 가상 차량(SimVehicleGateway)
    ├── navi/      NaviHelper 경유지·도착
    ├── location/  FusedLocation 위치·근접 알림
    ├── voice/     TextToSpeech·SpeechToText (ON_DEVICE)
    ├── gleo/      Gleo AI 음성 명령 받기·응답
    └── ui/        Compose 화면
```

## 데이터·개인정보

- 졸음쉼터 위치: 공공데이터포털 「전국졸음쉼터표준데이터」(한국도로공사, 데이터기준일자 2026-02-25, 253곳)를 `app/src/main/assets/rest_areas.psv`에 내장 — 통신 없이 동작
- 휴식 기록은 차 안에만 저장하고 외부로 보내지 않습니다. 운전 행동 데이터를 마케팅에 쓰지 않습니다.
