"""Builds the per-zone board from raw events. Pure functions only, so every rule is easy to test.

The app sees only app cars. The board therefore never states the lot's number of free spaces. What
it offers a driver is a list of fresh facts per zone: how many app cars left in the last few minutes
without another app car parking there since, how long ago, and how many approaching cars already said
they are heading for that zone.
"""
from __future__ import annotations

from dataclasses import dataclass, field

MINUTE = 60_000

TOKEN_TTL = 10 * MINUTE          # A hypothesis until the refill time is measured in the field.
TOKEN_HIGH_CONFIDENCE = 3 * MINUTE
LEAVING_TTL = 3 * MINUTE
CLAIM_TTL = TOKEN_TTL            # A car that said where it is heading holds that for as long as the departure counts.
FULLNESS_TTL = 30 * MINUTE
SESSION_TTL = 48 * 60 * MINUTE   # Drops cars whose app was removed or that never reported leaving.

JUST_LEFT = "JUST_LEFT"
LEAVING_SOON = "LEAVING_SOON"
FULLNESS = "FULLNESS"
UNKNOWN = "UNKNOWN"
UNZONED = "UNZONED"              # Events from drivers who did not give a zone.


@dataclass(frozen=True)
class Event:
    event_id: str
    session_id: str
    type: str
    lot_id: str
    zone_id: str | None
    occurred_at: int
    search_sec: int | None = None
    evidence: str | None = None
    weak: bool = False
    fullness: str | None = None


@dataclass
class ZoneView:
    zone_id: str
    status: str = UNKNOWN
    confidence: str | None = None
    parked_app_cars: int = 0
    vacancies: list[int] = field(default_factory=list)  # Age in milliseconds, newest first.
    leaving_soon: int = 0
    claims: int = 0              # Approaching cars that said they are heading here.
    confirmed: int = 0           # Of those, the cars already inside the lot.
    fullness: str | None = None
    fullness_age: int | None = None


def _zone_key(zone_id: str | None) -> str:
    return zone_id or UNZONED


def fresh_vacancies(events: list[Event], zone_id: str, now: int) -> list[int]:
    """Ages of unconsumed departure tokens in a zone.

    Each VACATED adds a token. A later PARK in the same zone takes the oldest token, since someone
    has probably filled that space. Tokens also expire, because a space does not stay empty for long.
    """
    tokens: list[int] = []
    arrived: set[str] = set()
    for event in sorted(events, key=lambda e: e.occurred_at):
        if _zone_key(event.zone_id) != zone_id:
            continue
        if event.type == "VACATED":
            tokens.append(event.occurred_at)
        elif event.type == "CLAIM_MISSED":
            # A driver went there and found no space: a car this app cannot see took what was left.
            tokens.clear()
        elif event.type == "PARK" and event.session_id not in arrived:
            # A session's first report here is its arrival. A repeat only restates where it is.
            arrived.add(event.session_id)
            if tokens and tokens[0] <= event.occurred_at:
                tokens.pop(0)
    return sorted((now - t for t in tokens if 0 <= now - t <= TOKEN_TTL))


def _session_positions(events: list[Event], now: int) -> dict[str, str]:
    """Where each still-parked session is: its latest reported zone, within the session TTL."""
    parked: dict[str, tuple[str, int]] = {}
    gone: set[str] = set()
    for event in sorted(events, key=lambda e: e.occurred_at):
        if event.type == "PARK":
            parked[event.session_id] = (_zone_key(event.zone_id), event.occurred_at)
        elif event.type in ("VACATED", "EXIT"):
            gone.add(event.session_id)
    return {
        session: zone
        for session, (zone, at) in parked.items()
        if session not in gone and now - at <= SESSION_TTL
    }


def _leaving_soon(events: list[Event], zone_id: str, now: int) -> int:
    latest: dict[str, Event] = {}
    for event in sorted(events, key=lambda e: e.occurred_at):
        if event.type in ("LEAVING_SOON", "LEAVING_CANCEL", "VACATED", "EXIT"):
            latest[event.session_id] = event
    return sum(
        1
        for event in latest.values()
        if event.type == "LEAVING_SOON"
        and not event.weak       # A door opening is a hint for the dashboard, not a space about to open.
        and _zone_key(event.zone_id) == zone_id
        and now - event.occurred_at <= LEAVING_TTL
    )


CLAIM_EVENTS = ("CLAIM", "CLAIM_CONFIRM", "CLAIM_CANCEL", "CLAIM_MISSED")


def active_claims(events: list[Event], now: int) -> dict[str, tuple[str, bool]]:
    """Which zone each approaching car is heading for, and whether it is confirmed, by approach id.

    The latest claim event of an approach decides: a new CLAIM moves it, CLAIM_CONFIRM says the car is
    now inside the lot, and a cancel, a miss or old age ends it. A claim says nothing about whether a
    space is there, so it is counted beside the departures and never subtracted here.
    """
    latest: dict[str, Event] = {}
    for event in sorted(events, key=lambda e: e.occurred_at):
        if event.type in CLAIM_EVENTS:
            latest[event.session_id] = event
    return {
        session: (event.zone_id, event.type == "CLAIM_CONFIRM")
        for session, event in latest.items()
        if event.type in ("CLAIM", "CLAIM_CONFIRM") and event.zone_id and 0 <= now - event.occurred_at <= CLAIM_TTL
    }


def _latest_fullness(events: list[Event], zone_id: str, now: int) -> tuple[str | None, int | None]:
    reports = [e for e in events if e.type == "FULLNESS" and _zone_key(e.zone_id) == zone_id and e.fullness]
    if not reports:
        return None, None
    latest = max(reports, key=lambda e: e.occurred_at)
    age = now - latest.occurred_at
    return (latest.fullness, age) if age <= FULLNESS_TTL else (None, None)


def build_board(events: list[Event], now: int) -> list[ZoneView]:
    """First matching rule wins: fresh departure, then leaving soon, then a driver report, then unknown."""
    positions = _session_positions(events, now)
    claims = active_claims(events, now)
    # A claim with no zone says nothing, and must not create an "unzoned" row.
    zones = {_zone_key(e.zone_id) for e in events if e.zone_id or e.type not in CLAIM_EVENTS}
    views = []
    for zone in sorted(zones):
        vacancies = fresh_vacancies(events, zone, now)
        leaving = _leaving_soon(events, zone, now)
        fullness, fullness_age = _latest_fullness(events, zone, now)
        view = ZoneView(
            zone_id=zone,
            parked_app_cars=sum(1 for z in positions.values() if z == zone),
            vacancies=vacancies,
            leaving_soon=leaving,
            claims=sum(1 for z, _ in claims.values() if z == zone),
            confirmed=sum(1 for z, sure in claims.values() if z == zone and sure),
            fullness=fullness,
            fullness_age=fullness_age,
        )
        if vacancies:
            view.status = JUST_LEFT
            view.confidence = "HIGH" if vacancies[0] <= TOKEN_HIGH_CONFIDENCE else "MEDIUM"
        elif leaving:
            view.status = LEAVING_SOON
            view.confidence = "LOW"
        elif fullness:
            view.status = FULLNESS
            view.confidence = "MEDIUM"
        views.append(view)
    return views


def should_push(event: Event, now: int) -> bool:
    """A car heading to the lot is told only what is recent. Old events flushed from a queue stay silent.

    VACATED and LEAVING_SOON are facts a driver may hear. The rest only change the list: a car parked
    into a departure, a departure was called off, or a car said where it is heading.
    """
    if event.type == "LEAVING_SOON":
        return now - event.occurred_at <= LEAVING_TTL
    if event.type in ("VACATED", "PARK", "LEAVING_CANCEL", *CLAIM_EVENTS):
        return now - event.occurred_at <= TOKEN_TTL
    return False
