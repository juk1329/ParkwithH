import asyncio
import csv

import pytest
from fastapi.testclient import TestClient

from server import board
from server.app import create_app

MIN = board.MINUTE
NOW = 1_000_000_000_000


def ev(id_, type_, at, zone="B2-B", session=None, **extra):
    return board.Event(id_, session or f"s-{id_}", type_, "L1", zone, at, **extra)


def view(views, zone="B2-B"):
    return next(v for v in views if v.zone_id == zone)


def test_a_departure_creates_a_token_with_high_confidence():
    z = view(board.build_board([ev("a", "VACATED", NOW - 2 * MIN)], NOW))
    assert z.status == board.JUST_LEFT
    assert z.confidence == "HIGH"
    assert z.vacancies == [2 * MIN]


def test_confidence_drops_after_three_minutes():
    z = view(board.build_board([ev("a", "VACATED", NOW - 5 * MIN)], NOW))
    assert (z.status, z.confidence) == (board.JUST_LEFT, "MEDIUM")


def test_a_token_expires():
    z = view(board.build_board([ev("a", "VACATED", NOW - 11 * MIN)], NOW))
    assert z.status == board.UNKNOWN
    assert z.vacancies == []


def test_a_later_arrival_consumes_the_oldest_token():
    events = [
        ev("a", "VACATED", NOW - 6 * MIN),
        ev("b", "VACATED", NOW - 2 * MIN),
        ev("c", "PARK", NOW - 1 * MIN),
    ]
    assert view(board.build_board(events, NOW)).vacancies == [2 * MIN]


def test_one_car_repeating_its_zone_uses_up_only_one_token():
    events = [
        ev("a", "VACATED", NOW - 3 * MIN),
        ev("b", "VACATED", NOW - 3 * MIN + 1),
        ev("c", "PARK", NOW - 2 * MIN, session="same"),
        ev("d", "PARK", NOW - 1 * MIN, session="same"),
    ]
    assert len(view(board.build_board(events, NOW)).vacancies) == 1


def test_an_arrival_before_the_departure_does_not_consume_it():
    events = [ev("c", "PARK", NOW - 5 * MIN), ev("a", "VACATED", NOW - 1 * MIN)]
    assert view(board.build_board(events, NOW)).vacancies == [1 * MIN]


def test_an_arrival_in_another_zone_does_not_consume_it():
    events = [ev("a", "VACATED", NOW - 2 * MIN), ev("c", "PARK", NOW - 1 * MIN, zone="B1-A")]
    assert view(board.build_board(events, NOW)).vacancies == [2 * MIN]


def test_parked_count_follows_sessions_and_never_goes_negative():
    events = [
        ev("p1", "PARK", NOW - 60 * MIN, session="s1"),
        ev("p2", "PARK", NOW - 50 * MIN, session="s2"),
        ev("v1", "VACATED", NOW - 1 * MIN, session="s1"),
    ]
    assert view(board.build_board(events, NOW)).parked_app_cars == 1


def test_a_later_zone_answer_moves_the_session_and_is_not_counted_twice():
    events = [
        ev("p1", "PARK", NOW - 10 * MIN, session="s1", zone=None),
        ev("p2", "PARK", NOW - 9 * MIN, session="s1", zone="B2-B"),
    ]
    views = board.build_board(events, NOW)
    assert view(views).parked_app_cars == 1
    assert view(views, board.UNZONED).parked_app_cars == 0


def test_a_car_gone_silent_leaves_the_count_after_the_ttl():
    events = [ev("p1", "PARK", NOW - board.SESSION_TTL - 1, session="s1")]
    assert view(board.build_board(events, NOW)).parked_app_cars == 0


def test_leaving_soon_shows_until_cancelled_or_old():
    soon = ev("l1", "LEAVING_SOON", NOW - 1 * MIN, session="s1")
    assert view(board.build_board([soon], NOW)).status == board.LEAVING_SOON

    cancel = ev("l2", "LEAVING_CANCEL", NOW - 30_000, session="s1")
    assert view(board.build_board([soon, cancel], NOW)).status == board.UNKNOWN

    old = ev("l3", "LEAVING_SOON", NOW - 4 * MIN, session="s9")
    assert view(board.build_board([old], NOW)).status == board.UNKNOWN


def test_a_door_opening_is_not_a_space_about_to_open():
    hint = ev("w", "LEAVING_SOON", NOW - 10_000, session="s1", weak=True)
    assert view(board.build_board([hint], NOW)).leaving_soon == 0
    gear = ev("g", "LEAVING_SOON", NOW - 5_000, session="s1")
    assert view(board.build_board([hint, gear], NOW)).leaving_soon == 1


def claims(events, zone="B2-B", now=NOW):
    return view(board.build_board(events, now), zone).claims


def test_a_claim_counts_until_cancelled_or_old():
    claim = ev("c", "CLAIM", NOW - 1 * MIN, session="a1")
    assert claims([claim]) == 1
    assert claims([claim, ev("x", "CLAIM_CANCEL", NOW - 30_000, session="a1")]) == 0
    assert claims([ev("c", "CLAIM", NOW - 11 * MIN, session="a1")]) == 0


def test_a_reclaim_moves_the_claim():
    events = [
        ev("c1", "CLAIM", NOW - 2 * MIN, session="a1"),
        ev("c2", "CLAIM", NOW - 1 * MIN, zone="B1-A", session="a1"),
    ]
    assert claims(events, "B2-B") == 0
    assert claims(events, "B1-A") == 1


def test_two_claims_on_one_departure_both_count():
    events = [
        ev("v", "VACATED", NOW - 2 * MIN),
        ev("c1", "CLAIM", NOW - 1 * MIN, session="a1"),
        ev("c2", "CLAIM", NOW - 30_000, session="a2"),
    ]
    z = view(board.build_board(events, NOW))
    assert (len(z.vacancies), z.claims) == (1, 2)


def test_a_claim_on_a_zone_without_news_creates_a_row_that_promises_nothing():
    z = view(board.build_board([ev("c", "CLAIM", NOW - 1 * MIN, session="a1")], NOW))
    assert (z.status, z.vacancies, z.claims) == (board.UNKNOWN, [], 1)


def test_parking_takes_the_departure_but_leaves_another_cars_claim():
    events = [
        ev("v", "VACATED", NOW - 3 * MIN),
        ev("c", "CLAIM", NOW - 2 * MIN, session="a1"),
        ev("p", "PARK", NOW - 1 * MIN, session="other"),
    ]
    z = view(board.build_board(events, NOW))
    assert (z.vacancies, z.claims, z.parked_app_cars) == ([], 1, 1)


def test_a_cancel_right_after_its_claim_wins():
    events = [
        ev("c", "CLAIM", NOW - 1000, session="a1"),
        ev("x", "CLAIM_CANCEL", NOW - 999, session="a1"),
    ]
    assert claims(events) == 0


def test_a_claim_without_a_zone_is_ignored_not_rejected():
    views = board.build_board([ev("c", "CLAIM", NOW - 1000, zone=None, session="a1")], NOW)
    assert views == []


def test_a_confirmed_claim_counts_as_a_claim_and_as_confirmed():
    events = [ev("v", "VACATED", NOW - 2 * MIN), ev("c", "CLAIM", NOW - 60_000, session="a1"),
              ev("k", "CLAIM_CONFIRM", NOW - 20_000, session="a1"), ev("d", "CLAIM", NOW - 10_000, session="a2")]
    z = view(board.build_board(events, NOW))
    assert (z.claims, z.confirmed) == (2, 1)
    assert view(board.build_board(events, NOW + 9 * MIN + 30_000)).confirmed == 1  # Ten minutes from the confirmation.
    assert view(board.build_board(events, NOW + 10 * MIN)).claims == 0


def test_a_confirmed_claim_ends_with_a_cancel_or_moves_with_a_new_claim():
    confirmed = [ev("c", "CLAIM_CONFIRM", NOW - 60_000, session="a1")]
    assert view(board.build_board(confirmed + [ev("x", "CLAIM_CANCEL", NOW - 1_000, session="a1")], NOW)).confirmed == 0
    moved = board.build_board(confirmed + [ev("m", "CLAIM", NOW - 1_000, zone="B1-A", session="a1")], NOW)
    assert (view(moved).claims, view(moved, "B1-A").claims, view(moved, "B1-A").confirmed) == (0, 1, 0)


def test_a_miss_ends_the_claim_and_drops_the_stale_departures_but_not_later_ones():
    events = [ev("v1", "VACATED", NOW - 5 * MIN), ev("v2", "VACATED", NOW - 4 * MIN),
              ev("other", "VACATED", NOW - 4 * MIN, zone="B1-A"),
              ev("k", "CLAIM_CONFIRM", NOW - 3 * MIN, session="a1"), ev("m", "CLAIM_MISSED", NOW - 2 * MIN, session="a1")]
    z = view(board.build_board(events, NOW))
    assert (z.vacancies, z.claims, z.confirmed) == ([], 0, 0)
    assert view(board.build_board(events, NOW), "B1-A").vacancies == [4 * MIN]
    later = events + [ev("v3", "VACATED", NOW - 1 * MIN)]
    assert view(board.build_board(later, NOW)).vacancies == [1 * MIN]


def test_confirmations_and_misses_reach_the_cars_watching():
    for type_ in ("CLAIM_CONFIRM", "CLAIM_MISSED"):
        assert board.should_push(ev("e", type_, NOW - 1_000), NOW)


def test_a_claim_is_not_a_parked_car():
    views = board.build_board([ev("c", "CLAIM", NOW - 1000, session="a1")], NOW)
    assert view(views).parked_app_cars == 0


def test_rules_apply_in_order():
    events = [
        ev("f", "FULLNESS", NOW - 5 * MIN, fullness="FULL", session="s3"),
        ev("l", "LEAVING_SOON", NOW - 1 * MIN, session="s2"),
        ev("v", "VACATED", NOW - 1 * MIN, session="s1"),
    ]
    assert view(board.build_board(events, NOW)).status == board.JUST_LEFT
    assert view(board.build_board(events[:2], NOW)).status == board.LEAVING_SOON
    assert view(board.build_board(events[:1], NOW)).status == board.FULLNESS


def test_an_old_driver_report_is_ignored():
    events = [ev("f", "FULLNESS", NOW - 31 * MIN, fullness="FULL")]
    assert view(board.build_board(events, NOW)).status == board.UNKNOWN


@pytest.mark.parametrize(
    "event, expected",
    [
        (ev("a", "VACATED", NOW - 1 * MIN), True),
        (ev("a", "VACATED", NOW - 11 * MIN), False),
        (ev("a", "LEAVING_SOON", NOW - 1 * MIN), True),
        (ev("a", "LEAVING_SOON", NOW - 4 * MIN), False),
        # These change the list a car on its way is looking at, so it must hear of them.
        (ev("a", "PARK", NOW), True),
        (ev("a", "PARK", NOW - 11 * MIN), False),
        (ev("a", "LEAVING_CANCEL", NOW), True),
        (ev("a", "CLAIM", NOW), True),
        (ev("a", "CLAIM", NOW - 11 * MIN), False),
        (ev("a", "CLAIM_CANCEL", NOW), True),
        (ev("a", "EXIT", NOW), False),
        (ev("a", "FULLNESS", NOW), False),
    ],
)
def test_only_fresh_useful_facts_are_pushed(event, expected):
    assert board.should_push(event, NOW) is expected


@pytest.fixture
def client(tmp_path):
    catalog = tmp_path / "lots.tsv"
    with open(catalog, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t")
        writer.writerow(["id", "name", "lat", "lng", "capacity", "tier", "radius_m", "alt_ids"])
        writer.writerow(["L1", "시험 공영주차장", "37.5", "127.0", "500", "AUTO", "150", ""])
    return TestClient(create_app(str(tmp_path / "test.db"), catalog))


def payload(event_id="e1", type_="VACATED", occurred=None, **extra):
    import time
    body = {
        "eventId": event_id, "sessionId": "s1", "type": type_, "lotId": "L1", "zoneId": "B2-B",
        "occurredAt": occurred or int(time.time() * 1000),
    }
    body.update(extra)
    return {"events": [body]}


def test_events_are_idempotent(client):
    assert client.post("/v1/events", json=payload()).json()["accepted"] == 1
    assert client.post("/v1/events", json=payload()).json()["accepted"] == 0
    assert len(client.get("/v1/lots/L1/events").json()["events"]) == 1


def test_board_reports_status_and_sample_size_without_free_spaces(client):
    client.post("/v1/events", json=payload("p", "PARK", sessionId="s2"))
    client.post("/v1/events", json=payload("v", "VACATED"))
    body = client.get("/v1/lots/L1/board").json()
    assert body["name"] == "시험 공영주차장"
    assert body["registeredCapacity"] == 500
    assert body["parkedAppCars"] == 1
    assert "free" not in str(body).lower()


@pytest.mark.parametrize(
    "bad",
    [
        {"type": "HACK"},
        {"zoneId": "B2; DROP TABLE events"},
        {"zoneId": "x" * 40},
        {"occurredAt": -1},
        {"searchSec": -5},
    ],
)
def test_invalid_input_is_rejected(client, bad):
    assert client.post("/v1/events", json=payload(**bad)).status_code == 422


def test_a_vehicle_identifier_field_is_not_stored(client):
    client.post("/v1/events", json=payload(vin="KMHXX00XXXX000000", plate="12가3456"))
    assert "KMHXX" not in str(client.get("/v1/lots/L1/events").json())


def test_future_timestamps_are_clamped(client):
    import time
    future = int(time.time() * 1000) + 3_600_000
    client.post("/v1/events", json=payload(occurred=future))
    stored = client.get("/v1/lots/L1/events").json()["events"][0]["occurred_at"]
    assert stored <= int(time.time() * 1000)


def test_reset_clears_events(client):
    client.post("/v1/events", json=payload())
    client.post("/v1/admin/reset")
    assert client.get("/v1/lots/L1/events").json()["events"] == []


def test_dashboard_serves_for_known_lots_only(client):
    assert "L1" in client.get("/v1/lots/L1/dashboard").text
    assert client.get("/v1/lots/NOPE/dashboard").status_code == 404


def test_new_signals_reach_subscribers_and_repeats_do_not(client):
    hub = client.app.state.hub

    async def scenario():
        queue = hub.subscribe("L1")
        client.post("/v1/events", json=payload("v1", "VACATED"))
        client.post("/v1/events", json=payload("v1", "VACATED"))
        client.post("/v1/events", json=payload("p1", "PARK"))
        return [queue.get_nowait() for _ in range(queue.qsize())]

    assert asyncio.run(scenario()) == [
        {"eventId": "v1", "type": "VACATED", "zoneId": "B2-B", "weak": False},
        {"eventId": "p1", "type": "PARK", "zoneId": "B2-B", "weak": False},
    ]


def test_a_claim_shows_on_the_board_beside_the_departure_it_is_heading_for(client):
    client.post("/v1/events", json=payload("v", "VACATED"))
    client.post("/v1/events", json=payload("c", "CLAIM", sessionId="approach-1"))
    zone = client.get("/v1/lots/L1/board").json()["zones"][0]
    assert (zone["zone_id"], len(zone["vacancies"]), zone["claims"]) == ("B2-B", 1, 1)

    client.post("/v1/events", json=payload("x", "CLAIM_CANCEL", sessionId="approach-1"))
    assert client.get("/v1/lots/L1/board").json()["zones"][0]["claims"] == 0


def test_an_older_server_field_set_is_still_there(client):
    # Cars built before claims existed read these keys with no default.
    client.post("/v1/events", json=payload("v", "VACATED"))
    zone = client.get("/v1/lots/L1/board").json()["zones"][0]
    assert {"zone_id", "vacancies", "leaving_soon", "parked_app_cars"} <= set(zone)


def test_health_names_the_api_version(client):
    assert client.get("/v1/health").json() == {"ok": True, "api": 3}
