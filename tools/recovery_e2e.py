#!/usr/bin/env python
"""Cuts the power on Vehicle 2 at the moments that matter and checks nothing is lost or invented.

The ignition can go off right after P, underground lots often have no signal, and the infotainment
can finish booting after the car has already left. A real suspend cannot be reproduced on the emulator,
so this uses a full power cycle: the harsher of the two for anything held in memory.

Requires tools/emu.sh boot 2 and the server on port 8787. Takes about ten minutes.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import time

from demo_e2e import FAR_LAT, FAR_LNG, LOT_ID, LOT_LAT, LOT_LNG, ROOT, ZONE, Car, Report, http

DEFAULT_SERVER = "http://10.0.2.2:8787"
UNREACHABLE_SERVER = "http://10.0.2.2:9"
SERVICE_START_SECONDS = 180


def power_off(vehicle: int) -> None:
    subprocess.run([str(ROOT / "tools/emu.sh"), "stop", str(vehicle)], check=True, timeout=60)
    time.sleep(3)


def power_on(car: Car, vehicle: int) -> None:
    subprocess.run([str(ROOT / "tools/emu.sh"), "boot", str(vehicle)], check=True, timeout=320)
    car.measure_clock()


def wait_for_service(car: Car, since: int) -> bool:
    return car.wait_for(r"service\] reconcile", timeout=SERVICE_START_SECONDS, since_ms=since) is not None


def park_in_zone(car: Car, zone: str) -> bool:
    since = car.now_ms()
    car.move(LOT_LAT, LOT_LNG)
    time.sleep(3)
    car.gear("Reverse")
    time.sleep(2)
    car.gear("Park")
    prompted = car.wait_for(r"notify\] post id=3", timeout=25, since_ms=since)
    if prompted and "어느 주차장" in prompted.text:
        car.probe("pick", LOT_ID)
        car.wait_for(r"notify\] post id=3 title=(?!어느)", timeout=8, since_ms=since)
    car.probe("zone", zone)
    return car.wait_for(rf"publish\] PARK lot={LOT_ID} zone={zone}", timeout=10, since_ms=since) is not None


def session_field(car: Car, name: str) -> str:
    since = car.now_ms() - 1000
    car.probe("state")
    entry = car.wait_for(r"state=SessionState", timeout=5, since_ms=since)
    match = re.search(rf"{name}=([^,)]+)", entry.text) if entry else None
    return match.group(1) if match else "?"


def events(server: str) -> list[dict]:
    return http(server, f"/v1/lots/{LOT_ID}/events?limit=200")["events"]


def of_type(items: list[dict], kind: str) -> list[dict]:
    return [event for event in items if event["type"] == kind]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", default="http://127.0.0.1:8787")
    args = parser.parse_args()
    server = args.server
    report = Report()
    car = Car("B", "emulator-5556", 8089)
    vehicle = 2
    car.measure_clock()
    car.prepare()
    car.probe("server", DEFAULT_SERVER)
    http(server, "/v1/admin/reset", "POST")
    car.leave_any_session()

    print("\n== the power goes off right after parking")
    report.check(park_in_zone(car, ZONE), "the car parks and publishes")
    time.sleep(2)
    power_off(vehicle)
    since = car.now_ms()
    power_on(car, vehicle)
    report.check(wait_for_service(car, since), "the service starts by itself after the power returns")
    time.sleep(2)
    report.check(session_field(car, "phase") == "PARKED", "the session is still PARKED")
    report.check(len(of_type(events(server), "PARK")) == 1, "the restart reports no second parking")
    print(f"  note  on screen after boot: {car.top_activity()}")

    print("\n== that car then leaves")
    since = car.now_ms()
    car.gear("Drive")
    report.check(car.wait_for(rf"publish\] LEAVING_SOON lot={LOT_ID} zone={ZONE}", timeout=10, since_ms=since) is not None,
                 "the departure still knows its zone")
    car.speed(20)
    report.check(car.wait_for(rf"publish\] VACATED lot={LOT_ID}", timeout=10, since_ms=since) is not None,
                 "movement confirms it")
    car.speed(0)
    car.move(FAR_LAT, FAR_LNG)
    car.wait_for(r"publish\] EXIT", timeout=15, since_ms=since)

    print("\n== no signal underground, then the power goes off")
    http(server, "/v1/admin/reset", "POST")
    car.probe("server", UNREACHABLE_SERVER)
    time.sleep(1)
    before_parking = int(time.time() * 1000)
    report.check(park_in_zone(car, ZONE), "the car parks while it cannot reach the server")
    time.sleep(3)
    report.check(not events(server), "nothing has arrived")
    power_off(vehicle)
    since = car.now_ms()
    time.sleep(5)
    power_on(car, vehicle)
    report.check(wait_for_service(car, since), "the service starts again")
    time.sleep(2)
    report.check(not events(server), "still nothing has arrived, the server is still out of reach")
    back_at = int(time.time() * 1000)
    car.probe("server", DEFAULT_SERVER)
    car.probe("demo", "false")  # Flushes the queue, as the next event or screen-on would.
    time.sleep(4)
    arrived = of_type(events(server), "PARK")
    report.check(len(arrived) == 1, "the queued parking arrives once", f"count={len(arrived)}")
    if arrived:
        at = arrived[0]["occurred_at"]
        report.check(before_parking - 2000 <= at < back_at, "it keeps the time it really happened",
                     f"{(back_at - at) / 1000:.0f} s before delivery")

    print("\n== the car drives off before the infotainment has booted")
    power_off(vehicle)
    since = car.now_ms()
    power_on(car, vehicle)
    # Android is up but the service has not started yet: it takes tens of seconds after boot.
    car.gear("Drive")
    car.move(FAR_LAT, FAR_LNG)
    report.check(wait_for_service(car, since), "the service starts")
    car.move(FAR_LAT + 0.0001, FAR_LNG)
    vacated = car.wait_for(rf"publish\] VACATED lot={LOT_ID}", timeout=30, since_ms=since)
    report.check(vacated is not None, "the departure it missed is still reported")
    time.sleep(3)
    late = of_type(events(server), "VACATED")
    report.check(bool(late) and late[0]["evidence"] in ("RECOVERED", "LEFT_LOT"),
                 "and it records how the departure was inferred", str(late[0]["evidence"]) if late else "")
    report.check(session_field(car, "phase") in ("DRIVING", "VACATED"), "the session closes", session_field(car, "phase"))
    return report.finish()


if __name__ == "__main__":
    raise SystemExit(main())
