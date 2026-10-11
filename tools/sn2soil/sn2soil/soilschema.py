"""Soil's on-disk shapes, as read from the Soil source on 2026-10-11 (`IndexSchema.kt`,
`ItemMeta.kt`, `SeamLinks.kt`, `NotebookSchema.kt`, `DocumentSchema.kt`, `SketchbookSchema.kt`,
`CalendarSchema.kt`, `BibleSchema.kt`, `ScratchSchema.kt`). Each file is created in its *final*
shape and stamped with the `user_version` Soil's `Schema.pending` expects for that shape: Soil
runs steps `user_version+1..N` with plain `CREATE` statements, so the number must match the tables.
Soil's `data/Schema.kt` refuses a file whose version is above its own."""

from __future__ import annotations

import sqlite3
from pathlib import Path

# ── the index ──────────────────────────────────────────────────────────────

INDEX_USER_VERSION = 11

KIND_NOTEBOOK, KIND_DOCUMENT, KIND_SKETCHBOOK = "notebook", "document", "sketchbook"
ITEM_FLAG_EXCLUDE_FROM_BACKUP = 1

INDEX_DDL = [
    """CREATE TABLE item (
           id TEXT PRIMARY KEY,
           kind TEXT NOT NULL,
           name TEXT NOT NULL,
           keyScope TEXT NOT NULL DEFAULT 'GLOBAL',
           flags INTEGER NOT NULL DEFAULT 0,
           createdAt INTEGER NOT NULL,
           updatedAt INTEGER NOT NULL,
           deletedAt INTEGER,
           pageCount INTEGER NOT NULL DEFAULT 0,
           openedAt INTEGER,
           parentId TEXT NOT NULL DEFAULT '',
           cover BLOB)""",
    "CREATE INDEX item_kind_alive ON item(kind, deletedAt)",
    "CREATE INDEX item_parent ON item(parentId, deletedAt)",
    "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    """CREATE TABLE link (
           id TEXT PRIMARY KEY,
           sourceItemId TEXT NOT NULL,
           sourcePageId TEXT NOT NULL,
           targetItemId TEXT NOT NULL,
           targetPageId TEXT,
           bibleWire TEXT,
           bibleStart INTEGER,
           bibleEnd INTEGER,
           calDate TEXT)""",
    "CREATE INDEX link_target ON link(targetItemId)",
    "CREATE INDEX link_source ON link(sourceItemId)",
    "CREATE INDEX link_bible ON link(bibleStart, bibleEnd)",
    "CREATE INDEX link_cal ON link(calDate)",
    """CREATE TABLE template (
           id TEXT PRIMARY KEY,
           parentId TEXT NOT NULL DEFAULT '',
           name TEXT NOT NULL,
           fit INTEGER NOT NULL DEFAULT 0,
           createdAt INTEGER NOT NULL,
           updatedAt INTEGER NOT NULL,
           deletedAt INTEGER,
           blob BLOB)""",
    "CREATE INDEX template_parent ON template(parentId, deletedAt)",
    """CREATE TABLE template_folder (
           id TEXT PRIMARY KEY,
           parentId TEXT NOT NULL DEFAULT '',
           name TEXT NOT NULL,
           createdAt INTEGER NOT NULL,
           updatedAt INTEGER NOT NULL,
           deletedAt INTEGER)""",
    "CREATE INDEX template_folder_parent ON template_folder(parentId, deletedAt)",
    "CREATE TABLE template_pin (id TEXT PRIMARY KEY, pinnedAt INTEGER NOT NULL)",
    """CREATE TABLE folder (
           id TEXT PRIMARY KEY,
           parentId TEXT NOT NULL DEFAULT '',
           name TEXT NOT NULL,
           createdAt INTEGER NOT NULL,
           updatedAt INTEGER NOT NULL,
           deletedAt INTEGER)""",
    "CREATE INDEX folder_parent ON folder(parentId, deletedAt)",
    "CREATE TABLE item_pin (id TEXT PRIMARY KEY, pinnedAt INTEGER NOT NULL)",
    "CREATE TABLE folder_prefs (folderId TEXT PRIMARY KEY, scheme TEXT, template TEXT)",
    """CREATE TABLE clipboard (
           kind TEXT PRIMARY KEY,
           payloadKind TEXT NOT NULL,
           sourceItemId TEXT NOT NULL,
           copiedAt INTEGER NOT NULL,
           blob BLOB NOT NULL)""",
    """CREATE TABLE tag (
           id TEXT PRIMARY KEY,
           display TEXT NOT NULL,
           identityKey TEXT NOT NULL UNIQUE,
           createdAt INTEGER NOT NULL)""",
    """CREATE TABLE tag_assignment (
           tagId TEXT NOT NULL,
           itemId TEXT NOT NULL,
           pageId TEXT NOT NULL DEFAULT '',
           createdAt INTEGER NOT NULL,
           PRIMARY KEY (tagId, itemId, pageId))""",
    "CREATE INDEX tag_assignment_target ON tag_assignment(itemId, pageId)",
    """CREATE TABLE item_page (
           itemId TEXT NOT NULL,
           pageId TEXT NOT NULL,
           position INTEGER NOT NULL,
           PRIMARY KEY (itemId, pageId))""",
    "CREATE INDEX item_page_item ON item_page(itemId, position)",
]

# ── every item file ────────────────────────────────────────────────────────

META_FORMAT = "1"
SOIL_META_DDL = "CREATE TABLE IF NOT EXISTS soil_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
SOIL_LINK_DDL = [
    "CREATE TABLE IF NOT EXISTS soil_link (id TEXT PRIMARY KEY, pageId TEXT NOT NULL, targetItemId TEXT NOT NULL, "
    "targetPageId TEXT, bibleWire TEXT, bibleStart INTEGER, bibleEnd INTEGER, calDate TEXT)",
    "CREATE INDEX IF NOT EXISTS soil_link_page ON soil_link(pageId)",
    "CREATE INDEX IF NOT EXISTS soil_link_bible ON soil_link(bibleStart, bibleEnd)",
    "CREATE INDEX IF NOT EXISTS soil_link_cal ON soil_link(calDate)",
]

ITEM_USER_VERSION = 1  # notebook, document and sketchbook each have one step

ROW_COLUMNS = ["id", "parentId", "type", '"order"', "createdAt", "updatedAt", "deletedAt", "text", "refId",
               "x", "y", "width", "height", "color", "strokeWidth", "style", "flags", "blob"]


def _paper_table(name: str) -> list[str]:
    return [
        f"""CREATE TABLE {name} (
               id          TEXT    NOT NULL PRIMARY KEY,
               parentId    TEXT    NOT NULL,
               type        TEXT    NOT NULL,
               "order"     INTEGER NOT NULL DEFAULT 0,
               createdAt   INTEGER NOT NULL,
               updatedAt   INTEGER NOT NULL,
               deletedAt   INTEGER,
               text        TEXT,
               refId       TEXT,
               x           REAL,
               y           REAL,
               width       REAL,
               height      REAL,
               color       TEXT,
               strokeWidth REAL,
               style       TEXT,
               flags       INTEGER,
               blob        BLOB)""",
        f'CREATE INDEX idx_{name}_parent_order ON {name}(parentId, "order", deletedAt)',
    ]


NOTEBOOK_DDL = _paper_table("notebook")
SKETCHBOOK_DDL = _paper_table("sketchbook")
INSERT_ROW = f"INSERT INTO {{table}} ({', '.join(ROW_COLUMNS)}) VALUES ({', '.join('?' for _ in ROW_COLUMNS)})"

DOCUMENT_DDL = [
    """CREATE TABLE document (
           id        TEXT    NOT NULL PRIMARY KEY,
           parentId  TEXT    NOT NULL,
           type      TEXT    NOT NULL,
           createdAt INTEGER NOT NULL,
           updatedAt INTEGER NOT NULL,
           deletedAt INTEGER,
           text      TEXT,
           refId     TEXT)""",
    "CREATE INDEX idx_document_parent_type ON document(parentId, type, deletedAt)",
]
DOCUMENT_MAX_BYTES = 5 * 1024 * 1024

# ── the app stores ─────────────────────────────────────────────────────────

SOIL_PACKAGE = "com.symmetricalpalmtree.soil"
STORE_SCRATCHPAD = "scratchpad.db"


def store_name(app: str, variant: str) -> str:
    """`app_<package>.db`; a debug build's package ends in `.dev`."""
    pkg = f"{SOIL_PACKAGE}.{app}" + (".dev" if variant == "dev" else "")
    return f"app_{pkg}.db"


INK_STROKE_DDL = (
    "CREATE TABLE stroke (id TEXT PRIMARY KEY, pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE, "
    '"order" INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, style TEXT NOT NULL, blob BLOB NOT NULL)'
)
INK_STROKE_INDEX = 'CREATE INDEX stroke_page_order ON stroke(pageId, "order")'

SCRATCH_USER_VERSION = 1
SCRATCH_DDL = [
    "CREATE TABLE page (id TEXT PRIMARY KEY, position INTEGER NOT NULL, width REAL NOT NULL, height REAL NOT NULL, "
    "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE INDEX page_position ON page(position)",
    INK_STROKE_DDL,
    INK_STROKE_INDEX,
    "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
]

CALENDAR_USER_VERSION = 2
CALENDAR_ID = "default"
CALENDAR_DDL = [
    "CREATE TABLE calendar (id TEXT PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL)",
    "CREATE TABLE period (id TEXT PRIMARY KEY, calendarId TEXT NOT NULL REFERENCES calendar(id) ON DELETE CASCADE, "
    "kind INTEGER NOT NULL, date TEXT NOT NULL, UNIQUE(calendarId, kind, date))",
    "CREATE TABLE page (id TEXT PRIMARY KEY, periodId TEXT NOT NULL REFERENCES period(id) ON DELETE CASCADE, "
    "half INTEGER NOT NULL, width REAL NOT NULL, height REAL NOT NULL, createdAt INTEGER NOT NULL, "
    "updatedAt INTEGER NOT NULL, UNIQUE(periodId, half))",
    INK_STROKE_DDL,
    INK_STROKE_INDEX,
    "CREATE TABLE state (calendarId TEXT NOT NULL REFERENCES calendar(id) ON DELETE CASCADE, key TEXT NOT NULL, "
    "value TEXT NOT NULL, PRIMARY KEY (calendarId, key))",
    "CREATE TABLE event (id TEXT PRIMARY KEY, calendarId TEXT NOT NULL REFERENCES calendar(id) ON DELETE CASCADE, "
    "type TEXT NOT NULL, title TEXT NOT NULL, startDate TEXT NOT NULL, endDate TEXT NOT NULL, allDay INTEGER NOT NULL, "
    "startMinute INTEGER, endMinute INTEGER, recurring INTEGER NOT NULL, freq TEXT, interval INTEGER NOT NULL, "
    "monthlyMode TEXT NOT NULL, endMode TEXT NOT NULL, untilDate TEXT, endCount INTEGER, noteText TEXT NOT NULL, "
    "noteWidth REAL NOT NULL, noteHeight REAL NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
    "CREATE INDEX event_span ON event(calendarId, startDate, endDate)",
    "CREATE INDEX event_recurring ON event(calendarId, recurring)",
    "CREATE TABLE event_weekday (eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE, weekday INTEGER NOT NULL, "
    "PRIMARY KEY (eventId, weekday))",
    "CREATE TABLE event_exception (eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE, date TEXT NOT NULL, "
    "PRIMARY KEY (eventId, date))",
    "CREATE TABLE event_reminder (eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE, amount INTEGER NOT NULL, "
    "unit TEXT NOT NULL, PRIMARY KEY (eventId, amount, unit))",
    "CREATE TABLE note_stroke (id TEXT PRIMARY KEY, eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE, "
    '"order" INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, style TEXT NOT NULL, blob BLOB NOT NULL)',
    'CREATE INDEX note_stroke_event_order ON note_stroke(eventId, "order")',
]

BIBLE_USER_VERSION = 3
BIBLE_DDL = [
    "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    "CREATE TABLE recent (usfm TEXT NOT NULL, chapter INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (usfm, chapter))",
    "CREATE TABLE recent_ref (ref TEXT PRIMARY KEY, at INTEGER NOT NULL)",
]


# ── helpers ────────────────────────────────────────────────────────────────


def create_plain(path: Path, ddl: list[str], user_version: int) -> sqlite3.Connection:
    if path.exists():
        path.unlink()
    con = sqlite3.connect(path)
    for stmt in ddl:
        con.execute(stmt)
    con.execute(f"PRAGMA user_version = {int(user_version)}")
    return con


def write_item_tables(con: sqlite3.Connection, item_id: str, kind: str, name: str, created_at: int) -> None:
    con.execute(SOIL_META_DDL)
    con.executemany(
        "INSERT INTO soil_meta (key, value) VALUES (?, ?)",
        [("format", META_FORMAT), ("id", item_id), ("kind", kind), ("name", name), ("createdAt", str(int(created_at)))],
    )
    for stmt in SOIL_LINK_DDL:
        con.execute(stmt)


# ── the Bible canon, for a link's verse keys (bible-ref `Canon.kt`, `VerseKey.kt`) ──

CANON = ("GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SNG ISA JER LAM EZK DAN "
         "HOS JOL AMO OBA JON MIC NAM HAB ZEP HAG ZEC MAL MAT MRK LUK JHN ACT ROM 1CO 2CO GAL EPH PHP COL 1TH 2TH 1TI "
         "2TI TIT PHM HEB JAS 1PE 2PE 1JN 2JN 3JN JUD REV").split()
ORDINAL = {b: i + 1 for i, b in enumerate(CANON)}
MAX_VERSE = 999
CHAPTER_LIMIT = 1000
MAX_WIRE_CHARS = 512


def verse_key(ordinal: int, chapter: int, verse: int) -> int:
    return ordinal * 1_000_000 + chapter * 1_000 + verse


def wire_ranges(wire: str | None) -> list[tuple[int, int]] | None:
    """Every range of a wire as (startKey, endKey), in wire order; None when Soil could not read it
    (`Reference.decode`): then no mirror row is written and the link stays a dead end."""
    if not wire or len(wire) > MAX_WIRE_CHARS:
        return None
    if not all(c.isdigit() or ("A" <= c <= "Z") or c in ":,-" for c in wire):
        return None
    out: list[tuple[int, int]] = []
    for part in wire.split(","):
        fields = part.split(":")
        if len(fields) != 4 or fields[0] not in ORDINAL:
            return None
        try:
            c1 = int(fields[1])
            v1s, c2s = fields[2].split("-", 1)
            v1, c2, v2 = int(v1s), int(c2s), int(fields[3])
        except ValueError:
            return None
        if not (1 <= c1 < CHAPTER_LIMIT and 1 <= c2 < CHAPTER_LIMIT and 0 <= v1 <= MAX_VERSE and 0 <= v2 <= MAX_VERSE):
            return None
        o = ORDINAL[fields[0]]
        start, end = verse_key(o, c1, v1), verse_key(o, c2, v2)
        if end < start:
            return None
        out.append((start, end))
    return out


def range_id(link_id: str, n: int) -> str:
    return link_id if n == 0 else f"{link_id}#{n}"
