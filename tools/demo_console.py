#!/usr/bin/env python
"""The two-car demo on one page: car A leaves a zone, car B hears it and parks with one tap.

    python tools/demo_console.py             # with two Pleos emulators already running
    python tools/demo_console.py --install   # also installs apk/*_release.apk on both

It waits for two emulators, starts the server on port 8787 unless one is running, opens the app on
both cars in server mode, and serves the console at http://127.0.0.1:8790/. The console moves each
car's gear through its Virtual Console and its location with `adb emu geo fix`, so nothing has to be
typed while the scene runs. Works on Windows, macOS and Linux. Needs adb and the server's packages.

Car A is the emulator with the lower port (usually emulator-5554) and its Virtual Console is
forwarded to 8088. Car B is the other one, on 8089. Everything listens on 127.0.0.1 only, and a
request can only pick among fixed choices: nothing it sends reaches a command line.
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
    "away": (37.6000, 127.1500),  # Kilometres from any lot in the catalog.
}
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
            a.gear("D")
        elif number == 4:
            a.move("away")
        elif number == 5:
            self.park(b)
        self.last_scene = number
        return SCENES[number - 1]["done"]

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
    {"car": "A", "title": "A차가 출차를 시작해요",
     "button": "A차 기어 D",
     "screen": "B차는 앱을 열지 않아도 \"지하 2층 B구역에서 곧 나갈 차가 있어요\"를 음성과 배너로 받습니다.",
     "done": "A차 기어가 P에서 D로 바뀌었습니다."},
    {"car": "A", "title": "A차가 주차장을 빠져나가요",
     "button": "A차 위치를 주차장 밖으로",
     "screen": "B차에 \"지하 2층 B구역에서 방금 1대 나갔어요\"가 옵니다. 앱은 오탐을 막으려고 15m 이상 움직이거나 "
               "주차장을 벗어나야 출차로 확정하는데, Virtual Console에는 주행 장치가 없어 위치를 옮깁니다.",
     "done": "A차 위치를 주차장 밖으로 옮겼습니다."},
    {"car": "B", "title": "B차가 도착해 같은 구역에 주차해요",
     "button": "B차 주차 (후진 → P)",
     "screen": "B차 화면 위 \"지하 2층 B구역에 내 차 위치 저장\"에서 [맞아요]를 누르세요. 앱을 열지 않고 저장되고, "
               "대시보드의 출차 소식 1건이 B차 주차로 소모됩니다.",
     "done": "B차가 코엑스 주차장 안에서 R → P로 바뀌었습니다. B차 화면의 배너에서 [맞아요]를 누르세요."},
]


def get_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=5) as response:
        return json.loads(response.read().decode("utf-8"))


def post(url: str) -> None:
    request = urllib.request.Request(url, method="POST", data=b"")
    with urllib.request.urlopen(request, timeout=10):
        pass


def server_alive() -> bool:
    try:
        get_json(f"{SERVER}/v1/lots/{LOT_ID}/board")
        return True
    except (OSError, ValueError):
        return False


def start_server() -> subprocess.Popen | None:
    """Starts the server unless one already answers. Returns the process this script owns, if any."""
    if server_alive():
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
    (re.compile(r"^/api/car/([AB])/place/(lot|away)$"), lambda c, m: c.cars[m[1]].move(m[2]) or
     f"차량 {m[1]} 위치를 {'코엑스 주차장 안' if m[2] == 'lot' else '주차장 밖'}으로 옮겼습니다."),
    (re.compile(r"^/api/scene/([1-5])$"), lambda c, m: c.scene(int(m[1]))),
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
  :root {
    --bg:#f5f6f8; --panel:#ffffff; --text:#14181f; --muted:#5d6675; --line:#dfe3e8;
    --accent:#2457d6; --accent-text:#ffffff; --good:#17804a; --warn:#a86400; --bad:#b42318; --chip:#eef1f5;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg:#12161c; --panel:#1c222b; --text:#eef1f5; --muted:#9aa4b2; --line:#2c343f;
      --accent:#6d9bff; --accent-text:#0b1220; --good:#78dcaa; --warn:#f2c14e; --bad:#ff8a80; --chip:#262d37;
    }
  }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--text); font:16px/1.5 system-ui, "Noto Sans KR", sans-serif;
         padding:20px 16px 40px; word-break:keep-all; }
  main { max-width:1280px; margin:0 auto; }
  h1 { font-size:26px; margin:0; }
  .sub { color:var(--muted); margin:4px 0 18px; }
  .layout { display:grid; grid-template-columns:minmax(0,3fr) minmax(0,2fr); gap:18px; align-items:start; }
  @media (max-width:900px) { .layout { grid-template-columns:1fr; } }
  .panel { background:var(--panel); border:1px solid var(--line); border-radius:12px; padding:16px; }
  .stack { display:grid; gap:14px; }
  h2 { font-size:18px; margin:0 0 10px; }
  .step { display:grid; grid-template-columns:40px minmax(0,1fr); gap:12px; padding:14px; border:1px solid var(--line);
          border-radius:10px; background:var(--panel); }
  .step.next { border-color:var(--accent); box-shadow:0 0 0 1px var(--accent) inset; }
  .step.done { opacity:.62; }
  .num { width:36px; height:36px; border-radius:50%; background:var(--chip); display:grid; place-items:center; font-weight:700; }
  .step.next .num { background:var(--accent); color:var(--accent-text); }
  .step h3 { font-size:17px; margin:4px 0 4px; }
  .step p { margin:0 0 10px; color:var(--muted); }
  .tag { font-size:13px; padding:1px 8px; border-radius:99px; background:var(--chip); margin-left:6px; font-weight:600; }
  button { font:inherit; border:1px solid var(--line); background:var(--chip); color:var(--text); border-radius:8px;
           padding:8px 14px; cursor:pointer; min-height:40px; }
  button.primary { background:var(--accent); color:var(--accent-text); border-color:var(--accent); font-weight:600; }
  button.on { outline:2px solid var(--accent); outline-offset:1px; font-weight:700; }
  button:disabled { opacity:.5; cursor:progress; }
  .row { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin:6px 0; }
  .label { color:var(--muted); min-width:44px; }
  .ok { color:var(--good); font-weight:600; }
  .wait { color:var(--warn); font-weight:600; }
  .note { margin:0 0 14px; padding:10px 14px; border-radius:10px; background:var(--chip); min-height:44px; }
  .zones { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:8px; }
  .zone { border:1px solid var(--line); border-radius:8px; padding:8px 10px; }
  .zone b { display:block; font-size:18px; }
  .JUST_LEFT { border-color:var(--good); } .JUST_LEFT .st { color:var(--good); }
  .LEAVING_SOON { border-color:var(--warn); } .LEAVING_SOON .st { color:var(--warn); }
  .small { color:var(--muted); font-size:14px; }
  a { color:var(--accent); }
</style>
</head>
<body>
<main>
  <h1>ParkwithH 시연 콘솔</h1>
  <p class="sub">A차가 코엑스 주차장 지하 2층 B구역에서 나가면, B차는 앱을 열지 않고도 그 소식을 듣고 같은 구역에 주차합니다.</p>
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
      <div class="panel" id="carA"></div>
      <div class="panel" id="carB"></div>
      <div class="panel">
        <h2>코엑스 주차장 현황 (서버)</h2>
        <div id="board" class="small">불러오는 중</div>
        <p class="small"><a id="dash" target="_blank" rel="noopener">운영사 대시보드 열기</a> · 원시 이벤트 로그와 함께 봅니다.</p>
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
const CARS = { A: "A차 · 먼저 주차했다가 나가는 차", B: "B차 · 소식을 듣고 들어오는 차" };
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
  h.append(el("span", { class: "tag" }, s.car + "차"));
  body.append(h, el("p", {}, s.screen));
  const button = el("button", { class: "primary" }, s.button);
  button.onclick = () => act("/api/scene/" + n, s.button);
  body.append(button);
  step.append(body);
  document.getElementById("steps").append(step);
});
document.getElementById("reset").onclick = () => act("/api/reset", "처음부터 다시");

function renderCar(name, car) {
  const box = document.getElementById("car" + name);
  box.replaceChildren();
  box.append(el("h2", {}, CARS[name]));
  const info = el("div", { class: "small" }, `${car.serial} · Virtual Console 127.0.0.1:${car.console} · `);
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
  [["lot", "코엑스 주차장 안"], ["away", "주차장 밖"]].forEach(([key, text]) => {
    const b = el("button", { class: car.place === key ? "on" : "", "aria-pressed": String(car.place === key) }, text);
    b.onclick = () => act(`/api/car/${name}/place/${key}`, `${name}차 위치 이동`);
    places.append(b);
  });
  box.append(places);
}

const zoneText = id => {
  if (id === "UNZONED") return "구역 미입력";
  const m = /^(B?)(\d)(F?)(?:-(.+))?$/.exec(id);
  if (!m) return id;
  return (m[1] ? "지하 " : "") + m[2] + "층" + (m[4] ? " " + m[4] + "구역" : "");
};
const stateText = z => z.status === "JUST_LEFT" ? "방금 1대 출차" : z.status === "LEAVING_SOON" ? "곧 나갈 차 있음"
  : z.status === "FULLNESS" ? "운전자 응답: " + z.fullness : "소식 없음";

function renderBoard(board) {
  const box = document.getElementById("board");
  box.replaceChildren();
  if (!board) { box.textContent = "서버에 연결할 수 없습니다."; return; }
  box.append(el("p", {}, `앱 차량 ${board.parkedAppCars}대 주차 중 · 등록 약 ${(Math.round(board.registeredCapacity / 100) * 100).toLocaleString("ko-KR")}면`));
  if (!board.zones.length) { box.append(el("p", {}, "아직 공유된 구역 소식이 없습니다.")); return; }
  const grid = el("div", { class: "zones" });
  board.zones.forEach(z => {
    const card = el("div", { class: "zone " + z.status });
    card.append(el("b", {}, zoneText(z.zone_id)), el("div", { class: "st" }, stateText(z)),
                el("div", { class: "small" }, `주차 중 ${z.parked_app_cars}대`));
    grid.append(card);
  });
  box.append(grid);
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
