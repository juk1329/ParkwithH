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
    assert fake.cars["B"].calls == [("gear", "R")]
    assert fake.cars["A"].calls == [("move", "lot")]


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
    "/api/scene/6",
    "/api/scene/1/../reset",
])
def test_only_fixed_choices_match(console, path):
    fake, port = console
    assert post(port, path, {"Host": f"127.0.0.1:{port}", "X-Demo-Console": "1"}) == 404
    assert fake.cars["A"].calls == [] and fake.cars["B"].calls == []
