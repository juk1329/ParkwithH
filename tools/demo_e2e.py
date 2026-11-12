#!/usr/bin/env python
"""Runs the three live scenes on two emulators and checks what each car actually did.

Car A leaves a zone. Car B, with the navigation on screen and this app out of sight, hears about it
and then confirms its own parking with one tap on a notification.

Requires tools/emu.sh boot 1 and boot 2, and the server on port 8787. Run with --install after a build.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import shlex
import subprocess
import sys
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
PACKAGE = "app.parkrelay"
NAVIGATION = "ai.umos.maps.android.navigation.app/.MainActivity"
PERMISSIONS = (
    "pleos.car.permission.FUSED_LOCATION",
    "android.car.permission.CAR_SPEED",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
)

LOT_ID = "06171-11291-00001-00-1"  # 탄천공영주차장, a real public lot.
LOT_LAT, LOT_LNG = 37.51441586, 127.0662917
FAR_LAT, FAR_LNG = 37.6000, 127.1500
ZONE = "B2-B"
CONFIRM_BUTTON = (1500, 258)  # The rightmost button on a three-button banner, at 2560x1440.
MAX_VOICE_LATENCY_MS = 3000
CLOCK_MARGIN_MS = 500
LOG_LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+ParkRelay:\s*(.*)$")
LATENCIES: list[int] = []


@dataclass
class LogEntry:
    at_ms: int
    text: str


class Car:
    def __init__(self, name: str, serial: str, console: int):
        self.name, self.serial, self.console = name, serial, console
        self.clock_offset_ms = 0

    def adb(self, *args: str, timeout: float = 30) -> str:
        # Logcat cuts long lines by bytes, which can split a Korean character in two.
        result = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
        return result.stdout

    def shell(self, *args: str) -> str:
        return self.adb("shell", " ".join(shlex.quote(a) for a in args))

    def alive(self) -> bool:
        """Whether the emulator still answers. A round cannot tell a dead car from a slow one by itself."""
        try:
            return self.adb("get-state", timeout=15).strip() == "device"
        except subprocess.TimeoutExpired:
            return False

    def measure_clock(self) -> None:
        before = time.time() * 1000
        device = int(self.shell("date", "+%s%N").strip()) / 1_000_000
        after = time.time() * 1000
        self.clock_offset_ms = int(device - (before + after) / 2)

    def install(self) -> None:
        """A build signed differently cannot be updated in place, so it is removed first when that happens."""
        for attempt in range(2):
            if "Success" in self.adb("install", "-r", "-t", str(APK), timeout=180):
                print(f"[{self.name}] installed")
                return
            self.adb("uninstall", PACKAGE)
        raise RuntimeError(f"{self.name}: the debug build did not install")

    def prepare(self) -> None:
        """Grants what a driver would grant on first run, then leaves the navigation in front."""
        for permission in PERMISSIONS:
            self.shell("pm", "grant", "--user", "10", PACKAGE, permission)
        self.shell("am", "start", "--user", "10", "-n", f"{PACKAGE}/.MainActivity")
        time.sleep(4)
        self.probe("consent")
        self.probe("forget-advice")
        time.sleep(1)
        # A plain start would only bring the consent screen forward. A new process reads the consent and starts the service.
        since = self.now_ms()
        self.shell("am", "start", "-S", "--user", "10", "-n", f"{PACKAGE}/.MainActivity")
        if self.wait_for(r"service\] reconcile", timeout=60, since_ms=since) is None:
            raise RuntimeError(f"{self.name}: the service did not start")
        self.probe("demo", "false")  # This run is about the real server, not the built-in stand-in.
        self.show_navigation()

    def forget_route(self) -> None:
        """A route kept from an earlier run would be announced during setup, before anyone listens for it.

        A route elsewhere plus a fresh start leaves the advice for the scene.
        """
        self.probe("dest", f"{FAR_LAT},{FAR_LNG},출발 전 경로")
        time.sleep(10)
        self.prepare()

    def show_navigation(self) -> None:
        self.shell("am", "start", "--user", "10", "-n", NAVIGATION)
        time.sleep(2)

    def probe(self, command: str, text: str = "") -> None:
        self.shell("am", "broadcast", "--user", "10", "-n", f"{PACKAGE}/.ProbeReceiver",
                   "--es", "cmd", command, "--es", "text", text)

    def gear(self, gear: str) -> None:
        self._console("GET", f"/vehicle/gear?gear={gear}")

    def speed(self, kph: int) -> None:
        self._console("PUT", f"/vehicle/controls?speed={kph}&accelerator={kph}&brake=0")

    def _console(self, method: str, path: str) -> None:
        result = subprocess.run([sys.executable, str(ROOT / "tools/vc.py"), str(self.console), method, path],
                                capture_output=True, text=True, timeout=60)
        if result.returncode != 0:
            raise RuntimeError(f"{self.name}: console {path} failed: {result.stdout[:200]}")

    def move(self, lat: float, lng: float) -> None:
        self.adb("emu", "geo", "fix", str(lng), str(lat))

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))

    def shot(self, path: Path) -> None:
        data = subprocess.run(["adb", "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=30)
        path.write_bytes(data.stdout)

    def top_activity(self) -> str:
        for line in self.shell("dumpsys", "activity", "activities").splitlines():
            if "topResumedActivity" in line:
                match = re.search(r"u\d+ (\S+)", line)
                return match.group(1) if match else line.strip()
        return ""

    def navigation_on_top(self) -> bool:
        return self.top_activity().startswith("ai.umos.maps")

    def now_ms(self) -> int:
        """A starting point for reading the car's log. Early by more than the clock offset's error, so nothing is missed."""
        return int(time.time() * 1000) + self.clock_offset_ms - CLOCK_MARGIN_MS

    def log(self, since_ms: int = 0) -> list[LogEntry]:
        raw = self.adb("logcat", "-d", "-v", "epoch", "-s", "ParkRelay:I")
        entries = []
        for line in raw.splitlines():
            match = LOG_LINE.match(line)
            if match:
                at = int(float(match.group(1)) * 1000)
                if at >= since_ms:
                    entries.append(LogEntry(at, match.group(2)))
        return entries

    def wait_for(self, pattern: str, timeout: float, since_ms: int) -> LogEntry | None:
        regex = re.compile(pattern)
        deadline = time.time() + timeout
        while time.time() < deadline:
            for entry in self.log(since_ms):
                if regex.search(entry.text):
                    return entry
            time.sleep(0.3)
        return None

    def phase(self) -> str:
        since = self.now_ms() - 1000
        self.probe("state")
        entry = self.wait_for(r"state=SessionState", timeout=5, since_ms=since)
        match = re.search(r"phase=(\w+)", entry.text) if entry else None
        return match.group(1) if match else "?"

    def leave_any_session(self) -> None:
        """Ends in DRIVING, far from any lot, whatever the last run left behind."""
        self.gear("Drive")
        self.move(FAR_LAT, FAR_LNG)
        self.speed(20)
        time.sleep(2)
        self.speed(0)
        for step in range(6):
            if self.phase() == "DRIVING":
                return
            self.move(FAR_LAT + step * 0.0001, FAR_LNG)
            time.sleep(2)
        raise RuntimeError(f"{self.name}: still in {self.phase()} after leaving")


class Report:
    def __init__(self):
        self.rows: list[tuple[bool, str, str]] = []

    def check(self, passed: bool, what: str, detail: str = "") -> bool:
        self.rows.append((passed, what, detail))
        print(f"  {'PASS' if passed else 'FAIL'}  {what}  {detail}")
        return passed

    def finish(self) -> int:
        failed = [row for row in self.rows if not row[0]]
        print(f"\n{len(self.rows) - len(failed)}/{len(self.rows)} checks passed")
        for _, what, detail in failed:
            print(f"  FAILED: {what} {detail}")
        return 1 if failed else 0


def http(base: str, path: str, method: str = "GET"):
    request = urllib.request.Request(base + path, method=method, data=b"" if method == "POST" else None)
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.loads(response.read())


def zone_row(board: dict) -> dict:
    return next((zone for zone in board["zones"] if zone["zone_id"] == ZONE), {})


def isolated_residential_lot() -> tuple[str, float, float]:
    """A large lot the catalog refuses to share, with no other lot near enough to be chosen instead."""
    with open(ROOT / "app/src/main/assets/lots.tsv", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    points = [(float(r["lat"]), float(r["lng"])) for r in rows]
    for row in sorted(rows, key=lambda r: -int(r["capacity"])):
        if row["tier"] != "NEVER" or "아파트" not in row["name"]:
            continue
        lat, lng = float(row["lat"]), float(row["lng"])
        nearby = sum(1 for p in points if abs(p[0] - lat) < 0.004 and abs(p[1] - lng) < 0.005)
        if nearby == 1:
            return row["name"], lat, lng
    raise RuntimeError("no isolated residential lot in the catalog")


def latency(sender: Car, sent_at: int, receiver: Car, received_at: int) -> int:
    """Device clocks differ slightly, so each time is brought back to the host's clock first."""
    return (received_at - receiver.clock_offset_ms) - (sent_at - sender.clock_offset_ms)


def memory_mb(car: Car) -> float | None:
    """Proportional set size of the app, or None when it is not running."""
    match = re.search(r"TOTAL PSS:\s+(\d+)", car.shell("dumpsys", "meminfo", PACKAGE))
    return int(match.group(1)) / 1024 if match else None


def run(a: Car, b: Car, server: str, out: Path, first_round: bool = True) -> int:
    report = Report()
    for car in (a, b):
        car.measure_clock()
        print(f"[{car.name}] clock offset {car.clock_offset_ms} ms")

    print("\n== scene setup: A parked in the zone, B on its way with the app off screen")
    a.leave_any_session()
    b.leave_any_session()
    time.sleep(3)  # The last round's departures reach the server before the board is cleared.
    http(server, "/v1/admin/reset", "POST")
    a.show_navigation()
    b.show_navigation()

    since = a.now_ms()
    a.move(LOT_LAT, LOT_LNG)
    time.sleep(3)
    a.gear("Reverse")
    time.sleep(2)
    a.gear("Park")
    prompted = a.wait_for(r"notify\] post id=3", timeout=25, since_ms=since)
    if prompted and "어느 주차장" in prompted.text:
        a.probe("pick", LOT_ID)
        a.wait_for(r"notify\] post id=3 title=(?!어느)", timeout=8, since_ms=since)
    a.probe("zone", ZONE)
    report.check(a.wait_for(rf"publish\] PARK lot={LOT_ID} zone={ZONE}", timeout=10, since_ms=since) is not None,
                 "A publishes its parking")
    time.sleep(2)
    board = http(server, f"/v1/lots/{LOT_ID}/board")
    report.check(board["parkedAppCars"] == 1, "server counts one app car", f"parkedAppCars={board['parkedAppCars']}")

    since_b = b.now_ms()
    b.probe("route", LOT_ID)
    report.check(b.wait_for(rf"approach\] watching {LOT_ID}", timeout=30, since_ms=since_b) is not None,
                 "B's destination comes from the navigation, off screen")
    if first_round:  # Later rounds repeat the destination, and it is rightly not announced twice.
        advice = b.wait_for(r"voice\] focus .*규모예요", timeout=10, since_ms=since_b)
        report.check(advice is not None, "B hears the lot's size with no other users needed",
                     advice.text.split("text=")[-1] if advice else "")
        b.wait_for(r"voice\] finished", timeout=15, since_ms=since_b)
    else:
        time.sleep(61)  # Keeps the car's one-sentence-a-minute limit out of the measurement.
    report.check(b.navigation_on_top(), "B shows the navigation, not this app", b.top_activity())
    b.shot(out / "b0_on_the_way.png")

    print("\n== scene 1 and 2: A pulls out, B is told")
    since_a, since_b = a.now_ms(), b.now_ms()
    a.gear("Drive")
    leaving = a.wait_for(r"session\] PARKED -> LEAVING", timeout=10, since_ms=since_a)
    report.check(leaving is not None, "A's gear leaving P starts the departure")
    warned = b.wait_for(r"voice\] focus .*곧 나갈", timeout=8, since_ms=since_b)
    if leaving and warned:
        ms = latency(a, leaving.at_ms, b, warned.at_ms)
        report.check(ms <= MAX_VOICE_LATENCY_MS, "B starts saying 'leaving soon' within 3 s", f"{ms} ms")
    else:
        report.check(False, "B says 'leaving soon'")
    a.speed(20)
    vacated = a.wait_for(r"session\] LEAVING -> VACATED", timeout=10, since_ms=since_a)
    report.check(vacated is not None, "A's movement confirms the departure")
    posted = b.wait_for(r"notify\] post id=2 title=.*방금 1대", timeout=8, since_ms=since_b)
    spoken = b.wait_for(r"voice\] focus .*방금 1대", timeout=8, since_ms=since_b)
    if vacated and posted:
        ms = latency(a, vacated.at_ms, b, posted.at_ms)
        report.check(ms <= MAX_VOICE_LATENCY_MS, "B's banner appears within 3 s of the departure", f"{ms} ms")
    else:
        report.check(False, "B shows the departure banner")
    if vacated and spoken:
        ms = latency(a, vacated.at_ms, b, spoken.at_ms)
        LATENCIES.append(ms)
        report.check(ms <= MAX_VOICE_LATENCY_MS, "B starts saying the departure within 3 s", f"{ms} ms")
    else:
        report.check(False, "B says the departure")
    time.sleep(1)
    b.shot(out / "b1_departure_banner.png")
    report.check(b.navigation_on_top(), "B still shows the navigation", b.top_activity())
    row = zone_row(http(server, f"/v1/lots/{LOT_ID}/board"))
    report.check(row.get("status") == "JUST_LEFT" and len(row.get("vacancies", [])) == 1,
                 "board shows one fresh departure", json.dumps(row, ensure_ascii=False)[:120])
    a.speed(0)

    print("\n== A leaves the lot")
    since_a = a.now_ms()
    a.move(FAR_LAT, FAR_LNG)
    report.check(a.wait_for(rf"publish\] EXIT lot={LOT_ID}", timeout=15, since_ms=since_a) is not None, "A ends its session")
    exit_alert = a.wait_for(r"proximity\] transition EXIT", timeout=6, since_ms=since_a)
    print(f"  note  proximity EXIT callback: {'received' if exit_alert else 'not received, position rule ended the session'}")

    print("\n== scene 3: B arrives and confirms with one tap")
    time.sleep(11)
    gone = f"pkg={PACKAGE} " not in "\n".join(
        line for line in b.shell("dumpsys", "notification", "--noredact").splitlines() if " id=2 " in line)
    report.check(gone, "B's information banner clears itself so the turn guidance is visible")
    since_b = b.now_ms()
    b.move(LOT_LAT, LOT_LNG)
    arrival = b.wait_for(r"proximity\] transition ENTER", timeout=10, since_ms=since_b)
    print(f"  note  proximity ENTER callback: {'received' if arrival else 'not received'}")
    b.gear("Reverse")
    time.sleep(2)
    b.gear("Park")
    prompt = b.wait_for(rf"notify\] post id=3 title=지하 2층 B구역에 내 차 위치 저장", timeout=25, since_ms=since_b)
    report.check(prompt is not None, "B is offered the zone it was just told about")
    time.sleep(1.5)
    b.shot(out / "b2_park_prompt.png")
    since_tap = b.now_ms()
    b.tap(*CONFIRM_BUTTON)
    report.check(b.wait_for(r"start action=app\.parkrelay\.CONFIRM_ZONE", timeout=6, since_ms=since_tap) is not None,
                 "the tap reaches the service")
    report.check(b.wait_for(rf"publish\] PARK lot={LOT_ID} zone={ZONE}", timeout=8, since_ms=since_tap) is not None,
                 "B publishes its parking")
    time.sleep(2)
    b.shot(out / "b3_after_tap.png")
    report.check(b.navigation_on_top(), "the app never opened", b.top_activity())
    board = http(server, f"/v1/lots/{LOT_ID}/board")
    row = zone_row(board)
    report.check(row.get("vacancies") == [] and board["parkedAppCars"] == 1,
                 "B's parking used up the departure and counts as one car",
                 f"vacancies={row.get('vacancies')} parkedAppCars={board['parkedAppCars']}")

    print("\n== nothing false gets out")
    since_a = a.now_ms()
    a.move(LOT_LAT, LOT_LNG)
    time.sleep(3)
    a.gear("Park")
    report.check(a.wait_for(r"notify\] post id=3", timeout=20, since_ms=since_a) is not None,
                 "a stop in P still asks, in case it is real parking")
    a.gear("Drive")
    time.sleep(3)
    published = [e.text for e in a.log(since_a) if "publish]" in e.text]
    report.check(not published, "a short stop in P with no reverse publishes nothing", str(published)[:120])
    buttons = [line for line in a.shell("dumpsys", "notification", "--noredact").splitlines()
               if f"pkg={PACKAGE} " in line and " id=3 " in line]
    report.check(not buttons, "the question with buttons is gone once the car is moving")
    a.leave_any_session()

    name, lat, lng = isolated_residential_lot()
    since_a = a.now_ms()
    a.move(lat, lng)
    time.sleep(3)
    a.gear("Reverse")
    time.sleep(2)
    a.gear("Park")
    time.sleep(15)
    published = [e.text for e in a.log(since_a) if "publish]" in e.text]
    prompts = [e.text for e in a.log(since_a) if "notify] post id=3" in e.text]
    report.check(not published and not prompts, f"parking at home ({name}) sends and asks nothing",
                 str(published + prompts)[:120])
    a.gear("Drive")
    return report.finish()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true", help="install the debug APK on both cars first")
    parser.add_argument("--server", default="http://127.0.0.1:8787")
    parser.add_argument("--out", type=Path, default=Path("/tmp/parkrelay-e2e"), help="where to save screen captures")
    parser.add_argument("--minutes", type=float, default=0, help="repeat in the same process for this long")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    a = Car("A", "emulator-5554", 8088)
    b = Car("B", "emulator-5556", 8089)
    for car in (a, b):
        if args.install:
            car.install()
        car.prepare()
    b.forget_route()
    if args.minutes:
        try:
            code = run(a, b, args.server, args.out)
        except Exception as error:  # The long run decides what a bad round means.
            print(f"first round stopped: {error}", flush=True)
            code = 1
        code = soak(a, b, args, code)
    else:
        code = run(a, b, args.server, args.out)
    print(f"\nscreens: {args.out}")
    return code


def revive(car: Car) -> None:
    """Boots an emulator that crashed and brings the app back, as after a power loss."""
    vehicle = (int(car.serial.split("-")[1]) - 5552) // 2
    subprocess.run([str(ROOT / "tools/emu.sh"), "boot", str(vehicle)], check=True, timeout=400, capture_output=True)
    time.sleep(45)
    car.measure_clock()
    car.prepare()


def soak(a: Car, b: Car, args: argparse.Namespace, code: int) -> int:
    """Runs the same scenes for a long time with no restart of the app, to expose leaks and slowdowns.

    The emulator itself can crash under software rendering. That is counted apart from the app's own
    failures: a car that stops answering is booted again, and the round it interrupted proves nothing.
    """
    cars = (a, b)
    start = time.time()
    samples = [{car.name: memory_mb(car) for car in cars}]
    failed_rounds = 1 if code else 0
    finished = 1
    crashes: list[str] = []
    print(f"memory at start: {samples[0]} (MB, PSS)", flush=True)
    while time.time() - start < args.minutes * 60:
        minutes = (time.time() - start) / 60
        print(f"\n######## round {finished + 1}, {minutes:.0f} min, {time.strftime('%H:%M:%S')}", flush=True)
        problem = False
        try:
            problem = run(a, b, args.server, args.out, first_round=False) != 0
        except Exception as error:  # A stuck round must not hide the numbers from the others.
            problem = True
            print(f"round stopped at {time.strftime('%H:%M:%S')}: {error}", flush=True)
        gone = [car for car in cars if not car.alive()]
        if gone:
            for car in gone:
                crashes.append(f"{car.name} at {(time.time() - start) / 60:.0f} min")
                print(f"car {car.name} stopped answering at {time.strftime('%H:%M:%S')}, booting it again", flush=True)
                revive(car)
            print("this round does not count", flush=True)
            continue
        finished += 1
        failed_rounds += 1 if problem else 0
        samples.append({car.name: memory_mb(car) for car in cars})
        print(f"memory: {samples[-1]} (MB, PSS)", flush=True)
    ordered = sorted(LATENCIES)
    print(f"\n==== soak: {finished} rounds finished in {(time.time() - start) / 60:.0f} min, {failed_rounds} with a failure")
    print(f"emulator crashes: {len(crashes)} {crashes}")
    if ordered:
        print(f"departure to speech request: {len(ordered)} times, min {ordered[0]} ms, median {ordered[len(ordered) // 2]} ms, max {ordered[-1]} ms")
    for car in cars:
        values = [sample[car.name] for sample in samples if sample[car.name] is not None]
        if values:
            print(f"car {car.name} memory: first {values[0]:.1f}, peak {max(values):.1f}, last {values[-1]:.1f} MB (PSS)")
    return 1 if failed_rounds else 0


if __name__ == "__main__":
    sys.exit(main())
