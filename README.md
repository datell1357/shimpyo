# 쉼표, — 졸음쉼터 15분 휴식 앱 (Pleos Connect)

졸음이 오면 쉼터로, 쉼터에선 15분 꿀잠 — 차가 졸음쉼터 휴식의 처음부터 끝까지를 대신 챙겨 주는 차량용 앱입니다.

1. **쉼표 제안(주행 중, 음성)** — 연속 운전 시간과 시간대를 보고 진행 방향 앞쪽의 졸음쉼터를 음성으로 제안하고, "응" 한마디로 경유지에 넣습니다.
2. **쉼표 모드(쉼터, P단)** — 운전석 등받이를 젖히고, 외기 유입·온도를 맞추고, 창문을 살짝 열고, 실내 조명을 낮추고, 문 잠김을 확인합니다.
3. **깨우기·출발 준비** — 빛 → 바람·시트 통풍 → 시트 원위치 → 알람 순서로 깨우고, 시트·공조·창문·조명을 쉼표 전으로 되돌린 뒤, 안전벨트를 확인하고 원래 목적지로 안내를 잇습니다.

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

release APK는 `apk/` 폴더에 있습니다. 직접 만들려면 저장소 밖에 `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)를 두고 `./gradlew assembleRelease`를 실행합니다.

### 테스트

```bash
./gradlew testDebugUnitTest
```

쉼터 찾기(진행 방향), 제안 기준(시간대·거절 뒤 재제안·최대 횟수), 연속 운전 계산(10분 정차 시 초기화), 깨우기 순서, 음성 대답 판별을 검사합니다.

## 테스트 환경과 시연 방법

1. Pleos Connect Emulator를 켜고 앱을 설치·실행합니다. 위치 권한을 허용합니다.
2. Virtual Console을 엽니다: `adb forward tcp:8088 tcp:8088` 후 http://127.0.0.1:8088
3. 위치를 경부고속도로 옥산 졸음쉼터 남쪽으로 둡니다(에뮬레이터 확장 컨트롤 → Location, 또는 `adb emu geo fix 127.334 36.588`).
4. 앱 오른쪽 위 설정 → **시연 모드**를 켭니다(3초 = 1분). 설정의 **운전 +60분**으로 연속 운전 시간을 앞당길 수 있습니다.
5. Virtual Console **Motion**에서 기어를 D로 두면 연속 운전 시간이 쌓이고, 기준(시간대별 1시간 20분~2시간)을 넘으면 쉼표가 음성으로 제안합니다.
6. "쉬어 갈게요"를 누르거나 "응"이라고 말하면 쉼터를 경유지로 넣습니다.
7. Virtual Console에서 기어를 **P**로 바꾸면 쉼표 모드가 시작되고, Virtual Console의 Convenience(시트·창문·조명)·Climate 카드에서 값이 바뀌는 것을 볼 수 있습니다.
8. 시간이 되면 깨우기가 진행되고, Safety에서 안전벨트를 풀었다 매면 출발 안내가 나옵니다.

차량이 아닌 기기(폰 에뮬레이터)에서는 가상 차량으로 실행되며, 설정 화면에서 기어·안전벨트를 바꿀 수 있습니다.

## 사용 권한

| 권한 | 용도 |
|---|---|
| `pleos.car.permission.CAR_DRIVE` | 기어, 트립 운전 시간 |
| `CAR_SEATS` / `CONTROL_CAR_SEATS` | 등받이 위치·이동, 시트 통풍, 안전벨트, 착석 |
| `CAR_CLIMATE` / `CONTROL_CAR_CLIMATE` | 공조 전원·온도·풍량·외기 |
| `CAR_WINDOWS` / `CONTROL_CAR_WINDOWS` | 운전석 창문 |
| `CAR_LIGHTS` / `CONTROL_CAR_LIGHTS` | 실내등, 앰비언트 조명 |
| `CAR_DOORS` / `CONTROL_CAR_DOORS` | 문 잠김 확인·잠금 |
| `CAR_ENERGY` | EV 배터리 잔량(낮으면 일찍 깨움) |
| `FUSED_LOCATION` | 차량 위치(다음 졸음쉼터 계산) |
| `NAVI_ROUTE` / `NAVI_ROUTE_SEARCH` | 경유지 추가·제거, 남은 거리·시간 |
| `TTS_SERVICE` / `STT_SERVICE` | 음성 안내, 짧은 대답 인식(ON_DEVICE 모드) |

차량 제어 권한(`CONTROL_CAR_*`)은 Pleos App Market과 협의·승인된 범위에서만 쓸 수 있습니다.

## 구조

```
app/src/main/java/app/shimpyo/
├── core/      흐름(ShimpyoController), 제안 기준, 연속 운전, 깨우기 순서, 졸음쉼터 검색, 기록, 주행 규제
├── vehicle/   Vehicle SDK 연동(PleosVehicleGateway), 가상 차량(SimVehicleGateway)
├── navi/      NaviHelper 경유지·도착
├── location/  FusedLocation
├── voice/     TextToSpeech·SpeechToText (ON_DEVICE)
└── ui/        Compose 화면
```

## 데이터·개인정보

- 졸음쉼터 위치: 공공데이터포털 「전국졸음쉼터표준데이터」(한국도로공사, 데이터기준일자 2026-02-25, 253곳)를 `app/src/main/assets/rest_areas.psv`에 내장 — 통신 없이 동작
- 휴식 기록은 차 안에만 저장하고 외부로 보내지 않습니다. 운전 행동 데이터를 마케팅에 쓰지 않습니다.
