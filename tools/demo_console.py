#!/usr/bin/env python
"""The two-car demo on one page: car A leaves a zone, and car B, a hundred metres from the lot, is
told the list of zones, says where it is heading, and parks with one tap.

    python tools/demo_console.py             # with two Pleos emulators already running
    python tools/demo_console.py --install   # also installs apk/*_release.apk on both

It waits for two emulators, starts the server on port 8787 unless one is running, opens the app on
both cars in server mode, and serves the console at http://127.0.0.1:8790/. The console moves each
car's gear through its Virtual Console and its location with `adb emu geo fix`, so nothing has to be
typed while the scene runs. Works on Windows, macOS and Linux. Needs adb and the server's packages.

Car A is the emulator with the lower port (usually emulator-5554) and its Virtual Console is
forwarded to 8088. Car B is the other one, on 8089. Everything listens on 127.0.0.1 only, and a
request can only pick among fixed choices: nothing it sends reaches a command line.

Two emulators cannot fill a list, so the console can also post the events of cars that do not
exist ("virtual cars"): a departure on another floor, a car ahead heading for it, a car that parks
where car B was going. The page labels them as such, and their content is fixed in this file.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import webbrowser
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import vc  # noqa: E402  Retries a console request and reads a gear back.

PACKAGE = "app.parkrelay"
ACTIVITY = f"{PACKAGE}/.MainActivity"
NAVIGATION = "ai.umos.maps.android.navigation.app/.MainActivity"
DRIVER_USER = "10"  # Pleos runs the driver's apps as Android user 10.
PERMISSIONS = (
    "pleos.car.permission.FUSED_LOCATION",
    "android.car.permission.CAR_SPEED",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
)
SERVER = "http://127.0.0.1:8787"
CONSOLE_PORT = 8790
CONSOLE_PORTS = (8088, 8089)
LOT_ID = "59446-29391-00005-00-1"  # 코엑스 주차장, where the app's route button heads.
PLACES = {
    "lot": (37.512432, 127.058847),  # The lot's catalog point.
    # The catalog gives this lot a radius of 300 m. This point is 380 m south of its centre, so 80 m
    # short of its edge: inside the hundred metres at which the app tells the list.
    "near": (37.509018, 127.058847),
    "away": (37.6000, 127.1500),  # Kilometres from any lot in the catalog.
}
PLACE_NAMES = {"lot": "코엑스 주차장 안", "near": "주차장 100m 앞", "away": "주차장 밖"}
MINUTE_MS = 60_000
DEPARTURE_PAUSE_SECONDS = 5  # Long enough to see "곧 1곳" on the dashboard before it becomes "1곳".
API_VERSION = 2
GEARS = {"P": "Park", "R": "Reverse", "N": "Neutral", "D": "Drive"}
SETTLE_SECONDS = 2  # The app counts a P right after R as parking, and wants the R seen first.
WAIT_FOR_CARS_SECONDS = 600


def find_adb() -> str:
    """PATH first, then the SDK folder Android Studio uses on each system."""
    found = shutil.which("adb")
    if found:
        return found
    homes = [os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    if sys.platform == "win32":
        homes.append(os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk"))
    elif sys.platform == "darwin":
        homes.append(os.path.expanduser("~/Library/Android/sdk"))
    else:
        homes.append(os.path.expanduser("~/Android/Sdk"))
    name = "adb.exe" if sys.platform == "win32" else "adb"
    for home in filter(None, homes):
        candidate = Path(home) / "platform-tools" / name
        if candidate.is_file():
            return str(candidate)
    sys.exit("adb를 찾지 못했습니다. Android SDK의 platform-tools 폴더를 PATH에 추가하세요.")


ADB = ""


def run_adb(*args: str, timeout: float = 60) -> subprocess.CompletedProcess:
    return subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout)


@dataclass
class Car:
    name: str
    serial: str
    console: int
    place: str = "?"
    ready_seen: float = 0.0
    ready: bool = False

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.console}"

    def adb(self, *args: str, timeout: float = 60) -> str:
        return run_adb("-s", self.serial, *args, timeout=timeout).stdout

    def shell(self, *args: str) -> str:
        # Every argument is a constant from this file, so nothing needs quoting for the device shell.
        return self.adb("shell", *args)

    def gear(self, letter: str) -> None:
        status, body = vc.send(self.base, f"/vehicle/gear?gear={GEARS[letter]}", "GET")
        if not 200 <= status < 300:
            raise RuntimeError(f"차량 {self.name}의 기어를 {letter}로 바꾸지 못했습니다 ({status} {body[:120]})")

    def move(self, place: str) -> None:
        lat, lng = PLACES[place]
        self.adb("emu", "geo", "fix", str(lng), str(lat))
        self.place = place

    def actual_gear(self) -> str | None:
        value = vc.actual_gear(self.base)
        return next((letter for letter, name in GEARS.items() if name == value), None)

    def app_running(self) -> bool:
        """The service starts only after the driver agrees, so a running service means the car is ready.

        Asked at most every five seconds: the page polls often and adb is slow.
        """
        if time.time() - self.ready_seen > 5:
            self.ready = "ParkingService" in self.shell("dumpsys", "activity", "services", PACKAGE)
            self.ready_seen = time.time()
        return self.ready


@dataclass
class Console:
    cars: dict[str, Car]
    lock: threading.Lock = field(default_factory=threading.Lock)
    last_scene: int = 0
    note: str = ""

    def park(self, car: Car) -> None:
        car.gear("D")
        car.move("lot")
        car.gear("R")
        time.sleep(SETTLE_SECONDS)
        car.gear("P")

    def scene(self, number: int) -> str:
        a, b = self.cars["A"], self.cars["B"]
        if number == 1:
            self.park(a)
        elif number == 2:
            b.move("away")
            b.gear("D")
            b.shell("am", "start", "--user", DRIVER_USER, "-n", NAVIGATION)
        elif number == 3:
            # The gear leaving P is "about to leave". Leaving the lot confirms it: the Virtual Console
            # page has no accelerator, and the app wants 15 m of movement or the car out of the lot.
            a.gear("D")
            time.sleep(DEPARTURE_PAUSE_SECONDS)
            a.move("away")
        elif number == 4:
            self.virtual("seed")
        elif number == 5:
            b.gear("D")
            b.move("near")
        elif number == 6:
            self.park(b)
        self.last_scene = number
        return SCENES[number - 1]["done"]

    def virtual(self, what: str) -> str:
        """Posts what cars that are not emulators would have sent. Every field is fixed here."""
        now = int(time.time() * 1000)
        events, note = VIRTUAL[what]
        post_json(f"{SERVER}/v1/events", {"events": [
            {"eventId": f"{session}-{type_}-{now}", "sessionId": session, "type": type_, "lotId": LOT_ID,
             "zoneId": zone, "occurredAt": now - ago}
            for type_, zone, session, ago in events
        ]})
        return note

    def reset(self) -> str:
        """Both cars drive away from the lot and stop there, then the server forgets the last run."""
        for car in self.cars.values():
            car.gear("D")
            car.move("away")
        time.sleep(4)  # A parked car's departure reaches the server before the board is cleared.
        for car in self.cars.values():
            car.gear("P")
        post(f"{SERVER}/v1/admin/reset")
        self.last_scene = 0
        return "두 차 모두 주차장 밖에 P로 섰고, 서버 기록을 지웠습니다."

    def status(self) -> dict:
        cars = {}
        for name, car in self.cars.items():
            cars[name] = {
                "serial": car.serial,
                "console": car.console,
                "gear": car.actual_gear(),
                "place": car.place,
                "appReady": car.app_running(),
            }
        try:
            board = get_json(f"{SERVER}/v1/lots/{LOT_ID}/board")
        except OSError:
            board = None
        return {"cars": cars, "board": board, "lastScene": self.last_scene, "note": self.note,
                "busy": self.lock.locked(), "dashboard": f"{SERVER}/v1/lots/{LOT_ID}/dashboard"}


# Cars that exist only as events: (type, zone, session, how long ago in ms).
VIRTUAL = {
    "seed": ([
        ("VACATED", "B1-A", "virtual-left-b1", 2 * MINUTE_MS),
        ("VACATED", "B3-C", "virtual-left-b3", 4 * MINUTE_MS),
        ("CLAIM", "B1-A", "virtual-ahead", 20_000),
    ], "가상 차량: 지하 1층 A구역과 지하 3층 C구역에서 1대씩 나갔고, 앞차 1대가 지하 1층 A구역으로 가는 중입니다."),
    "take": ([
        ("PARK", "B2-B", "virtual-parker", 0),
    ], "가상 차량: 다른 차가 지하 2층 B구역에 먼저 주차했습니다. B차가 그 구역으로 가던 중이면 다른 구역을 안내받습니다."),
}

# The page shows these in order. "screen" is what to do on the emulator, "done" what should follow.
SCENES = [
    {"car": "A", "title": "A차가 코엑스 주차장에 주차해요",
     "button": "A차 주차 (후진 → P)",
     "screen": "A차 화면 위에 \"내 차 위치를 저장할까요?\"가 뜨면 [층·구역 입력]을 누르고 B2, B를 차례로 고르세요. "
               "다시 할 때는 앱이 지난 구역을 기억해 \"지하 2층 B구역에 내 차 위치 저장\"이 뜨니 [맞아요]를 누르세요.",
     "done": "A차가 코엑스 주차장 안에서 R → P로 바뀌었습니다. A차 화면에서 층·구역을 입력하세요."},
    {"car": "B", "title": "B차가 코엑스로 출발해요",
     "button": "B차 출발 (기어 D)",
     "screen": "처음 한 번은 먼저 B차 화면의 ParkwithH에서 [1. 코엑스로 안내 시작]을 누르고 이 버튼을 누르세요. "
               "\"코엑스 주차장, 약 2,100면 규모예요\"가 들립니다. 안내는 목적지마다 한 번만 나오고, 경로는 다시 할 때도 남아 있습니다.",
     "done": "B차가 D로 바뀌고 내비 화면으로 돌아갔습니다."},
    {"car": "A", "title": "A차가 나가요",
     "button": "A차 출차 (기어 D → 주차장 밖)",
     "screen": "A차 기어가 P를 벗어나면 아래 목록에 \"지하 2층 B구역 곧 1곳\"이, 몇 초 뒤 주차장을 벗어나면 \"1곳\"이 됩니다. "
               "B차는 아직 멀리 있어서 아무 말도 하지 않습니다. 목록만 조용히 바뀝니다.",
     "done": "A차가 기어를 D로 바꾸고 주차장을 벗어났습니다. 목록에 지하 2층 B구역 1곳이 생겼습니다."},
    {"car": "가상", "title": "다른 차들도 움직여요 (가상 차량)",
     "button": "가상 차량 넣기",
     "screen": "에뮬레이터 2대로는 목록을 채울 수 없어, 콘솔이 다른 차들의 이벤트를 서버에 대신 보냅니다. "
               "지하 1층 A구역과 지하 3층 C구역에서 1대씩 나갔고, B차보다 앞선 차 1대가 지하 1층 A구역으로 간다고 알렸습니다.",
     "done": VIRTUAL["seed"][1]},
    {"car": "B", "title": "B차가 주차장 100m 앞에 와요",
     "button": "B차 위치를 100m 앞으로",
     "screen": "앱을 열지 않은 B차가 층별 주차 가능 구역을 음성과 배너로 받습니다. 앞차가 가는 지하 1층 A구역은 건너뛰고 "
               "\"지하 2층 B구역으로 간다고 알렸어요\"라고 말한 뒤, 목록에 \"가는 중 1대\"가 붙습니다. "
               "마이크가 있으면 이때 다른 구역 이름이나 \"취소\"를 말해 바꿀 수 있습니다.",
     "done": "B차 위치를 주차장 경계 80m 앞으로 옮겼습니다. B차 화면의 배너와 음성을 확인하세요."},
    {"car": "B", "title": "B차가 주차해요",
     "button": "B차 주차 (후진 → P)",
     "screen": "B차 화면 위 \"지하 2층 B구역에 내 차 위치 저장\"에서 [맞아요]를 누르세요. 앱을 열지 않고 저장되고, "
               "목록에서 지하 2층 B구역 1곳과 \"가는 중\"이 함께 사라집니다.",
     "done": "B차가 코엑스 주차장 안에서 R → P로 바뀌었습니다. B차 화면의 배너에서 [맞아요]를 누르세요."},
]


def get_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.loads(response.read().decode("utf-8"))


def post(url: str) -> None:
    request = urllib.request.Request(url, method="POST", data=b"")
    with urllib.request.urlopen(request, timeout=10):
        pass


def post_json(url: str, payload: dict) -> None:
    request = urllib.request.Request(url, method="POST", data=json.dumps(payload).encode("utf-8"),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10):
        pass


def server_is_current() -> bool:
    """A server left running from an older checkout answers the board but knows nothing of the list."""
    try:
        return get_json(f"{SERVER}/v1/health").get("api", 0) >= API_VERSION
    except (OSError, ValueError):
        return False


def server_alive() -> bool:
    try:
        get_json(f"{SERVER}/v1/lots/{LOT_ID}/board")
        return True
    except (OSError, ValueError):
        return False


def start_server() -> subprocess.Popen | None:
    """Starts the server unless one already answers. Returns the process this script owns, if any."""
    if server_alive():
        if not server_is_current():
            sys.exit("8787 포트에 예전 버전의 서버가 실행 중입니다. 그 서버를 끄고 다시 실행하세요.")
        print("서버: 이미 8787에서 실행 중입니다.")
        return None
    missing = [name for name in ("fastapi", "uvicorn") if importlib.util.find_spec(name) is None]
    if missing:
        sys.exit(f"서버 패키지가 없습니다({', '.join(missing)}). 먼저 실행하세요: "
                 f"{Path(sys.executable).name} -m pip install -r server/requirements.txt")
    log = Path(tempfile.gettempdir()) / "parkwithh-server.log"
    process = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "server.app:app", "--host", "127.0.0.1", "--port", "8787"],
        cwd=ROOT, stdout=log.open("w", encoding="utf-8"), stderr=subprocess.STDOUT)
    for _ in range(40):
        if server_alive():
            print(f"서버: 8787에서 시작했습니다. 기록은 {log}")
            return process
        if process.poll() is not None:
            break
        time.sleep(0.5)
    sys.exit(f"서버가 시작되지 않았습니다. {log}를 확인하세요.")


def booted_emulators() -> list[str]:
    serials = []
    for line in run_adb("devices").stdout.splitlines():
        match = re.match(r"^(emulator-(\d+))\s+device$", line.strip())
        if match and run_adb("-s", match.group(1), "shell", "getprop", "sys.boot_completed",
                             timeout=20).stdout.strip() == "1":
            serials.append(match.group(1))
    return sorted(serials, key=lambda serial: int(serial.split("-")[1]))


def wait_for_cars() -> list[str]:
    deadline = time.time() + WAIT_FOR_CARS_SECONDS
    shown = -1
    while time.time() < deadline:
        serials = booted_emulators()
        if len(serials) >= 2:
            if len(serials) > 2:
                print(f"에뮬레이터가 {len(serials)}대입니다. 앞의 두 대를 씁니다.")
            return serials[:2]
        if len(serials) != shown:
            print(f"부팅을 마친 에뮬레이터 2대를 기다리는 중입니다 (지금 {len(serials)}대).")
            shown = len(serials)
        time.sleep(3)
    sys.exit("에뮬레이터 2대가 준비되지 않았습니다. Android Studio의 Device Manager에서 두 AVD를 켜세요.")


def release_apk() -> Path:
    found = sorted((ROOT / "apk").glob("*_release.apk"))
    if not found:
        sys.exit("apk 폴더에 release APK가 없습니다.")
    return found[0]


def install(car: Car, apk: Path) -> None:
    """A build signed with another key cannot replace this one, so it is removed first when that happens."""
    for _ in range(2):
        result = run_adb("-s", car.serial, "install", "-r", str(apk), timeout=240)
        if "Success" in result.stdout:
            print(f"차량 {car.name}: {apk.name} 설치")
            return
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" not in result.stdout + result.stderr:
            break
        print(f"차량 {car.name}: 서명이 다른 앱이 있어 지우고 다시 설치합니다.")
        run_adb("-s", car.serial, "uninstall", PACKAGE)
    sys.exit(f"차량 {car.name}에 설치하지 못했습니다: {(result.stdout + result.stderr).strip()[-300:]}")


def prepare(car: Car, apk: Path, reinstall: bool) -> None:
    listed = car.shell("pm", "list", "packages", "--user", DRIVER_USER, PACKAGE)
    if reinstall or f"package:{PACKAGE}" not in listed:
        install(car, apk)
    for permission in PERMISSIONS:  # What a driver would allow on the first run, so no dialog interrupts.
        car.shell("pm", "grant", "--user", DRIVER_USER, PACKAGE, permission)
    forwarded = run_adb("-s", car.serial, "forward", f"tcp:{car.console}", "tcp:8088")
    if forwarded.returncode != 0:
        sys.exit(f"차량 {car.name}의 Virtual Console을 {car.console} 포트로 연결하지 못했습니다: {forwarded.stderr.strip()}")
    car.shell("am", "start", "--user", DRIVER_USER, "-n", ACTIVITY, "--es", "mode", "server")
    print(f"차량 {car.name}: {car.serial}, Virtual Console http://127.0.0.1:{car.console}/, 앱을 서버 모드로 열었습니다.")


def keep_consoles_awake(cars: list[Car]) -> None:
    """The Virtual Console ignores changes unless a browser session is alive, so this stands in for one."""
    def beat() -> None:
        while True:
            for car in cars:
                vc.call(car.base, f"/browser/session?client=demo-console-{car.console}", "POST")
            time.sleep(1)

    threading.Thread(target=beat, daemon=True).start()
    time.sleep(1.5)


ROUTES = [
    (re.compile(r"^/api/car/([AB])/gear/([PRND])$"), lambda c, m: c.cars[m[1]].gear(m[2]) or
     f"차량 {m[1]} 기어를 {m[2]}로 바꿨습니다."),
    (re.compile(r"^/api/car/([AB])/place/(lot|near|away)$"), lambda c, m: c.cars[m[1]].move(m[2]) or
     f"차량 {m[1]} 위치를 {PLACE_NAMES[m[2]]}으로 옮겼습니다."),
    (re.compile(r"^/api/scene/([1-6])$"), lambda c, m: c.scene(int(m[1]))),
    (re.compile(r"^/api/virtual/(seed|take)$"), lambda c, m: c.virtual(m[1])),
    (re.compile(r"^/api/reset$"), lambda c, m: c.reset()),
]


def handler_for(console: Console, port: int) -> type[BaseHTTPRequestHandler]:
    hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args) -> None:
            pass

        def allowed(self) -> bool:
            # A site that resolves its own name to 127.0.0.1 still sends its own name as the Host.
            return self.headers.get("Host") in hosts

        def reply(self, status: int, body: bytes, kind: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def reply_json(self, status: int, payload: dict) -> None:
            self.reply(status, json.dumps(payload, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

        def do_GET(self) -> None:
            if not self.allowed():
                return self.reply_json(403, {"error": "forbidden"})
            if self.path == "/":
                page = PAGE.replace("__SCENES__", json.dumps(SCENES, ensure_ascii=False))
                return self.reply(200, page.encode("utf-8"), "text/html; charset=utf-8")
            if self.path == "/api/status":
                return self.reply_json(200, console.status())
            self.reply_json(404, {"error": "not found"})

        def do_POST(self) -> None:
            # A page from another site cannot add this header without a preflight, which is never answered.
            if not self.allowed() or self.headers.get("X-Demo-Console") != "1":
                return self.reply_json(403, {"error": "forbidden"})
            for pattern, action in ROUTES:
                match = pattern.match(self.path)
                if not match:
                    continue
                if not console.lock.acquire(blocking=False):
                    return self.reply_json(409, {"error": "앞의 동작이 아직 진행 중입니다."})
                try:
                    console.note = action(console, match)
                    return self.reply_json(200, {"note": console.note})
                except (RuntimeError, OSError, subprocess.TimeoutExpired) as error:
                    console.note = str(error)
                    return self.reply_json(500, {"error": str(error)})
                finally:
                    console.lock.release()
            self.reply_json(404, {"error": "not found"})

    return Handler


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--install", action="store_true", help="apk 폴더의 release APK를 두 차에 다시 설치")
    parser.add_argument("--port", type=int, default=CONSOLE_PORT, help="시연 콘솔 포트 (기본 8790)")
    parser.add_argument("--no-browser", action="store_true", help="브라우저를 자동으로 열지 않음")
    parser.add_argument("--no-reset", action="store_true", help="시작할 때 두 차와 서버 기록을 초기화하지 않음")
    args = parser.parse_args()

    global ADB
    ADB = find_adb()
    serials = wait_for_cars()
    cars = [Car(name, serial, port) for name, serial, port in zip("AB", serials, CONSOLE_PORTS)]
    server = start_server()
    apk = release_apk()
    for car in cars:
        prepare(car, apk, args.install)
    keep_consoles_awake(cars)
    console = Console({car.name: car for car in cars})
    if not args.no_reset:
        print("두 차를 주차장 밖 출발 위치로 옮기는 중입니다.")
        try:
            console.note = console.reset()
        except (RuntimeError, OSError) as error:
            console.note = f"초기화 중 문제가 있었습니다: {error}"
            print(console.note)

    httpd = ThreadingHTTPServer(("127.0.0.1", args.port), handler_for(console, args.port))
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))  # Also stops the server this script started.
    url = f"http://127.0.0.1:{args.port}/"
    print(f"\n시연 콘솔: {url}\n두 차 화면에서 [동의하고 시작]을 누른 뒤 콘솔의 순서를 따라가세요. 끝내려면 Ctrl+C.")
    if not args.no_browser:
        webbrowser.open(url)
    try:
        httpd.serve_forever()
    except (KeyboardInterrupt, SystemExit):
        pass
    finally:
        httpd.server_close()
        if server is not None:
            server.terminate()
            try:  # An open dashboard stream can hold a graceful shutdown for as long as it stays open.
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
            print("서버를 껐습니다.")
    return 0


PAGE = r"""<!doctype html>
<html lang="ko" translate="no">
<head>
<meta charset="utf-8">
<meta name="google" content="notranslate">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ParkwithH 시연 콘솔</title>
<style>
  /* Shares its look with the app and the dashboard: the floor on a sign-blue band, the zone as one
     large letter, the count beside it. Colour carries meaning only. */
  :root {
    --bg:#eef1f4; --panel:#ffffff; --text:#10161c; --muted:#4e5965; --line:#d0d6dd; --chip:#e4e8ed;
    --band:#0f4cbd; --band-text:#ffffff; --open:#067a43; --soon:#a35c00; --taken:#707a85; --bad:#b42318;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg:#0d1116; --panel:#181e26; --text:#f4f6f8; --muted:#a5b0bc; --line:#303944; --chip:#232b35;
      --band:#185cd6; --band-text:#ffffff; --open:#4ade80; --soon:#ffb840; --taken:#848f9b; --bad:#ff8a80;
    }
  }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--text); padding:22px 16px 44px; word-break:keep-all;
         font:16px/1.5 "Pretendard", "Noto Sans KR", "Apple SD Gothic Neo", system-ui, sans-serif; }
  main { max-width:1320px; margin:0 auto; }
  h1 { font-size:28px; line-height:1.2; margin:0; letter-spacing:-0.01em; }
  .sub { color:var(--muted); margin:6px 0 18px; max-width:76ch; }
  .layout { display:grid; grid-template-columns:minmax(0,3fr) minmax(0,2fr); gap:18px; align-items:start; }
  @media (max-width:900px) { .layout { grid-template-columns:1fr; } }
  .panel { background:var(--panel); border:1px solid var(--line); border-radius:16px; padding:16px 18px; }
  .stack { display:grid; gap:12px; }
  h2 { font-size:18px; margin:0 0 10px; }
  .step { display:grid; grid-template-columns:44px minmax(0,1fr); gap:14px; padding:14px 16px; border:1px solid var(--line);
          border-radius:16px; background:var(--panel); }
  .step.next { border-color:var(--band); box-shadow:0 0 0 2px var(--band) inset; }
  .step.done { opacity:.6; }
  .num { width:40px; height:40px; border-radius:10px; background:var(--chip); display:grid; place-items:center; font-weight:800; font-size:18px; }
  .step.next .num { background:var(--band); color:var(--band-text); }
  .step h3 { font-size:17px; margin:6px 0 4px; }
  .step p { margin:0 0 10px; color:var(--muted); max-width:70ch; }
  .tag { font-size:13px; padding:2px 9px; border-radius:99px; background:var(--chip); margin-left:8px; font-weight:700; white-space:nowrap; }
  button { font:inherit; border:1px solid var(--line); background:var(--panel); color:var(--text); border-radius:10px;
           padding:8px 14px; cursor:pointer; min-height:42px; }
  button:hover { border-color:var(--band); }
  button:focus-visible { outline:3px solid var(--band); outline-offset:2px; }
  button.primary { background:var(--band); color:var(--band-text); border-color:var(--band); font-weight:700; }
  button.on { background:var(--band); color:var(--band-text); border-color:var(--band); font-weight:700; }
  button:disabled { opacity:.5; cursor:progress; }
  .row { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin:8px 0 0; }
  .label { color:var(--muted); min-width:44px; }
  .ok { color:var(--open); font-weight:700; }
  .wait { color:var(--soon); font-weight:700; }
  .note { margin:0 0 14px; padding:12px 16px; border-radius:12px; background:var(--panel); border:1px solid var(--line);
          border-left:6px solid var(--band); min-height:48px; }
  .floors { display:grid; gap:8px; }
  .floor { display:grid; grid-template-columns:64px minmax(0,1fr); border:1px solid var(--line); border-radius:12px; overflow:hidden; }
  .band { background:var(--band); color:var(--band-text); display:grid; place-items:center; font-size:22px; font-weight:800; }
  .zone { display:grid; grid-template-columns:auto minmax(0,1fr) auto; gap:10px; align-items:center; padding:8px 12px; min-height:52px; }
  .zone + .zone { border-top:1px solid var(--line); }
  .mark { font-size:26px; font-weight:800; line-height:1; min-width:1.2em; }
  .mark small { font-size:14px; font-weight:500; color:var(--muted); margin-left:4px; }
  .notes { color:var(--muted); font-size:14px; text-align:right; }
  .count { font-size:24px; font-weight:800; font-variant-numeric:tabular-nums; min-width:3.4em; text-align:right; }
  .open .count { color:var(--open); } .soon .count { color:var(--soon); }
  .taken .count { color:var(--taken); } .quiet .count { color:var(--taken); font-weight:500; font-size:15px; }
  .small { color:var(--muted); font-size:14px; }
  a { color:var(--band); }
  @media (prefers-color-scheme: dark) { a { color:#6eaaff; } }
</style>
</head>
<body>
<main>
  <h1>ParkwithH 시연 콘솔</h1>
  <p class="sub">A차가 코엑스 주차장 지하 2층 B구역에서 나갑니다. B차는 앱을 열지 않은 채 주차장 100m 앞에서 층별 주차 가능 구역을 듣고,
     앞차가 가는 구역을 피해 갈 곳을 알린 뒤, 한 번 눌러 주차를 마칩니다.</p>
  <div class="note" id="note" role="status" aria-live="polite">불러오는 중</div>
  <div class="layout">
    <section class="stack" id="steps" aria-label="장면 순서">
      <div class="step" id="step0">
        <div class="num">0</div>
        <div>
          <h3>두 차 화면에서 앱 동의</h3>
          <p>콘솔이 두 차의 앱을 서버 모드로 열어 두었습니다. 처음 한 번, 각 차 화면에서 [동의하고 시작]을 누르세요.
             권한은 미리 허용해 두었습니다.</p>
          <div class="row" id="ready"></div>
        </div>
      </div>
    </section>
    <section class="stack">
      <div class="panel">
        <h2>주차 가능 구역 (서버가 보는 코엑스 주차장)</h2>
        <div id="board" class="small">불러오는 중</div>
        <p class="small">앱 차량의 출차 기준입니다. 자리 보장은 아닙니다.
           <a id="dash" target="_blank" rel="noopener">운영사 대시보드 열기</a></p>
      </div>
      <div class="panel" id="carA"></div>
      <div class="panel" id="carB"></div>
      <div class="panel">
        <h2>가상 차량</h2>
        <p class="small">에뮬레이터가 아닌 차의 이벤트를 서버에 대신 보냅니다. 내용은 정해져 있습니다.</p>
        <div class="row">
          <button id="seed">다른 층 출차 2건과 앞차 넣기</button>
          <button id="take">B차가 가던 지하 2층 B구역에 먼저 주차</button>
        </div>
      </div>
      <div class="panel">
        <h2>다시 하기</h2>
        <p class="small">두 차를 주차장 밖으로 옮겨 P로 세우고 서버 기록을 지웁니다.</p>
        <button id="reset">처음부터 다시</button>
      </div>
    </section>
  </div>
</main>
<script>
const SCENES = __SCENES__;
const CARS = { A: "A차: 먼저 주차했다가 나가는 차", B: "B차: 목록을 듣고 들어오는 차" };
const PLACES = [["lot", "코엑스 주차장 안"], ["near", "100m 앞"], ["away", "주차장 밖"]];
let busy = false;

const el = (tag, attrs = {}, text) => {
  const node = document.createElement(tag);
  Object.entries(attrs).forEach(([k, v]) => k === "class" ? node.className = v : node.setAttribute(k, v));
  if (text !== undefined) node.textContent = text;
  return node;
};

async function act(path, label) {
  if (busy) return;
  busy = true;
  document.querySelectorAll("button").forEach(b => b.disabled = true);
  document.getElementById("note").textContent = label + " 진행 중…";
  try {
    const res = await fetch(path, { method: "POST", headers: { "X-Demo-Console": "1" } });
    const body = await res.json();
    document.getElementById("note").textContent = body.note || body.error || "완료";
  } catch (e) {
    document.getElementById("note").textContent = "콘솔에 연결할 수 없습니다. 터미널의 demo_console.py가 실행 중인지 확인하세요.";
  } finally {
    busy = false;
    document.querySelectorAll("button").forEach(b => b.disabled = false);
    refresh();
  }
}

SCENES.forEach((s, i) => {
  const n = i + 1;
  const step = el("div", { class: "step", id: "step" + n });
  step.append(el("div", { class: "num" }, String(n)));
  const body = el("div");
  const h = el("h3", {}, s.title);
  h.append(el("span", { class: "tag" }, s.car === "가상" ? "가상 차량" : s.car + "차"));
  body.append(h, el("p", {}, s.screen));
  const button = el("button", { class: "primary" }, s.button);
  button.onclick = () => act("/api/scene/" + n, s.button);
  body.append(button);
  step.append(body);
  document.getElementById("steps").append(step);
});
document.getElementById("reset").onclick = () => act("/api/reset", "처음부터 다시");
document.getElementById("seed").onclick = () => act("/api/virtual/seed", "가상 차량 넣기");
document.getElementById("take").onclick = () => act("/api/virtual/take", "가상 차량 주차");

function renderCar(name, car) {
  const box = document.getElementById("car" + name);
  box.replaceChildren();
  box.append(el("h2", {}, CARS[name]));
  const info = el("div", { class: "small" }, `${car.serial}, Virtual Console 127.0.0.1:${car.console}, `);
  info.append(el("span", { class: car.appReady ? "ok" : "wait" }, car.appReady ? "앱 실행 중" : "동의 대기"));
  box.append(info);
  const gears = el("div", { class: "row" });
  gears.append(el("span", { class: "label" }, "기어"));
  ["P", "R", "N", "D"].forEach(g => {
    const b = el("button", { class: car.gear === g ? "on" : "", "aria-pressed": String(car.gear === g) }, g);
    b.onclick = () => act(`/api/car/${name}/gear/${g}`, `${name}차 기어 ${g}`);
    gears.append(b);
  });
  box.append(gears);
  const places = el("div", { class: "row" });
  places.append(el("span", { class: "label" }, "위치"));
  PLACES.forEach(([key, text]) => {
    const b = el("button", { class: car.place === key ? "on" : "", "aria-pressed": String(car.place === key) }, text);
    b.onclick = () => act(`/api/car/${name}/place/${key}`, `${name}차 위치 이동`);
    places.append(b);
  });
  box.append(places);
}

const ZONE = /^(B?)(\d)(F?)(?:-(.+))?$/;
const badge = id => { const m = ZONE.exec(id); return !m ? "?" : m[1] ? "B" + m[2] : m[2] + "F"; };
const mark = id => { const m = ZONE.exec(id); return m && m[4] ? m[4] : ""; };
const floorRank = id => { const m = ZONE.exec(id); return !m ? Infinity : m[1] ? Number(m[2]) : -Number(m[2]); };
const age = ms => ms < 60000 ? "방금" : Math.floor(ms / 60000) + "분 전";

function zoneRow(z) {
  const departures = z.vacancies.length, claims = z.claims || 0;
  const state = departures ? (departures > claims ? "open" : "taken") : z.leaving_soon ? "soon" : "quiet";
  const row = el("div", { class: "zone " + state });
  const name = el("div", { class: "mark" }, mark(z.zone_id) || (z.zone_id === "UNZONED" ? "미입력" : "전체"));
  if (mark(z.zone_id)) name.append(el("small", {}, "구역"));
  const notes = [];
  if (departures) notes.push(age(z.vacancies[0]));
  if (claims) notes.push(`가는 중 ${claims}대`);
  notes.push(`주차 중 ${z.parked_app_cars}대`);
  const count = departures ? `${departures}곳` : z.leaving_soon ? `곧 ${z.leaving_soon}곳` : "소식 없음";
  row.append(name, el("div", { class: "notes" }, notes.join(", ")), el("div", { class: "count" }, count));
  return row;
}

function renderBoard(board) {
  const box = document.getElementById("board");
  box.replaceChildren();
  if (!board) { box.textContent = "서버에 연결할 수 없습니다."; return; }
  const capacity = (Math.round(board.registeredCapacity / 100) * 100).toLocaleString("ko-KR");
  box.append(el("p", {}, `주차 중인 앱 차량 ${board.parkedAppCars}대, 등록 약 ${capacity}면`));
  if (!board.zones.length) { box.append(el("p", {}, "아직 공유된 구역 소식이 없습니다.")); return; }
  const floors = new Map();
  board.zones.slice().sort((a, b) => floorRank(a.zone_id) - floorRank(b.zone_id) || a.zone_id.localeCompare(b.zone_id))
    .forEach(z => { const k = badge(z.zone_id); if (!floors.has(k)) floors.set(k, []); floors.get(k).push(z); });
  const list = el("div", { class: "floors" });
  floors.forEach((zones, key) => {
    const floor = el("div", { class: "floor" });
    const rows = el("div");
    rows.append(...zones.map(zoneRow));
    floor.append(el("div", { class: "band" }, key), rows);
    list.append(floor);
  });
  box.append(list);
}

async function refresh() {
  try {
    const s = await (await fetch("/api/status", { cache: "no-store" })).json();
    renderCar("A", s.cars.A);
    renderCar("B", s.cars.B);
    renderBoard(s.board);
    document.getElementById("dash").href = s.dashboard;
    const ready = document.getElementById("ready");
    ready.replaceChildren(...["A", "B"].map(n => el("span", { class: s.cars[n].appReady ? "ok" : "wait" },
      `${n}차 ${s.cars[n].appReady ? "준비됨" : "동의 대기"}`)));
    const allReady = s.cars.A.appReady && s.cars.B.appReady;
    document.getElementById("step0").className = "step " + (allReady ? "done" : "next");
    SCENES.forEach((_, i) => {
      const n = i + 1;
      document.getElementById("step" + n).className = "step" +
        (n <= s.lastScene ? " done" : (allReady && n === s.lastScene + 1 ? " next" : ""));
    });
    if (!busy && s.note) document.getElementById("note").textContent = s.note;
    if (s.busy && !busy) document.getElementById("note").textContent = "다른 동작이 진행 중입니다…";
  } catch (e) {
    document.getElementById("note").textContent = "콘솔에 연결할 수 없습니다. 터미널의 demo_console.py가 실행 중인지 확인하세요.";
  }
}
refresh();
setInterval(() => { if (!busy) refresh(); }, 2000);
</script>
</body>
</html>
"""

if __name__ == "__main__":
    sys.exit(main())
