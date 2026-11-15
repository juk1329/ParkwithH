"""The demo console drives emulators from a browser, so it must refuse anything but its own fixed buttons."""
import http.client
import sys
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import demo_console as dc  # noqa: E402


class FakeCar:
    def __init__(self):
        self.calls = []

    def gear(self, letter):
        self.calls.append(("gear", letter))

    def move(self, place):
        self.calls.append(("move", place))


class FakeConsole:
    def __init__(self):
        self.cars = {"A": FakeCar(), "B": FakeCar()}
        self.lock = threading.Lock()
        self.note = ""
        self.virtual_calls = []

    def virtual(self, what):
        self.virtual_calls.append(what)
        return what


@pytest.fixture
def console():
    fake = FakeConsole()
    server = ThreadingHTTPServer(("127.0.0.1", 0), dc.handler_for(fake, 0))
    port = server.server_address[1]
    server.RequestHandlerClass = dc.handler_for(fake, port)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield fake, port
    server.shutdown()
    server.server_close()


def post(port, path, headers):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    connection.request("POST", path, body=b"", headers=headers)
    status = connection.getresponse().status
    connection.close()
    return status


def test_a_fixed_button_reaches_the_car(console):
    fake, port = console
    assert post(port, "/api/car/B/gear/R", {"Host": f"127.0.0.1:{port}", "X-Demo-Console": "1"}) == 200
    assert post(port, "/api/car/A/place/lot", {"Host": f"localhost:{port}", "X-Demo-Console": "1"}) == 200
    assert post(port, "/api/car/B/place/near", {"Host": f"127.0.0.1:{port}", "X-Demo-Console": "1"}) == 200
    assert fake.cars["B"].calls == [("gear", "R"), ("move", "near")]
    assert fake.cars["A"].calls == [("move", "lot")]


def test_a_virtual_car_is_one_of_the_fixed_choices(console):
    fake, port = console
    headers = {"Host": f"127.0.0.1:{port}", "X-Demo-Console": "1"}
    assert post(port, "/api/virtual/seed", headers) == 200
    assert post(port, "/api/virtual/take", headers) == 200
    assert fake.virtual_calls == ["seed", "take"]


def test_virtual_events_are_fixed_and_pass_the_servers_own_checks():
    from server.app import EventIn

    for events, note in dc.VIRTUAL.values():
        assert note.startswith("가상 차량")
        for type_, zone, session, ago in events:
            assert session.startswith("virtual-")
            EventIn(eventId="e", sessionId=session, type=type_, lotId=dc.LOT_ID, zoneId=zone, occurredAt=1 + ago)


def test_every_scene_has_a_button_and_the_near_point_is_inside_a_hundred_metres_of_the_edge():
    import math

    assert len(dc.SCENES) == 7
    assert all({"car", "title", "button", "screen", "done"} <= set(scene) for scene in dc.SCENES)
    (lat, lng), (near_lat, near_lng) = dc.PLACES["lot"], dc.PLACES["near"]
    metres = math.hypot((near_lat - lat) * 111_320, (near_lng - lng) * 111_320 * math.cos(math.radians(lat)))
    coex_radius = 300  # From the catalog row of this lot.
    assert 0 < metres - coex_radius <= 100


@pytest.mark.parametrize("headers", [
    {},  # A plain form post from another page cannot add the header.
    {"X-Demo-Console": "1", "Host": "attacker.example:8790"},  # A rebound name still sends its own host.
])
def test_requests_from_elsewhere_are_refused(console, headers):
    fake, port = console
    headers.setdefault("Host", f"127.0.0.1:{port}")
    assert post(port, "/api/car/A/gear/D", headers) == 403
    assert fake.cars["A"].calls == []


@pytest.mark.parametrize("path", [
    "/api/car/C/gear/P",
    "/api/car/A/gear/X",
    "/api/car/A/place/37.5,127.0",
    "/api/car/A/gear/P;reboot",
    "/api/scene/8",
    "/api/virtual/anything",
    "/api/car/A/place/gate",
    "/api/scene/1/../reset",
])
def test_only_fixed_choices_match(console, path):
    fake, port = console
    assert post(port, path, {"Host": f"127.0.0.1:{port}", "X-Demo-Console": "1"}) == 404
    assert fake.cars["A"].calls == [] and fake.cars["B"].calls == []
    assert fake.virtual_calls == []
