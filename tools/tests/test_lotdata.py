"""Pins the data traps from design doc section 2.1, so a refactor cannot quietly reintroduce them."""
import csv
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

import build_lot_catalog as builder  # noqa: E402
import lotdata as ld  # noqa: E402


def rt(code="", total=100, remain=0, id_="93001-12211-00010-00-1"):
    return ld.Realtime(id=id_, code=code, total=total, remain=remain)


def fac(id_, name="테스트", lat=37.5, lng=127.0, capacity=100):
    return ld.Facility(id=id_, name=name, address="", lat=lat, lng=lng, capacity=capacity)


@pytest.mark.parametrize(
    "row, expected",
    [
        (rt(id_="44660-29299-00001-00-1", total=1000), ld.GHOST),
        (rt(id_="44660-29299-00001-00-1", total=0), ld.GHOST),
        (rt(total=0), ld.INVALID),
        (rt(total=10, remain=11), ld.INVALID),
        (rt(code="", remain=0), ld.NO_DATA),
        (rt(code="0", remain=0), ld.NO_DATA),
        (rt(code="3", remain=0), ld.VALUE_CODED),
        (rt(code="0", remain=60), ld.VALUE_CODED),
        (rt(code="", remain=40), ld.VALUE_UNCODED),
        (rt(code="", remain=100), ld.DEFAULT_SUSPECT),
    ],
)
def test_classify_realtime(row, expected):
    assert ld.classify_realtime(row) == expected


def test_a_real_value_on_a_44_id_is_not_treated_as_ghost():
    assert ld.classify_realtime(rt(id_="44660-19299-00001-00-1", code="2", remain=10)) == ld.VALUE_CODED


def test_zero_remaining_without_a_code_is_not_full():
    assert ld.congestion_level(rt(code="", remain=0)) is None
    assert ld.congestion_level(rt(code="0", remain=0)) is None


def test_code_three_is_full():
    assert ld.congestion_level(rt(code="3", remain=0)) == 3


@pytest.mark.parametrize("remain, level", [(60, 0), (40, 1), (20, 2), (5, 3)])
def test_uncoded_level_follows_observed_boundaries(remain, level):
    assert ld.congestion_level(rt(code="", remain=remain)) == level


@pytest.mark.parametrize(
    "name, capacity, whitelisted, expected",
    [
        ("세종로 공영주차장", 1260, False, ld.AUTO),
        ("코엑스 주차장", 2123, False, ld.ASK),
        ("코엑스 주차장", 2123, True, ld.AUTO),
        ("현대아파트", 500, False, ld.NEVER),
        ("경희궁의아침 3단지", 500, False, ld.NEVER),
        ("타워팰리스", 3695, False, ld.NEVER),
        ("", 500, False, ld.NEVER),
        ("작은 공영주차장", 10, False, ld.NEVER),
    ],
)
def test_share_tier(name, capacity, whitelisted, expected):
    assert ld.share_tier(name, capacity, whitelisted=whitelisted) == expected


def test_apartment_is_never_auto_even_with_public_word():
    assert ld.share_tier("공영아파트", 300) == ld.NEVER


@pytest.mark.parametrize("capacity, radius", [(50, 100), (299, 100), (300, 150), (1000, 300), (5413, 500)])
def test_radius_grows_with_capacity(capacity, radius):
    assert ld.radius_m(capacity) == radius


def test_dedupe_keeps_joinable_id_and_all_alternatives():
    a = fac("A", capacity=982)
    b = fac("B", capacity=982, lat=37.50001)
    merged = ld.dedupe([a, b], preferred_ids={"B"})
    assert len(merged) == 1
    assert merged[0][0].id == "B"
    assert merged[0][1] == ["A"]


def test_dedupe_keeps_lots_at_different_points():
    assert len(ld.dedupe([fac("A"), fac("B", lat=37.51)])) == 2


def test_facilities_drops_rows_that_cannot_be_located():
    base = {"주차장 관리번호": "X", "주차장명": "n", "주차장 주소": "", "주차장의 총 주차구획 수": "10"}
    rows = [
        {**base, "주차장 입구위치 위도": "37.5", "주차장 입구위치 경도": "127.0"},
        {**base, "주차장 입구위치 위도": "", "주차장 입구위치 경도": ""},
        {**base, "주차장 입구위치 위도": "1.0", "주차장 입구위치 경도": "1.0"},
    ]
    assert len(list(ld.facilities(rows))) == 1


def test_haversine_known_distance():
    assert ld.haversine_m(37.5, 127.0, 37.5, 127.0) == 0
    assert ld.haversine_m(37.5, 127.0, 37.6, 127.0) == pytest.approx(11119, abs=20)


def test_find_csv_matches_nfd_names(tmp_path):
    import unicodedata
    (tmp_path / unicodedata.normalize("NFD", "한국교통안전공단_주차장시설정보.csv")).write_text("x")
    assert ld.find_csv(tmp_path, "시설").exists()


needs_data = pytest.mark.skipif(not (ROOT / "data").exists(), reason="raw CSV files are not in the repository")


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    out = tmp_path_factory.mktemp("catalog")
    tsv, report = out / "lots.tsv", out / "quality.json"
    quality = builder.build(ROOT / "data", tsv, report, ROOT / "tools/lots_whitelist.csv", 30)
    with open(tsv, encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    return quality, rows, json.loads(report.read_text(encoding="utf-8"))


@needs_data
def test_quality_report_matches_the_reviewed_figures(built):
    quality, _, written = built
    assert written == quality
    assert quality["facility"]["rows"] == 222709
    assert quality["realtime"]["rows"] == 787229
    assert quality["realtime"]["ghost_block"]["rows"] == 713447
    assert quality["realtime"]["usable_rows"] == 2205
    assert quality["join"]["shared_ids"] == 2035
    assert quality["join"]["usable_lots_after_dedupe"] == 980


@needs_data
def test_catalog_holds_no_public_snapshot_values(built):
    _, rows, _ = built
    assert set(rows[0]) == set(builder.COLUMNS)
    assert not {"remain", "code", "congestion"} & set(rows[0])


@needs_data
def test_every_whitelisted_lot_is_auto(built):
    _, rows, _ = built
    with open(ROOT / "tools/lots_whitelist.csv", encoding="utf-8", newline="") as handle:
        wanted = {row["id"] for row in csv.DictReader(handle)}
    tiers = {}
    for row in rows:
        for lot_id in [row["id"], *filter(None, row["alt_ids"].split(","))]:
            tiers[lot_id] = row["tier"]
    assert {tiers.get(lot_id) for lot_id in wanted} == {ld.AUTO}


@needs_data
def test_large_apartment_is_never_shared(built):
    _, rows, _ = built
    tier = {row["name"]: row["tier"] for row in rows}
    assert tier["파크리오"] in (ld.ASK, ld.NEVER)
    assert tier["타워팰리스"] == ld.NEVER
