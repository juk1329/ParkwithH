#!/usr/bin/env python
"""Drives the emulator's Virtual Console from scripts.

Usage: vc.py PORT [METHOD] PATH [PATH...]
Example: vc.py 8088 /vehicle/gear?gear=Drive
         vc.py 8088 PUT "/vehicle/controls?accelerator=40&brake=0&steering=0"

The console rejects writes unless a browser session is alive, so this keeps a heartbeat
running. It is an unofficial route: keep manual console control as the backup at a demo.
"""
from __future__ import annotations

import json
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ATTEMPTS = 5


def call(base: str, path: str, method: str = "GET") -> tuple[int, str]:
    data = b"" if method != "GET" else None
    request = urllib.request.Request(base + path, method=method, data=data)
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            return response.status, response.read().decode(errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(errors="replace")
    except OSError as error:
        return 0, str(error)


def wanted_gear(path: str) -> str | None:
    query = urllib.parse.urlparse(path)
    if query.path != "/vehicle/gear":
        return None
    return urllib.parse.parse_qs(query.query).get("gear", [None])[0]


def actual_gear(base: str) -> str | None:
    status, body = call(base, "/vehicle/state")
    if status != 200:
        return None
    try:
        return json.loads(body)["gear"]["actual"]["value"]
    except (ValueError, KeyError, TypeError):
        return None


def send(base: str, path: str, method: str) -> tuple[int, str]:
    """The console can drop a connection under load, and a gear change can fail without saying so.

    A demo built on either would drift, so every request is retried and a gear is read back.
    """
    gear = wanted_gear(path)
    status, body = 0, ""
    for attempt in range(ATTEMPTS):
        if attempt:
            time.sleep(1)
        if gear is not None and attempt and actual_gear(base) == gear:
            return status or 200, body
        status, body = call(base, path, method)
        if status == 0 or status >= 500:  # Also says "emulator_inactive" until the heartbeat is seen.
            continue
        if gear is None or actual_gear(base) == gear:
            return status, body
    return (status if gear is None or actual_gear(base) == gear else 0), body


def main() -> int:
    port, args = sys.argv[1], sys.argv[2:]
    method = "GET"
    if args and args[0] in ("GET", "PUT", "POST"):
        method, args = args[0], args[1:]
    base = f"http://127.0.0.1:{port}"
    stop = threading.Event()

    def heartbeat() -> None:
        while not stop.is_set():
            call(base, f"/browser/session?client=vc-{port}", "POST")
            time.sleep(1)

    threading.Thread(target=heartbeat, daemon=True).start()
    time.sleep(1.5)
    worst = 0
    for path in args:
        status, body = send(base, path, method)
        print(f"{port} {method} {path} -> {status} {body[:600]}")
        worst = max(worst, 0 if 200 <= status < 300 else 1)
    time.sleep(0.5)
    stop.set()
    return worst


if __name__ == "__main__":
    sys.exit(main())
