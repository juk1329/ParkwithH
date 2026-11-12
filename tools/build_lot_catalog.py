#!/usr/bin/env python
"""Builds the in-car lot catalog and a data quality report from the raw KOTSA CSV files.

Usage: python tools/build_lot_catalog.py [--data data] [--out app/src/main/assets/lots.tsv]

Public remaining-space values are used only to keep the joinable management number when
duplicates merge. They are never written to the catalog: a stale snapshot must not reach
the car, because drivers would read it as the current state.
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
from pathlib import Path

import lotdata as ld

COLUMNS = ["id", "name", "lat", "lng", "capacity", "tier", "radius_m", "alt_ids"]
EMPTY_COLUMN_CHECK = [
    "주차장 설치일자", "주차장 폐지일자", "주차장 입출구 형태", "주차형식",
    "이용가능 차량유형_승용(소형)", "이용가능 차량유형_SUV",
    "주차구획 단위정보 수집장치 설치 여부", "영상식 수량", "초음파 수량", "방범 CCTV 설치대수",
]


def read_whitelist(path: Path) -> dict[str, str]:
    with open(path, encoding="utf-8", newline="") as handle:
        return {row["id"]: row["reason"] for row in csv.DictReader(handle)}


def build(data_dir: Path, out: Path, report: Path, whitelist_path: Path, min_capacity: int) -> dict:
    whitelist = read_whitelist(whitelist_path)
    facility_rows = list(ld.read_facility_rows(ld.find_csv(data_dir, "시설")))
    realtime_rows = list(ld.read_realtime(ld.find_csv(data_dir, "실시간")))

    usable_ids = {r.id for r in realtime_rows if ld.classify_realtime(r) in ld.USABLE}
    located = list(ld.facilities(facility_rows))
    missing_whitelist = sorted(set(whitelist) - {f.id for f in located})
    if missing_whitelist:
        raise SystemExit(f"whitelist ids not found in the facility file: {missing_whitelist}")

    candidates = [f for f in located if f.capacity >= min_capacity or f.id in whitelist]
    # A whitelisted row must survive deduplication, so it outranks a public-value row.
    merged = ld.dedupe(candidates, usable_ids | set(whitelist))

    catalog = []
    for row, others in merged:
        listed = row.id in whitelist or any(other in whitelist for other in others)
        tier = ld.share_tier(row.name, row.capacity, min_capacity, whitelisted=listed)
        catalog.append((row, others, tier))
    catalog.sort(key=lambda item: item[0].id)

    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(COLUMNS)
        for row, others, tier in catalog:
            name = row.name.replace("\t", " ")
            writer.writerow([
                row.id, name, f"{row.lat:.6f}", f"{row.lng:.6f}", row.capacity, tier,
                ld.radius_m(row.capacity), ",".join(others),
            ])

    kinds = collections.Counter(ld.classify_realtime(r) for r in realtime_rows)
    ghost = [r for r in realtime_rows if ld.classify_realtime(r) == ld.GHOST]
    realtime_ids = {r.id for r in realtime_rows}
    facility_ids = {r["주차장 관리번호"].strip() for r in facility_rows}
    located_ids = {f.id for f in located}
    usable_located = [f for f in located if f.id in usable_ids]
    usable_merged = ld.dedupe(usable_located, usable_ids)
    with_capacity = [f for f in located if f.capacity >= 1]
    over_100 = [f for f in located if f.capacity >= 100]
    coded = [r for r in realtime_rows if ld.classify_realtime(r) == ld.VALUE_CODED]

    quality = {
        "facility": {
            "rows": len(facility_rows),
            "located_rows": len(located),
            "filled_share": {
                column: round(sum(1 for r in facility_rows if r[column].strip()) / len(facility_rows), 3)
                for column in ("주차장명", "주차장 주소", "주차장 입구위치 위도", "주차장의 총 주차구획 수")
            },
            "columns_empty_or_all_zero": [
                c for c in EMPTY_COLUMN_CHECK if not any(r[c].strip() not in ("", "0") for r in facility_rows)
            ],
            "seoul_area_share": {
                "capacity_ge_1": round(sum(ld.in_seoul_area(f.lat, f.lng) for f in with_capacity) / len(with_capacity), 3),
                "capacity_ge_100": round(sum(ld.in_seoul_area(f.lat, f.lng) for f in over_100) / len(over_100), 3),
            },
        },
        "realtime": {
            "rows": len(realtime_rows),
            "kinds": dict(kinds),
            "usable_rows": sum(kinds[k] for k in ld.USABLE),
            "ghost_block": {
                "rows": len(ghost),
                "distinct_first_segments": len({r.id.split("-")[0] for r in ghost}),
                "total_spaces": sum(r.total for r in ghost),
            },
            "coded_mix": dict(collections.Counter(r.code for r in coded)),
        },
        "join": {
            "shared_ids": len(facility_ids & realtime_ids),
            "shared_ids_with_coordinates": len(located_ids & realtime_ids),
            "usable_with_coordinates": len(usable_located),
            "usable_lots_after_dedupe": len(usable_merged),
            "usable_lots_in_seoul_area": sum(ld.in_seoul_area(m[0].lat, m[0].lng) for m in usable_merged),
        },
        "catalog": {
            "min_capacity": min_capacity,
            "lots": len(catalog),
            "tiers": dict(collections.Counter(tier for _, _, tier in catalog)),
            "whitelist_size": len(whitelist),
            "bytes": out.stat().st_size,
        },
        "caveats": [
            "시설 파일은 2023-05-10 기준이고 서울 중심이다.",
            "실시간 파일은 2026-07-26의 1회 시점 값이며 시각이 없다. 회전율을 구할 수 없다.",
            "두 파일의 연결률이 낮아 공개값이 있는 주차장 수는 실제보다 적게 잡힐 수 있다.",
        ],
    }
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(quality, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return quality


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=root / "data")
    parser.add_argument("--out", type=Path, default=root / "app/src/main/assets/lots.tsv")
    parser.add_argument("--report", type=Path, default=root / "docs/data_quality.json")
    parser.add_argument("--whitelist", type=Path, default=root / "tools/lots_whitelist.csv")
    parser.add_argument("--min-capacity", type=int, default=30)
    args = parser.parse_args()
    quality = build(args.data, args.out, args.report, args.whitelist, args.min_capacity)
    print(json.dumps(quality, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
