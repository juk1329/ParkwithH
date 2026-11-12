# ParkwithH

**차가 주차장을 떠나는 순간, 그 사실을 다음 차에게 넘겨주는 층·구역 릴레이**

주차장 전체 잔여 대수는 일부만 공개되고, 층·구역 정보는 어디에도 없습니다. 이 앱은 기어 신호만으로 주차와 출차를 감지해 "지하 2층 B구역에서 방금 1대 나갔어요"라는 사실을 들어오는 차에 알립니다. 운전자는 앱을 열 필요가 없습니다. 앱은 화면에 없어도 동작하고, 음성과 알림으로 안내합니다.

사용자가 아무도 없을 때도 쓸모가 있습니다. 내비 목적지 주변의 큰 공영 주차장을 수용 대수와 함께 안내하고, 주차한 위치를 층·구역까지 저장합니다.

> **이 앱은 실제 차량에 설치된 적이 없습니다.** 아래 동작은 모두 Pleos Connect 에뮬레이터에서 확인한 것입니다.

| 항목 | 내용 |
|---|---|
| APK | [`apk/현재층은만차입니다_ParkwithH_release.apk`](apk/) (release 서명, 약 27MB) |
| 시연 영상 | [`demo/ParkwithH_demo.mp4`](demo/ParkwithH_demo.mp4) (2분 30초, 에뮬레이터 2대의 실제 동작 녹화. 기어·속도·위치·경로는 시험 도구로 넣었습니다) |
| 가장 빠른 확인 | 에뮬레이터 1대 + APK만으로 [데모 모드](#3-한-대로-보기-데모-모드-서버-불필요) 약 3분 |

## 실행 방법

화면이 있는 일반 PC·노트북(Windows, macOS, Linux)을 기준으로 씁니다. 화면이 없는 Linux 서버에서 실행하는 방법은 [5절](#5-화면이-없는-linux-서버에서-실행하기)에 있습니다.

### 1. 에뮬레이터 준비 (처음 한 번)

Pleos 공식 문서 [에뮬레이터 설치](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)와 [에뮬레이터 설정](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/configure-pleos-connect-emulator)을 요약했습니다. 공식 문서는 **그래픽 카드가 필수**라고 안내합니다.

1. Android Studio를 설치합니다.
2. **Tools → SDK Manager → SDK Update Sites** 탭에서 **+** 를 누르고 아래를 추가합니다.
   - Name: `Pleos Connect System Image`
   - URL (Windows·Linux): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - URL (Mac): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
3. **SDK Platforms** 탭에서 **Show Package Details**를 켜고, Android API Level 34의 **Pleos Connect system Image**를 체크해 설치한 뒤 Android Studio를 다시 시작합니다.
4. **Tools → Device Manager → Create device**에서 가상 기기를 만듭니다.
   - Form Factor **Automotive** → **Pleos Connect v2.1.0**
   - **Advanced Settings**: Startup의 Default boot를 **Cold**로, RAM을 **4 GB**로 설정합니다.
   - 시스템 이미지: API 34의 **Pleos Connect v2.1.0 APIs**
5. Device Manager에서 실행합니다. **Cold boot가 아니면 차량 신호(기어, 도어)가 앱에 들어오지 않습니다.**

아래 명령은 `adb`가 PATH에 있다고 가정합니다. 없다면 Android SDK의 `platform-tools` 폴더에서 실행하세요. 기본 위치는 다음과 같습니다.

| OS | `platform-tools` 기본 위치 |
|---|---|
| Windows | `%LOCALAPPDATA%\Android\Sdk\platform-tools` |
| macOS | `~/Library/Android/sdk/platform-tools` |
| Linux | `~/Android/Sdk/platform-tools` |

### 2. APK 설치와 Virtual Console 연결

저장소 루트에서 실행합니다.

```bash
adb install -r apk/현재층은만차입니다_ParkwithH_release.apk
adb forward tcp:8088 tcp:8088
```

- 브라우저에서 `http://127.0.0.1:8088/`을 열면 Virtual Console(차량 신호 조작 화면)이 나옵니다.
- **Emulation Controls** 왼쪽의 Domain에서 **Motion**을 고르면 기어(P·R·N·D)를 바꿀 수 있습니다. 도어는 **Convenience**에 있습니다.
- 에뮬레이터를 다시 켜면 `adb forward`도 다시 실행해야 합니다.

### 3. 한 대로 보기 (데모 모드, 서버 불필요)

처음 설치하면 데모 모드가 켜져 있습니다. 앱이 다른 차량과 서버를 흉내 내며, 서버로 아무것도 보내지 않습니다. 화면에는 항상 "데모 모드"라고 표시합니다. 주차장 이름과 면수는 공공 데이터의 실제 값이고, 다른 차량과 구역 이름은 예시입니다.

시작 전에 Virtual Console에서 기어를 **P**로 둡니다. 기어가 P가 아니면 앱은 주행 중 카드만 보여 주고 버튼을 숨깁니다.

| 순서 | 할 일 | 보이고 들리는 것 |
|---|---|---|
| 1 | 화면 아래 가운데의 **네모 4개 아이콘** → **앱** 탭 → **ParkwithH**(P 아이콘)를 열고 **동의하고 시작**을 누릅니다 | 권한 창이 3번 뜹니다. 위치는 **앱 사용 중에만 허용**, 알림과 "알 수 없는 작업 실행"은 **허용**을 누르세요. "알 수 없는 작업"은 Pleos 위치(FusedLocation) 권한이며, 에뮬레이터가 이름을 이렇게 표시합니다 |
| 2 | **1. 코엑스로 안내 시작** | 내비가 앞으로 나오고 코엑스 주차장 경로가 잡힙니다. 곧 "코엑스 주차장, 약 2,100면 규모예요"가 음성과 배너로 나옵니다 |
| 3 | 화면 아래에 생긴 **P 아이콘**(또는 1번의 앱 목록)으로 앱에 돌아가 **2. 10초 뒤 다른 차 출차**를 누르고, 화면 아래의 **화살표 아이콘**(내비게이션)으로 내비 화면에 돌아갑니다 | 앱이 화면에서 사라집니다 |
| 4 | 기다립니다 | 10초 뒤 "지하 2층 B구역에서 곧 나갈 차가 있어요", 6초 뒤 "지하 2층 B구역에서 방금 1대 나갔어요"가 음성과 배너로 나옵니다. 앱을 열지 않은 상태입니다 |
| 5 | Virtual Console에서 기어를 **R**로, 2초 뒤 **P**로 바꿉니다 | 몇 초 뒤 "지하 2층 B구역에 내 차 위치 저장" 배너가 뜹니다. **맞아요**를 누르면 앱을 열지 않고 저장됩니다. 앱을 열면 "내 차 위치"에 지하 2층 B구역이 보입니다 |

- 마이크가 연결돼 있으면 5번에서 "네"라고 답해도 됩니다. 에뮬레이터 **Extended Controls → Microphone**에서 호스트 오디오 입력을 켜야 합니다.
- 규모 안내는 목적지마다 한 번만 말합니다. 처음 상태로 다시 보려면 `adb uninstall app.parkrelay` 후 다시 설치하세요.
- 소리가 나지 않아도 같은 문장이 배너로 보입니다.

### 4. 서버와 차량 2대로 보기

실제 서버를 거쳐 한 차의 출차가 다른 차에 전달되는 장면입니다. 에뮬레이터 2대가 함께 돌아가므로 메모리가 넉넉해야 합니다(에뮬레이터당 RAM 4 GB).

**준비**

1. 1절과 같은 방법으로 가상 기기를 하나 더 만듭니다.
2. 서버를 켭니다. Python 3.10 이상이 필요합니다.
   ```bash
   pip install -r server/requirements.txt
   python -m uvicorn server.app:app --host 127.0.0.1 --port 8787
   ```
3. 두 에뮬레이터를 켜고 `adb devices`로 이름을 확인합니다. 먼저 켠 쪽이 보통 `emulator-5554`(차량 A), 다음이 `emulator-5556`(차량 B)입니다.
4. 두 대에 설치하고 Virtual Console을 연결합니다.
   ```bash
   adb -s emulator-5554 install -r apk/현재층은만차입니다_ParkwithH_release.apk
   adb -s emulator-5556 install -r apk/현재층은만차입니다_ParkwithH_release.apk
   adb -s emulator-5554 forward tcp:8088 tcp:8088   # 차량 A: http://127.0.0.1:8088/
   adb -s emulator-5556 forward tcp:8089 tcp:8088   # 차량 B: http://127.0.0.1:8089/
   ```
5. 두 대 모두 기어를 P로 두고 앱을 열어 동의와 권한을 허용한 뒤, **설정 → 데모 모드 끄기**를 누릅니다. 앱은 `http://10.0.2.2:8787`(에뮬레이터에서 본 이 PC)로 접속하고, 화면에 **시연 도구**가 나옵니다.
6. 운영사 대시보드를 브라우저로 엽니다: `http://127.0.0.1:8787/v1/lots/59446-29391-00005-00-1/dashboard`

**장면** (`adb emu geo fix`는 경도, 위도 순서입니다)

| 순서 | 차량 | 할 일 | 결과 |
|---|---|---|---|
| 1 | A | 앱에서 **1. 코엑스로 안내 시작** | A의 내비 목적지가 코엑스 주차장이 됩니다 |
| 2 | A | `adb -s emulator-5554 emu geo fix 127.058847 37.512432` 후 기어 **R → P** | "내 차 위치를 저장할까요?" 배너 → **층·구역 입력** → **B2** → **B**. 대시보드에 지하 2층 B구역, 앱 차량 1대가 나옵니다 |
| 3 | B | 앱에서 **1. 코엑스로 안내 시작**. 그대로 내비 화면에 둡니다 | "코엑스 주차장, 약 2,100면 규모예요" |
| 4 | A | 기어 **D** | B에서 앱을 열지 않은 채 "지하 2층 B구역에서 곧 나갈 차가 있어요" |
| 5 | A | `adb -s emulator-5554 emu geo fix 127.1500 37.6000` (주차장 밖으로 이동) | B에서 "지하 2층 B구역에서 방금 1대 나갔어요". 대시보드 원시 로그에 `LEAVING_SOON`, `VACATED`, `EXIT` |
| 6 | B | `adb -s emulator-5556 emu geo fix 127.058847 37.512432` 후 기어 **R → P** | "지하 2층 B구역에 내 차 위치 저장" 배너 → **맞아요**. B는 내비 화면 그대로이고, 대시보드에 B의 `PARK`가 찍힙니다 |

- 5번에서 위치를 옮기는 이유: 속도 신호가 있는 차는 15m 이상 움직이거나 주차장을 벗어나야 출차로 확정합니다. 잠깐 D에 넣었다가 다시 P로 돌아온 차를 출차로 잘못 알리지 않기 위해서입니다. Virtual Console에는 가속 페달이 없으므로 위치를 옮겨 주차장 이탈을 만듭니다.
- 같은 장면을 자동으로 재현하는 스크립트도 있습니다: `python tools/demo_e2e.py --install`. Linux 전용이며, 디버그 빌드와 `tools/emu.sh`로 켠 에뮬레이터 2대가 필요합니다(5절).

### 5. 화면이 없는 Linux 서버에서 실행하기

개발에 쓴 PC가 이 방식입니다. `tools/emu.sh`가 가상 디스플레이를 만들고 에뮬레이터를 Cold Boot로 켭니다. GPU가 없어도 되도록 기본은 소프트웨어 렌더링(`swiftshader_indirect`)입니다.

```bash
# emulator와 adb가 PATH에 있어야 합니다. 가상 기기 이름은 기본 Pleos_Veh1, Pleos_Veh2이며 AVD_1, AVD_2로 바꿀 수 있습니다.
tools/emu.sh xvfb                   # 가상 디스플레이 :77 생성
tools/emu.sh boot 1                 # emulator-5554, Virtual Console을 이 서버의 8088에 연결
tools/emu.sh boot 2                 # emulator-5556, 8089에 연결
EMU_GPU=host tools/emu.sh boot 1    # GPU가 있으면 하드웨어 렌더링
tools/emu.sh stop 1                 # 저장소를 동기화하고 끔
```

- **화면 보기:** NoMachine, VNC 같은 원격 데스크톱으로 디스플레이 `:77`에 접속합니다.
- **Virtual Console과 대시보드:** 서버에 브라우저가 없으면, 사용하는 노트북에서 SSH 터널을 연 뒤 노트북 브라우저로 `http://127.0.0.1:8088/`, `http://127.0.0.1:8089/`, `http://127.0.0.1:8787/…`을 엽니다.
  ```bash
  ssh -N -L 8088:127.0.0.1:8088 -L 8089:127.0.0.1:8089 -L 8787:127.0.0.1:8787 <사용자>@<서버 주소>
  ```
- **소리:** `-no-window`로 켜면 소리가 나지 않아서, `tools/emu.sh`는 가상 디스플레이에 창을 띄워 실행합니다. 소리는 서버의 오디오 장치로 나갑니다. 원격 데스크톱이 오디오를 전달하지 않으면 배너로 확인하세요.
- 소프트웨어 렌더링으로 오래 켜 두면 에뮬레이터가 스스로 종료될 때가 있습니다. 앱과 무관하며, `tools/emu.sh boot 1`로 다시 켜면 됩니다.
- 그 밖의 설치, 데모 모드, 차량 2대 순서는 2~4절과 같습니다.

### 6. 소스에서 빌드

```bash
./gradlew :app:assembleDebug      # JDK 17, Android SDK 35 필요
./gradlew :app:assembleRelease    # 저장소 루트에 keystore.properties가 있으면 서명
```

저장소에 서명 키는 없습니다. `keystore.properties` 형식은 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`입니다. Pleos SDK는 `settings.gradle.kts`에 적힌 공식 저장소에서 받습니다. 디버그 빌드와 release APK는 서명이 달라 서로 덮어 설치할 수 없습니다. 바꿀 때는 `adb uninstall app.parkrelay`를 먼저 실행하세요.

## 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator 2.1.0, API 34 시스템 이미지, emulator 37.2.13 |
| 부팅 | **Cold Boot** (`-no-snapshot`). Quick Boot에서는 차량 신호를 쓸 수 없습니다 |
| 확인한 PC | Ubuntu 24.04, 화면 없는 서버에서 가상 디스플레이와 소프트웨어 렌더링. Windows·macOS와 GPU 렌더링에서는 시험하지 않았습니다 |
| 소리 | 창 모드에서만 나옵니다. `-no-window`는 오디오가 동작하지 않습니다 |
| 음성 입력 | Extended Controls → Microphone에서 호스트 오디오 입력 켜기(명령줄은 `-allow-host-audio`). 입력 음량이 작으면 말소리로 인식되지 않습니다 |
| 런타임 권한 | 위치, 알림, `pleos.car.permission.FUSED_LOCATION`("알 수 없는 작업"으로 표시), `android.car.permission.CAR_SPEED`. 첫 실행 때 묻습니다 |
| 차량 신호 | Virtual Console: `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/` |
| 위치 | `adb emu geo fix <경도> <위도>` |
| 앱 실행 사용자 | Android user 10 (`adb shell am start --user 10 -n app.parkrelay/.MainActivity`) |
| 패키지 이름 | `app.parkrelay` (개발 초기 이름을 유지했습니다) |
| 서버 | Python 3.10 이상, FastAPI. 에뮬레이터에서는 `10.0.2.2:8787`로 접속 |

### 문제 해결

| 증상 | 확인할 것 |
|---|---|
| 기어를 바꿔도 앱이 반응하지 않음 | 가상 기기를 Cold Boot로 켰는지 확인하세요 |
| Virtual Console 페이지가 열리지 않음 | `adb devices`로 연결을 확인하고 `adb forward`를 다시 실행하세요. 8088 포트를 다른 프로그램이 쓰고 있지 않은지도 확인하세요 |
| 앱에 버튼과 설정이 보이지 않음 | 기어가 P가 아니면 주행 중 카드만 보입니다. P로 바꾸세요 |
| 설치할 때 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 서명이 다른 빌드가 설치돼 있습니다. `adb uninstall app.parkrelay` 후 다시 설치하세요 |
| 처음 켤 때 "어느 주차장인가요?" 알림 | 에뮬레이터의 마지막 위치가 주차장 근처라서 뜹니다. 무시해도 됩니다 |
| 규모 안내가 다시 나오지 않음 | 목적지마다 한 번만 말합니다. 앱을 지우고 다시 설치하면 처음부터 볼 수 있습니다 |
| 서버 모드에서 소식이 오지 않음 | 서버가 8787 포트에서 실행 중인지, 두 차 모두 데모 모드를 껐는지, 같은 주차장(코엑스)으로 안내 중인지 확인하세요 |
| 출차가 확정되지 않음 | 4절 5번처럼 위치를 주차장 밖으로 옮기세요 |

## APK 위치

`apk/현재층은만차입니다_ParkwithH_release.apk` (release 서명, 버전 0.1.0)

## 사용한 SDK와 확인 상태

공모전이 지정한 SDK 5종을 모두 핵심 기능에 씁니다. 버전은 Pleos Connect SDK 릴리스 노트(2026-10-19)의 배포 버전입니다. 아래 확인 결과는 에뮬레이터에서 앱이 화면에 없는 상태로 직접 확인한 것입니다.

| SDK | 버전 | 쓰임 | 에뮬레이터 확인 |
|---|---|---|---|
| Vehicle | 2.3.2 | 운전석·동승석 도어, 운전석 탑승으로 주차와 하차 대기 구분 | 값 수신. 에뮬레이터는 탑승값이 반전되어 보정 |
| NaviHelper | 3.0.5 | 목적지로 주차장 특정, 주차장으로 경로 전달 | 경로 요청, 안내 시작, 목적지 수신 |
| FusedLocation | 1.1.3 | 마지막 위치, 세션 이탈·도착 근접 알림 | 위치, ENTER·EXIT 수신. 부팅 후에도 동작 |
| TextToSpeech | 3.3.1 | 주 안내 경로 (`ON_DEVICE`) | 실제 소리 출력 측정 |
| SpeechToText | 3.3.1 | 층·구역 음성 입력 (`ON_DEVICE`) | 온디바이스 한국어 문장 인식 |

Pleos의 [프로젝트 생성 및 SDK 사용 신청](https://document.pleos.ai/docs/pleos-only/create-my-project) 안내에는 FusedLocation·TextToSpeech·SpeechToText의 Playground 사용 신청이 "준비 중"으로 적혀 있습니다(2026-11-06 확인). 이 앱은 권한 선언과 온디바이스 모드만 쓰므로 Playground 신청이나 앱 등록 없이 동작합니다.

기어와 속도는 Pleos 문서의 API 선택 기준에 따라 AAOS 표준 `CarPropertyManager`(`GEAR_SELECTION`, `PERF_VEHICLE_SPEED`)를 씁니다.

**실제 차량과 다를 수 있는 것**

- 헤드업 알림은 `CATEGORY_NAVIGATION`일 때 표시되는 것을 에뮬레이터에서 확인했습니다. 양산차의 정책은 확인하지 못했습니다.
- 실제 Suspend는 에뮬레이터로 재현할 수 없어 전원 차단으로 시험했습니다.
- 공식 창 정책에서 2:1·1:2 비율은 와이드 디스플레이(Connect-W)용입니다. 이 에뮬레이터는 Connect-S 정책대로 2:0·1:1만 제공하므로, 좁은 창은 창 크기를 강제로 줄여 확인했습니다. 공식 폼 팩터 테스트 도구로는 아직 시험하지 않았습니다.

## 구조

```
core/     순수 Kotlin. 세션 상태 머신, 주차장 특정, 공유 등급, 보드 문구, 음성 문장 파서
app/      Android. 서비스, 신호 소스, 음성, 알림, 화면, 오프라인 큐, 데모 백엔드
server/   FastAPI + SQLite + SSE. 이벤트, 출차 토큰, 구역 보드, 운영사 대시보드
tools/    카탈로그 생성, 에뮬레이터 제어, E2E·복구 시험
```

- 위치 판단은 차 안에서 끝납니다. 이동 경로, 차대번호, 번호판은 서버로 보내지 않으며, 세션마다 새 ID를 씁니다.
- 주거 시설로 보이는 주차장에서는 세션을 만들지 않고 아무것도 보내지 않습니다.
- 출차는 "빈자리 보장"이 아니라 "방금 1대가 나갔다"는 사실로만 안내합니다. 앱은 이 앱을 쓰는 차만 볼 수 있습니다.

## 테스트

```bash
./gradlew :core:test
python -m pytest server/tests tools/tests     # ROS 등으로 PYTHONPATH가 잡힌 환경은 env -u PYTHONPATH
python tools/demo_e2e.py --install            # 에뮬레이터 2대, 라이브 장면과 오탐 차단
python tools/recovery_e2e.py                  # 전원 차단, 통신 불가, 부팅 중 출발
```

## 공공 데이터 출처

공공데이터포털(data.go.kr), 이용허락범위 제한 없음.

| 데이터 | 기준일 | 쓰임 |
|---|---|---|
| 한국교통안전공단_주차장시설정보 | 2023-05-10 | 주차장 이름, 좌표, 면수. 가공해 `app/src/main/assets/lots.tsv`로 포함 |
| 한국교통안전공단_주차장실시간정보 | 2026-07-26 | 공개 잔여값의 공백 분석에만 사용. 차 안 화면에는 쓰지 않음 |

면수는 규모일 뿐 현재 빈자리가 아닙니다. 원본 CSV는 저장소에 포함하지 않으며, 재생성 방법은 `tools/build_lot_catalog.py`에 있습니다.

## 오픈소스와 라이선스

| 구성 요소 | 라이선스 |
|---|---|
| Kotlin, Android Gradle Plugin, AndroidX Core | Apache-2.0 |
| FastAPI, Pydantic | MIT |
| Uvicorn, HTTPX | BSD-3-Clause |
| pytest | MIT |
| JUnit 5 | EPL-2.0 |

Pleos Connect SDK 5종은 42dot SDK 라이선스를 따르며, SDK 바이너리는 이 저장소에 포함하지 않습니다. 앱 이름과 아이콘에는 Pleos·42dot 상표를 쓰지 않습니다.
