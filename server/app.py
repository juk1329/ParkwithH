"""Relay server. Stores anonymous events per lot, builds the zone board, and pushes signals to cars.

Run: uvicorn server.app:app --host 0.0.0.0 --port 8787
The emulator reaches this machine at http://10.0.2.2:8787.
"""
from __future__ import annotations

import asyncio
import csv
import json
import os
import re
import sqlite3
import threading
import time
from dataclasses import asdict
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse, StreamingResponse
from pydantic import BaseModel, Field, field_validator

from server import board

ROOT = Path(__file__).resolve().parent.parent
CATALOG_PATH = Path(os.environ.get("PARKRELAY_CATALOG", ROOT / "app/src/main/assets/lots.tsv"))
DATABASE_PATH = os.environ.get("PARKRELAY_DB", str(ROOT / "server/parkrelay.db"))
HEARTBEAT_SECONDS = 15
ZONE_PATTERN = re.compile(r"^[0-9A-Za-z가-힣-]{1,16}$")


class EventIn(BaseModel):
    """No vehicle identity exists in this schema: session ids are random and last one parking."""

    eventId: str = Field(min_length=1, max_length=64)
    sessionId: str = Field(min_length=1, max_length=64)
    type: Literal["PARK", "LEAVING_SOON", "LEAVING_CANCEL", "VACATED", "EXIT", "FULLNESS"]
    lotId: str = Field(min_length=1, max_length=32)
    zoneId: str | None = None
    occurredAt: int = Field(gt=0)
    searchSec: int | None = Field(default=None, ge=0, le=24 * 3600)
    evidence: Literal["SPEED", "GEAR_TIME", "LEFT_LOT", "RECOVERED"] | None = None
    weak: bool = False
    fullness: Literal["EASY", "NORMAL", "BUSY", "FULL"] | None = None

    @field_validator("zoneId")
    @classmethod
    def zone_is_short_label(cls, value: str | None) -> str | None:
        if value is not None and not ZONE_PATTERN.match(value):
            raise ValueError("zoneId must be a short floor and zone label")
        return value


class EventBatch(BaseModel):
    events: list[EventIn] = Field(max_length=200)


class Store:
    """SQLite behind one lock. Enough for a demo, and simple to explain."""

    def __init__(self, path: str):
        self.lock = threading.Lock()
        self.connection = sqlite3.connect(path, check_same_thread=False)
        self.connection.execute(
            """CREATE TABLE IF NOT EXISTS events (
                event_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, type TEXT NOT NULL,
                lot_id TEXT NOT NULL, zone_id TEXT, occurred_at INTEGER NOT NULL,
                search_sec INTEGER, evidence TEXT, weak INTEGER NOT NULL DEFAULT 0, fullness TEXT)"""
        )
        self.connection.execute("CREATE INDEX IF NOT EXISTS events_lot ON events(lot_id, occurred_at)")
        self.connection.commit()

    def add(self, event: board.Event) -> bool:
        """Returns False for a repeat: queued events can be sent more than once after a lost reply."""
        with self.lock:
            cursor = self.connection.execute(
                "INSERT OR IGNORE INTO events VALUES (?,?,?,?,?,?,?,?,?,?)",
                (event.event_id, event.session_id, event.type, event.lot_id, event.zone_id,
                 event.occurred_at, event.search_sec, event.evidence, int(event.weak), event.fullness),
            )
            self.connection.commit()
            return cursor.rowcount == 1

    def events(self, lot_id: str, since: int) -> list[board.Event]:
        with self.lock:
            rows = self.connection.execute(
                "SELECT event_id, session_id, type, lot_id, zone_id, occurred_at, search_sec, evidence, weak, fullness "
                "FROM events WHERE lot_id = ? AND occurred_at >= ? ORDER BY occurred_at",
                (lot_id, since),
            ).fetchall()
        return [board.Event(*row[:8], bool(row[8]), row[9]) for row in rows]

    def reset(self) -> None:
        with self.lock:
            self.connection.execute("DELETE FROM events")
            self.connection.commit()


class Hub:
    """Fans new signals out to cars currently watching a lot."""

    def __init__(self) -> None:
        self.subscribers: dict[str, set[asyncio.Queue]] = {}

    def subscribe(self, lot_id: str) -> asyncio.Queue:
        queue: asyncio.Queue = asyncio.Queue(maxsize=100)
        self.subscribers.setdefault(lot_id, set()).add(queue)
        return queue

    def unsubscribe(self, lot_id: str, queue: asyncio.Queue) -> None:
        self.subscribers.get(lot_id, set()).discard(queue)

    def publish(self, lot_id: str, message: dict) -> None:
        for queue in self.subscribers.get(lot_id, set()):
            if not queue.full():
                queue.put_nowait(message)


def load_lot_names(path: Path) -> dict[str, dict]:
    if not path.exists():
        return {}
    with open(path, encoding="utf-8", newline="") as handle:
        return {
            row["id"]: {"name": row["name"], "capacity": int(row["capacity"])}
            for row in csv.DictReader(handle, delimiter="\t")
        }


def now_ms() -> int:
    return int(time.time() * 1000)


def create_app(database_path: str = DATABASE_PATH, catalog_path: Path = CATALOG_PATH) -> FastAPI:
    app = FastAPI(title="ParkwithH", docs_url=None, redoc_url=None)
    store = Store(database_path)
    hub = Hub()
    lots = load_lot_names(catalog_path)
    app.state.store = store
    app.state.hub = hub

    @app.post("/v1/events")
    def post_events(batch: EventBatch) -> dict:
        accepted = 0
        now = now_ms()
        for item in batch.events:
            event = board.Event(
                event_id=item.eventId, session_id=item.sessionId, type=item.type, lot_id=item.lotId,
                zone_id=item.zoneId, occurred_at=min(item.occurredAt, now), search_sec=item.searchSec,
                evidence=item.evidence, weak=item.weak, fullness=item.fullness,
            )
            if not store.add(event):
                continue
            accepted += 1
            if board.should_push(event, now):
                # The event id lets a car recognize its own event. It links to nothing else.
                hub.publish(
                    event.lot_id,
                    {"eventId": event.event_id, "type": event.type, "zoneId": event.zone_id, "weak": event.weak},
                )
        return {"accepted": accepted, "received": len(batch.events)}

    def board_payload(lot_id: str) -> dict:
        now = now_ms()
        events = store.events(lot_id, now - board.SESSION_TTL)
        views = board.build_board(events, now)
        lot = lots.get(lot_id, {})
        return {
            "lotId": lot_id,
            "name": lot.get("name"),
            "registeredCapacity": lot.get("capacity"),
            "parkedAppCars": sum(v.parked_app_cars for v in views),
            "zones": [asdict(v) for v in views],
            "asOf": now,
        }

    @app.get("/v1/lots/{lot_id}/board")
    def get_board(lot_id: str) -> dict:
        return board_payload(lot_id)

    @app.get("/v1/lots/{lot_id}/events")
    def get_events(lot_id: str, limit: int = 50) -> dict:
        events = store.events(lot_id, now_ms() - board.SESSION_TTL)
        return {"events": [asdict(e) for e in events[-limit:]]}

    @app.get("/v1/lots/{lot_id}/stream")
    async def stream(lot_id: str) -> StreamingResponse:
        queue = hub.subscribe(lot_id)

        async def messages():
            try:
                yield ": connected\n\n"
                while True:
                    try:
                        message = await asyncio.wait_for(queue.get(), timeout=HEARTBEAT_SECONDS)
                        yield f"data: {json.dumps(message, ensure_ascii=False)}\n\n"
                    except asyncio.TimeoutError:
                        yield ": keep-alive\n\n"
            finally:
                hub.unsubscribe(lot_id, queue)

        return StreamingResponse(messages(), media_type="text/event-stream")

    @app.get("/v1/lots/{lot_id}/dashboard", response_class=HTMLResponse)
    def dashboard(lot_id: str) -> str:
        if lot_id not in lots and lots:
            raise HTTPException(status_code=404, detail="unknown lot")
        return (ROOT / "server/dashboard.html").read_text(encoding="utf-8").replace("__LOT_ID__", lot_id)

    @app.post("/v1/admin/reset")
    def reset() -> dict:
        store.reset()
        return {"reset": True}

    return app


app = create_app()
