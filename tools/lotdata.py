"""Loaders and classifiers for the KOTSA parking datasets in data/.

Encodes the data traps found in the review (design doc section 2.1), so that no other
code has to rediscover them: the ghost 44xxx block, "0 remaining" meaning "no data",
and the same lot appearing under two management numbers.
"""
from __future__ import annotations

import csv
import math
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

SEOUL_AREA = {"lat": (37.41, 37.72), "lng": (126.73, 127.20)}

# Realtime row classes.
GHOST = "ghost_block"          # 44xxx IDs: no value ever reported, 3.5e8 spaces in total.
INVALID = "invalid"            # Non-positive total, or remaining above total.
NO_DATA = "no_data"            # Remaining 0 with blank or 0 code. NOT the same as full.
DEFAULT_SUSPECT = "default"    # Blank code with remaining == total: looks like an unset default.
VALUE_CODED = "value_coded"    # Congestion code present and consistent.
VALUE_UNCODED = "value_uncoded"  # Blank code, 0 < remaining < total.
USABLE = {VALUE_CODED, VALUE_UNCODED}

# Sharing tiers (design doc section 6.2).
AUTO = "AUTO"
ASK = "ASK"
NEVER = "NEVER"

RESIDENTIAL = re.compile(
    r"아파트|빌라|맨션|주공|오피스텔|자이|래미안|힐스테이트|푸르지오|e편한|아이파크|더샵|센트레빌"
    r"|롯데캐슬|두산위브|홈타운|단독|다세대|다가구|주택|거주자|내집|하우스|파크빌|연립|캐슬|팰리스|리버뷰|\d+단지"
)


@dataclass(frozen=True)
class Facility:
    id: str
    name: str
    address: str
    lat: float
    lng: float
    capacity: int


@dataclass(frozen=True)
class Realtime:
    id: str
    code: str
    total: int
    remain: int


def find_csv(data_dir: str | Path, keyword: str) -> Path:
    """Finds a dataset by keyword. Korean names on disk are NFD, so compare after normalizing."""
    for path in Path(data_dir).glob("*.csv"):
        if keyword in unicodedata.normalize("NFC", path.name):
            return path
    raise FileNotFoundError(f"no csv containing {keyword!r} in {data_dir}")


def _number(text: str | None) -> float | None:
    try:
        return float(text) if text and text.strip() else None
    except ValueError:
        return None


def _integer(text: str | None) -> int:
    value = _number(text)
    return int(value) if value is not None else 0


def in_korea(lat: float, lng: float) -> bool:
    return 33 < lat < 39 and 124 < lng < 132


def in_seoul_area(lat: float, lng: float) -> bool:
    return (
        SEOUL_AREA["lat"][0] < lat < SEOUL_AREA["lat"][1]
        and SEOUL_AREA["lng"][0] < lng < SEOUL_AREA["lng"][1]
    )


def read_facility_rows(path: Path) -> Iterator[dict[str, str]]:
    with open(path, encoding="cp949", errors="replace", newline="") as handle:
        yield from csv.DictReader(handle)


def facilities(rows: Iterable[dict[str, str]]) -> Iterator[Facility]:
    """Yields rows with coordinates inside Korea; all other rows cannot be located."""
    for row in rows:
        lat, lng = _number(row["주차장 입구위치 위도"]), _number(row["주차장 입구위치 경도"])
        if lat is None or lng is None or not in_korea(lat, lng):
            continue
        yield Facility(
            id=row["주차장 관리번호"].strip(),
            name=row["주차장명"].strip(),
            address=row["주차장 주소"].strip(),
            lat=lat,
            lng=lng,
            capacity=_integer(row["주차장의 총 주차구획 수"]),
        )


def read_realtime(path: Path) -> Iterator[Realtime]:
    with open(path, encoding="cp949", errors="replace", newline="") as handle:
        for row in csv.DictReader(handle):
            yield Realtime(
                id=row["주차장관리번호"].strip(),
                code=row["주차혼잡상태"].strip(),
                total=_integer(row["총주차구획수"]),
                remain=_integer(row["총잔여주차구획수"]),
            )


def classify_realtime(row: Realtime) -> str:
    if row.id.startswith("44") and row.remain == 0 and row.code == "":
        return GHOST
    if row.total <= 0 or row.remain > row.total:
        return INVALID
    if row.code in ("1", "2", "3"):
        return VALUE_CODED
    if row.remain == 0:
        return NO_DATA
    if row.code == "0":
        return VALUE_CODED
    return DEFAULT_SUSPECT if row.remain == row.total else VALUE_UNCODED


def congestion_level(row: Realtime) -> int | None:
    """Official scale: 0 free, 1 normal, 2 busy, 3 full. Uncoded rows use the observed boundaries."""
    kind = classify_realtime(row)
    if kind == VALUE_CODED:
        return int(row.code)
    if kind == VALUE_UNCODED:
        occupancy = 1 - row.remain / row.total
        return 0 if occupancy < 0.5 else 1 if occupancy < 0.7 else 2 if occupancy < 0.9 else 3
    return None


def share_tier(name: str, capacity: int, min_capacity: int = 30, whitelisted: bool = False) -> str:
    """Whitelist wins. Otherwise auto-sharing is deliberately narrow: name matching is unreliable,
    and a wrong AUTO would publish someone's home parking."""
    if whitelisted:
        return AUTO
    if capacity < min_capacity or not name or RESIDENTIAL.search(name):
        return NEVER
    return AUTO if "공영" in name else ASK


def radius_m(capacity: int) -> int:
    """Geofence radius grows with size: the point may be a building centroid, not the entrance."""
    if capacity >= 3000:
        return 500
    if capacity >= 1000:
        return 300
    if capacity >= 300:
        return 150
    return 100


def dedupe(rows: Iterable[Facility], preferred_ids: set[str] = frozenset()) -> list[tuple[Facility, list[str]]]:
    """Merges rows at the same point (about 10 m), keeping every management number for later API joins.

    Preference: rows with a usable public value, then larger capacity, then a name.
    """
    groups: dict[tuple[float, float], list[Facility]] = {}
    for row in rows:
        groups.setdefault((round(row.lat, 4), round(row.lng, 4)), []).append(row)
    merged = []
    for members in groups.values():
        members.sort(key=lambda r: (r.id in preferred_ids, r.capacity, bool(r.name)), reverse=True)
        merged.append((members[0], [m.id for m in members[1:]]))
    return merged


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi, dlng = phi2 - phi1, math.radians(lng2 - lng1)
    h = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlng / 2) ** 2
    return 2 * 6371000 * math.asin(math.sqrt(h))
