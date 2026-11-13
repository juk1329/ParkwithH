#!/usr/bin/env python
"""Checks the promises that are not about parking itself, on Vehicle 2 with the debug build.

A driver is promised that nothing can be touched while moving, that switching sharing off stops
sharing at once, that withdrawing consent removes everything and keeps the app idle after a
restart, that a long time without signal loses nothing, and that the app stops talking when a call
or another announcement takes the speakers.

Requires tools/emu.sh boot 2 and the server on port 8787. One power cycle; takes about six minutes.
"""
from __future__ import annotations

import argparse
import re
import time

from demo_e2e import FAR_LAT, FAR_LNG, LOT_ID, PACKAGE, Car, Report, http
from recovery_e2e import DEFAULT_SERVER, UNREACHABLE_SERVER, events, of_type, park_in_zone, power_off, power_on, session_field, wait_for_service

FLOOD_LOT = "59446-29391-00005-00-1"  # The lot the "flood" probe writes its harmless events for.
FLOOD = 201                            # One more than the server takes in a single request.
ZONE = "B2-B"
INPUT_SCREENS = ("층·구역 입력", "데이터 삭제와 동의 철회", "동의하고 시작")
LONG_SENTENCE = "오디오 포커스를 확인하는 긴 문장입니다. 이 문장이 끝나기 전에 다른 소리가 스피커를 가져갑니다. 하나 둘 셋 넷 다섯 여섯 일곱."


def screen_texts(car: Car) -> list[str]:
    car.shell("uiautomator", "dump", "/sdcard/parkwithh_window.xml")
    return re.findall(r'text="([^"]+)"', car.shell("cat", "/sdcard/parkwithh_window.xml"))


def app_files(car: Car) -> str:
    return car.shell("run-as", PACKAGE, "--user", "10", "ls", "files")


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

    print("\n== a start while moving shows nothing to touch")
    car.gear("Drive")
    car.shell("am", "start", "-S", "--user", "10", "-n", f"{PACKAGE}/.MainActivity")
    time.sleep(7)
    texts = screen_texts(car)
    shown = [t for t in texts if t in INPUT_SCREENS]
    report.check(bool(texts) and not shown, "opened in D, the app shows no input screen", str(texts)[:140])
    car.gear("Park")
    time.sleep(4)
    report.check("층·구역 입력" in screen_texts(car), "back in P, the home screen returns")
    since = car.now_ms()
    car.wait_for(r"service\] reconcile", timeout=60, since_ms=since - 20_000)
    car.probe("demo", "false")
    car.probe("server", DEFAULT_SERVER)
    car.show_navigation()
    car.leave_any_session()

    print("\n== sharing switched off while parked stops that very parking from being shared")
    report.check(park_in_zone(car, ZONE), "the car parks and publishes")
    car.probe("share", f"{LOT_ID},false")
    time.sleep(1)
    since = car.now_ms()
    car.gear("Drive")
    car.speed(20)
    time.sleep(3)
    car.speed(0)
    car.move(FAR_LAT, FAR_LNG)
    time.sleep(8)
    published = [e.text for e in car.log(since) if "publish]" in e.text]
    report.check(not published, "the departure is not published", str(published)[:120])
    kinds = sorted({event["type"] for event in events(server)})
    report.check(kinds == ["PARK"], "the server holds the parking and nothing after it", str(kinds))
    report.check(session_field(car, "phase") == "DRIVING", "the session still ended in the car")
    car.probe("share", f"{LOT_ID},true")

    print("\n== a long time offline drains in batches the server accepts")
    http(server, "/v1/admin/reset", "POST")
    car.probe("server", UNREACHABLE_SERVER)
    time.sleep(1)
    car.probe("flood", str(FLOOD))
    time.sleep(10)  # The first try fails after its timeout; the rest only go to disk.
    report.check(not http(server, f"/v1/lots/{FLOOD_LOT}/events?limit=500")["events"], "nothing arrives while unreachable")
    since = car.now_ms()
    car.probe("server", DEFAULT_SERVER)
    car.probe("flush")
    time.sleep(6)
    arrived = http(server, f"/v1/lots/{FLOOD_LOT}/events?limit=500")["events"]
    report.check(len(arrived) == FLOOD, f"all {FLOOD} queued events arrive", f"count={len(arrived)}")
    batches = [e.text for e in car.log(since) if "backend] send count=" in e.text]
    report.check(any("count=200 status=200" in b for b in batches) and any("count=1 status=200" in b for b in batches),
                 "they went as a full batch and the remainder", str(batches)[:140])

    print("\n== another sound taking the speakers stops the sentence")
    since = car.now_ms()
    car.probe("speak", LONG_SENTENCE)
    report.check(car.wait_for(r"voice\] start", timeout=8, since_ms=since) is not None, "a long sentence starts")
    car.probe("focus-steal")
    lost = car.wait_for(r"voice\] finished focus lost", timeout=4, since_ms=since)
    report.check(lost is not None, "it stops when focus is lost")
    since_second = car.now_ms()
    car.probe("speak", "포커스가 없는 동안의 문장입니다.")
    time.sleep(3)
    outcome = [e.text for e in car.log(since_second) if "voice] focus result" in e.text or "voice] finished" in e.text]
    print(f"  note  while another client holds focus: {outcome}")
    car.probe("focus-release")
    time.sleep(1)
    since_third = car.now_ms()
    car.probe("speak", "다시 안내합니다.")
    report.check(car.wait_for(r"voice\] finished done", timeout=10, since_ms=since_third) is not None,
                 "the next sentence is spoken once focus is free")

    print("\n== consent withdrawn: everything goes, and a restart starts nothing")
    http(server, "/v1/admin/reset", "POST")
    car.leave_any_session()
    report.check(park_in_zone(car, ZONE), "the car parks and publishes")
    time.sleep(2)
    report.check(len(of_type(events(server), "PARK")) == 1, "the parking reached the server")
    car.probe("server", UNREACHABLE_SERVER)
    time.sleep(1)
    car.gear("Drive")  # A departure that cannot be delivered waits in the queue.
    time.sleep(3)
    before = app_files(car)
    report.check("session.json" in before and "events.queue" in before, "a session and unsent events are on disk", before.replace("\n", " "))
    car.probe("withdraw")
    time.sleep(3)
    after = app_files(car)
    report.check("session.json" not in after and "events.queue" not in after, "withdrawing removes both", after.replace("\n", " "))
    running = "ParkingService" in car.shell("dumpsys", "activity", "services", PACKAGE)
    report.check(not running, "the service has stopped")
    power_off(vehicle)
    since = car.now_ms()
    power_on(car, vehicle)
    booted = car.wait_for(r"boot\] boot completed", timeout=180, since_ms=since)
    time.sleep(20)
    woke = [e.text for e in car.log(since) if re.search(r"service\] (created|reconcile)|location\]|proximity\]", e.text)]
    report.check(booted is not None and not woke, "after a restart the app does nothing: no service, no position", str(woke)[:120])
    time.sleep(3)
    kinds = sorted({event["type"] for event in events(server)})
    report.check(kinds == ["PARK"], "the unsent departure was never delivered", str(kinds))

    print("\n== consent given again starts clean")
    car.gear("Park")
    car.prepare()
    report.check(session_field(car, "phase") == "DRIVING" and session_field(car, "lot") == "null",
                 "no earlier session comes back", f"phase={session_field(car, 'phase')} lot={session_field(car, 'lot')}")
    return report.finish()


if __name__ == "__main__":
    raise SystemExit(main())
