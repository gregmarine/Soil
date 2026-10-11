"""The extension stores: SN's calendar, scratch pad and Bible into Soil's app stores; SN's tags read
for the index. The document store (a user dictionary) and the cloud store (an account) stay behind."""

from __future__ import annotations

import sqlite3
from pathlib import Path

from . import soilschema as S
from .items import Ctx
from .snread import load_table, table_exists

STROKE_COLS = ["id", "pageId", '"order"', "color", "width", "style", "blob"]


def _copy(src: sqlite3.Connection, dst: sqlite3.Connection, table: str, cols: list[str], extra: dict | None = None,
          where: str = "") -> int:
    rows = src.execute(f'SELECT {", ".join(cols)} FROM "{table}" {where}').fetchall()
    names = [c.strip('"') for c in cols]
    ex_names = list((extra or {}).keys())
    ex_vals = tuple((extra or {}).values())
    q = ", ".join(f'"{c}"' for c in names + ex_names)
    dst.executemany(f'INSERT OR IGNORE INTO "{table}" ({q}) VALUES ({",".join("?" for _ in names + ex_names)})',
                    [tuple(r) + ex_vals for r in rows])
    return len(rows)


def build_scratch(src: sqlite3.Connection, out: Path, ctx: Ctx) -> None:
    dst = S.create_plain(out, S.SCRATCH_DDL, S.SCRATCH_USER_VERSION)
    if table_exists(src, "page"):
        n = _copy(src, dst, "page", ["id", "position", "width", "height", "createdAt", "updatedAt"])
        ctx.bump("scratch pages", n)
        ctx.bump("scratch strokes", _copy(src, dst, "stroke", STROKE_COLS))
        if table_exists(src, "state"):
            _copy(src, dst, "state", ["key", "value"])
    dst.commit()
    dst.close()


def build_calendar(src: sqlite3.Connection, out: Path, ctx: Ctx, now: int) -> None:
    dst = S.create_plain(out, S.CALENDAR_DDL, S.CALENDAR_USER_VERSION)
    cal = {"calendarId": S.CALENDAR_ID}
    dst.execute("INSERT INTO calendar (id, name, createdAt) VALUES (?, 'Calendar', ?)", (S.CALENDAR_ID, now))
    if table_exists(src, "period"):
        ctx.bump("calendar periods", _copy(src, dst, "period", ["id", "kind", "date"], cal))
        ctx.bump("calendar pages", _copy(src, dst, "page", ["id", "periodId", "half", "width", "height", "createdAt", "updatedAt"]))
        ctx.bump("calendar strokes", _copy(src, dst, "stroke", STROKE_COLS))
        if table_exists(src, "state"):
            _copy(src, dst, "state", ["key", "value"], cal)
    if table_exists(src, "event"):
        ctx.bump("events", _copy(src, dst, "event", [
            "id", "type", "title", "startDate", "endDate", "allDay", "startMinute", "endMinute", "recurring", "freq",
            "interval", "monthlyMode", "endMode", "untilDate", "endCount", "noteText", "noteWidth", "noteHeight",
            "createdAt", "updatedAt"], cal))
        _copy(src, dst, "event_weekday", ["eventId", "weekday"])
        _copy(src, dst, "event_exception", ["eventId", "date"])
        _copy(src, dst, "event_reminder", ["eventId", "amount", "unit"])
        ctx.bump("event note strokes", _copy(src, dst, "note_stroke", ["id", "eventId", '"order"', "color", "width", "style", "blob"]))
    dst.commit()
    dst.close()


def build_bible(src: sqlite3.Connection, out: Path, ctx: Ctx) -> None:
    dst = S.create_plain(out, S.BIBLE_DDL, S.BIBLE_USER_VERSION)
    if table_exists(src, "state"):
        pos = src.execute("SELECT value FROM state WHERE key = 'position'").fetchone()
        if pos:
            dst.execute("INSERT INTO state (key, value) VALUES ('position', ?)", (pos[0],))
            ctx.bump("bible position")
    if table_exists(src, "recent"):
        ctx.bump("bible recents", _copy(src, dst, "recent", ["usfm", "chapter", "at"]))
    if table_exists(src, "recent_ref"):
        ctx.bump("bible recent references", _copy(src, dst, "recent_ref", ["ref", "at"]))
    dst.commit()
    dst.close()


def read_tags(src: sqlite3.Connection) -> tuple[list[dict], list[dict]]:
    tags = load_table(src, "tag") if table_exists(src, "tag") else []
    assigns = load_table(src, "assignment") if table_exists(src, "assignment") else []
    return tags, assigns


def count_words(src: sqlite3.Connection) -> int:
    return src.execute("SELECT count(*) FROM word").fetchone()[0] if table_exists(src, "word") else 0
