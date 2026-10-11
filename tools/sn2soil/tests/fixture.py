"""A synthetic Notesprout SN library (plaintext) in SN's exact shapes: the index, four notebooks
(a handwritten one with page documents, a text document, a sketch notebook with ink beside its
rasters, one in the trash), and the six extension stores."""

from __future__ import annotations

import json
import sqlite3
import struct
import zlib
from pathlib import Path

import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sn2soil import snread  # noqa: E402

INDEX_DDL = [
    "CREATE TABLE `objects` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `parentId` TEXT, "
    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `pageCount` INTEGER, `flags` INTEGER, "
    "`keyScope` TEXT, `templateKind` TEXT, `blob` BLOB, `refId` TEXT, `sortOrder` INTEGER, PRIMARY KEY(`id`))",
    "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
]
SOIL_DDL = [
    "CREATE TABLE `notebook` (`id` TEXT NOT NULL, `parentId` TEXT NOT NULL, `type` TEXT NOT NULL, `order` INTEGER NOT NULL DEFAULT 0, "
    "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `text` TEXT, `refId` TEXT, `x` REAL, `y` REAL, "
    "`width` REAL, `height` REAL, `color` TEXT, `strokeWidth` REAL, `style` TEXT, `flags` INTEGER, `blob` BLOB, PRIMARY KEY(`id`))",
    "CREATE TABLE notebook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)",
    "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
]
HOST_SCHEMA = "CREATE TABLE host_schema (id INTEGER PRIMARY KEY CHECK (id = 0), version INTEGER NOT NULL)"
STROKE = ("CREATE TABLE stroke (id TEXT PRIMARY KEY, pageId TEXT NOT NULL REFERENCES page(id) ON DELETE CASCADE, "
          '"order" INTEGER NOT NULL, color INTEGER NOT NULL, width REAL NOT NULL, style TEXT NOT NULL, blob BLOB NOT NULL)')

T0 = 1_700_000_000_000
FOLDER = "33333333-3333-4333-8333-333333333333"
SUB = "34343434-3434-4343-8343-343434343434"
NB_HAND = "11111111-1111-4111-8111-111111111111"
NB_TEXT = "22222222-2222-4222-8222-222222222222"
NB_SKETCH = "44444444-4444-4444-8444-444444444444"
NB_TRASH = "55555555-5555-4555-8555-555555555555"
NB_EMPTY = "66666666-6666-4666-8666-666666666666"
P1, P2, P3 = "aaaa0001-0000-4000-8000-000000000001", "aaaa0002-0000-4000-8000-000000000002", "aaaa0003-0000-4000-8000-000000000003"
PT1 = "aaaa0011-0000-4000-8000-000000000011"
PS1, PS2 = "aaaa0021-0000-4000-8000-000000000021", "aaaa0022-0000-4000-8000-000000000022"
TPL = "cccc0001-0000-4000-8000-000000000001"
LINK_PAGE, LINK_NB, LINK_BIBLE, LINK_SKETCH = ("dddd0001-0000-4000-8000-000000000001", "dddd0002-0000-4000-8000-000000000002",
                                               "dddd0003-0000-4000-8000-000000000003", "dddd0004-0000-4000-8000-000000000004")
STICKY = "eeee0001-0000-4000-8000-000000000001"
HEAD, TEXT, SHAPE = "ffff0001-0000-4000-8000-000000000001", "99990001-0000-4000-8000-000000000001", "77770001-0000-4000-8000-000000000001"
TAG_A, TAG_B = "12120001-0000-4000-8000-000000000001", "12120002-0000-4000-8000-000000000002"
LIBTPL = "55550001-0000-4000-8000-000000000001"
LIBTPL_LINED = "55550002-0000-4000-8000-000000000002"

PNG_1x1 = bytes.fromhex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360f8cfc00000030101"
    "00c9fe92ef0000000049454e44ae426082"
)
WEBP_FAKE = b"RIFF\x1a\x00\x00\x00WEBPVP8L\x0d\x00\x00\x00\x2f\x00\x00\x00\x00\x07\x10\x11\x11\x88\x88\xfe\x07\x00"


def fmtb(xy, pressure=None):
    flags = 0x01 if pressure else 0
    body = bytearray([flags])
    for i, (x, y) in enumerate(xy):
        body += struct.pack("<ff", float(x), float(y))
        if pressure:
            body += struct.pack("<f", float(pressure[i]))
    return bytes([1]) + zlib.compress(bytes(body), 9)


def _insert(con, table, **cols):
    keys = list(cols)
    q = ", ".join(f'"{k}"' for k in keys)
    con.execute(f'INSERT INTO "{table}" ({q}) VALUES ({",".join("?" for _ in keys)})', [cols[k] for k in keys])


def row(con, id, parentId, type, order=0, created=T0, updated=None, deleted=None, **cols):
    _insert(con, "notebook", id=id, parentId=parentId, type=type, order=order, createdAt=created, updatedAt=updated or created,
            deletedAt=deleted, **cols)


def _soil(path: Path, nb_id: str, name: str, extra_meta: dict | None = None) -> sqlite3.Connection:
    con = sqlite3.connect(path)
    for s in SOIL_DDL:
        con.execute(s)
    con.execute("PRAGMA user_version = 1")
    con.execute("INSERT INTO room_master_table VALUES (42, '7c05940f6179724b53fb0e038e229953')")
    meta = {"formatVersion": 1, "notebookId": nb_id, "name": name, "createdAt": T0, "updatedAt": T0, "encrypted": True,
            "keyScope": "GLOBAL", "folderPath": [], "appVersionCode": 1, "textDocument": False, "sketch": False}
    meta.update(extra_meta or {})
    con.execute("INSERT INTO notebook_meta VALUES (0, ?)", (json.dumps(meta),))
    return con


def build_hand(path: Path) -> None:
    con = _soil(path, NB_HAND, "Meeting")
    row(con, NB_HAND, "", "notebook", text="Meeting", refId=P2)
    row(con, TPL, NB_HAND, "template", text="IMG#0123abcd", width=1920.0, height=2560.0, blob=PNG_1x1)
    row(con, P1, NB_HAND, "page", order=0, refId=TPL, width=1920.0, height=2560.0)
    row(con, "s1", P1, "stroke", order=0, color="#000000", strokeWidth=3.0, style="PEN", blob=fmtb([(10, 10), (20, 20)], [0.5, 0.6]))
    row(con, "s1del", P1, "stroke", order=1, deleted=T0 + 5, color="#000000", strokeWidth=3.0, style="PEN", blob=fmtb([(0, 0), (1, 1)]))
    row(con, HEAD, P1, "heading", order=2, x=50.0, y=40.0, width=800.0, height=90.0, text="## Agenda", flags=2)
    row(con, TEXT, P1, "text", order=3, x=50.0, y=400.0, width=600.0, height=200.0, text="Some **bold** text")
    row(con, SHAPE, P1, "shape", order=4, x=500.0, y=900.0, width=200.0, height=100.0, strokeWidth=2.0, style="RECTANGLE", flags=1)
    row(con, LINK_PAGE, P1, "link", order=5, x=40.0, y=1000.0, width=500.0, height=120.0, text=f"L1|1|0||{P2}")
    row(con, "s2", LINK_PAGE, "stroke", order=0, color="#000000", strokeWidth=3.0, style="PEN", blob=fmtb([(50, 1010), (60, 1020)]))
    row(con, LINK_NB, P1, "link", order=6, x=40.0, y=1200.0, width=500.0, height=120.0, text=f"L1|0|1|{NB_TEXT}|")
    row(con, LINK_BIBLE, P1, "link", order=7, x=40.0, y=1400.0, width=500.0, height=120.0, text="L1|1|3|JHN:3:14-3:18,PRO:3:5-3:6|")
    row(con, LINK_SKETCH, P1, "link", order=8, x=40.0, y=1600.0, width=500.0, height=120.0, text=f"L1|1|2|{NB_SKETCH}|{PS2}")
    row(con, STICKY, P1, "sticky_note", order=9, x=1500.0, y=100.0, width=120.0, height=120.0, flags=1920 | (1200 << 20))
    row(con, "s3", STICKY, "stroke", order=0, color="#0000FF", strokeWidth=3.0, style="PEN", blob=fmtb([(5, 5), (15, 15)]))
    row(con, "doc1", P1, "document", text="# Page one\n\nDraft.", flags=T0 + 100)
    row(con, "sketchdead", P1, "sketch", order=-1, blob=PNG_1x1)
    row(con, P2, NB_HAND, "page", order=1, refId="", width=1920.0, height=2560.0)
    row(con, "doc2", P2, "document", text="Page two words.")
    row(con, "s4", P2, "stroke", order=0, color="#000000", strokeWidth=3.0, style="PENCIL", blob=fmtb([(1, 1), (2, 2)]))
    row(con, P3, NB_HAND, "page", order=2, refId="", width=1920.0, height=2560.0, deleted=T0 + 9)
    row(con, "s5", P3, "stroke", order=0, color="#000000", strokeWidth=3.0, style="PEN", blob=fmtb([(1, 1), (2, 2)]))
    con.commit()
    con.close()


def build_text(path: Path) -> None:
    con = _soil(path, NB_TEXT, "Essay", {"textDocument": True})
    row(con, NB_TEXT, "", "notebook", text="Essay", refId=PT1)
    row(con, PT1, NB_TEXT, "page", order=0, refId="", width=1404.0, height=1872.0)
    row(con, "doc-nb", NB_TEXT, "document", text="# Essay\n\nThe whole thing.")
    row(con, "doc-p", PT1, "document", text="A page draft that loses to the notebook document.")
    con.commit()
    con.close()


def build_sketch(path: Path) -> None:
    con = _soil(path, NB_SKETCH, "Doodles", {"sketch": True})
    row(con, NB_SKETCH, "", "notebook", text="Doodles", refId=PS2)
    row(con, PS1, NB_SKETCH, "page", order=0, refId="", width=1920.0, height=2560.0)
    row(con, "g1", PS1, "sketch_graphite", order=-1, blob=WEBP_FAKE)
    row(con, "i1", PS1, "sketch_ink", order=-1, blob=WEBP_FAKE)
    row(con, "gg", PS1, "guide_grid", order=-1, text=json.dumps({"kind": 1, "count": 8, "visible": True}))
    row(con, PS2, NB_SKETCH, "page", order=1, refId="", width=1920.0, height=2560.0)
    row(con, "i2", PS2, "sketch_ink", order=-1, blob=WEBP_FAKE)
    row(con, "ss1", PS2, "stroke", order=0, color="#000000", strokeWidth=3.0, style="PEN", blob=fmtb([(7, 7), (8, 8)]))
    row(con, "sl1", PS2, "link", order=1, x=1.0, y=1.0, width=100.0, height=50.0, text=f"L1|1|0||{PS1}")
    con.commit()
    con.close()


def build_empty(path: Path) -> None:
    con = _soil(path, NB_EMPTY, "Blank")
    row(con, NB_EMPTY, "", "notebook", text="Blank", refId=None)
    con.commit()
    con.close()


def _store(path: Path, ddl: list[str], version: int) -> sqlite3.Connection:
    con = sqlite3.connect(path)
    con.execute(HOST_SCHEMA)
    for s in ddl:
        con.execute(s)
    con.execute("INSERT INTO host_schema VALUES (0, ?)", (version,))
    con.execute("PRAGMA user_version = 2")
    return con


def build_stores(garden: Path) -> None:
    pkg = snread.PKG_HOST
    con = _store(garden / f"{pkg}.ext.tags.db", [
        "CREATE TABLE tag (id TEXT PRIMARY KEY, display TEXT NOT NULL, identityKey TEXT NOT NULL UNIQUE, createdAt INTEGER NOT NULL)",
        "CREATE TABLE assignment (tagId TEXT NOT NULL REFERENCES tag(id) ON DELETE CASCADE, notebookId TEXT NOT NULL, "
        "pageId TEXT NOT NULL DEFAULT '', createdAt INTEGER NOT NULL, PRIMARY KEY (tagId, notebookId, pageId))"], 1)
    con.execute("INSERT INTO tag VALUES (?, 'Work  Stuff', 'work stuff', ?)", (TAG_A, T0))
    con.execute("INSERT INTO tag VALUES (?, 'Ideas', 'ideas', ?)", (TAG_B, T0))
    con.execute("INSERT INTO assignment VALUES (?, ?, '', ?)", (TAG_A, NB_HAND, T0))
    con.execute("INSERT INTO assignment VALUES (?, ?, ?, ?)", (TAG_B, NB_HAND, P2, T0))
    con.execute("INSERT INTO assignment VALUES (?, ?, ?, ?)", (TAG_B, NB_TEXT, PT1, T0))   # a page tag on a text document
    con.execute("INSERT INTO assignment VALUES (?, ?, '', ?)", (TAG_B, NB_TRASH, T0))      # on a trashed notebook
    con.commit(); con.close()

    con = _store(garden / f"{pkg}.ext.scratchpad.db", [
        "CREATE TABLE page (id TEXT PRIMARY KEY, position INTEGER NOT NULL, width REAL NOT NULL, height REAL NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
        STROKE, "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)"], 1)
    con.execute("INSERT INTO page VALUES ('sp1', 0, 1920.0, 2560.0, ?, ?)", (T0, T0))
    con.execute("INSERT INTO page VALUES ('sp2', 1, 1920.0, 2560.0, ?, ?)", (T0, T0))
    con.execute("INSERT INTO stroke VALUES ('spk1', 'sp1', 0, -16777216, 3.0, 'PEN', ?)", (fmtb([(3, 3), (4, 4)]),))
    con.execute("INSERT INTO state VALUES ('current', 'sp2')")
    con.commit(); con.close()

    con = _store(garden / f"{pkg}.ext.calendar.db", [
        "CREATE TABLE period (id TEXT PRIMARY KEY, kind INTEGER NOT NULL, date TEXT NOT NULL, UNIQUE(kind, date))",
        "CREATE TABLE page (id TEXT PRIMARY KEY, periodId TEXT NOT NULL REFERENCES period(id) ON DELETE CASCADE, half INTEGER NOT NULL, "
        "width REAL NOT NULL, height REAL NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, UNIQUE(periodId, half))",
        STROKE, "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE event (id TEXT PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL, startDate TEXT NOT NULL, endDate TEXT NOT NULL, "
        "allDay INTEGER NOT NULL, startMinute INTEGER, endMinute INTEGER, recurring INTEGER NOT NULL, freq TEXT, interval INTEGER NOT NULL, "
        "monthlyMode TEXT NOT NULL, endMode TEXT NOT NULL, untilDate TEXT, endCount INTEGER, noteText TEXT NOT NULL, noteWidth REAL NOT NULL, "
        "noteHeight REAL NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
        "CREATE TABLE event_weekday (eventId TEXT NOT NULL, weekday INTEGER NOT NULL, PRIMARY KEY(eventId, weekday))",
        "CREATE TABLE event_exception (eventId TEXT NOT NULL, date TEXT NOT NULL, PRIMARY KEY(eventId, date))",
        "CREATE TABLE event_reminder (eventId TEXT NOT NULL, amount INTEGER NOT NULL, unit TEXT NOT NULL, PRIMARY KEY(eventId, amount, unit))",
        "CREATE TABLE note_stroke (id TEXT PRIMARY KEY, eventId TEXT NOT NULL, \"order\" INTEGER NOT NULL, color INTEGER NOT NULL, "
        "width REAL NOT NULL, style TEXT NOT NULL, blob BLOB NOT NULL)"], 2)
    con.execute("INSERT INTO period VALUES ('per-m', 0, '2026-09-01')")
    con.execute("INSERT INTO period VALUES ('per-d', 2, '2026-09-02')")
    con.execute("INSERT INTO page VALUES ('cp1', 'per-m', 0, 1920.0, 2560.0, ?, ?)", (T0, T0))
    con.execute("INSERT INTO page VALUES ('cp2', 'per-d', 1, 1920.0, 2560.0, ?, ?)", (T0, T0))
    con.execute("INSERT INTO stroke VALUES ('ck1', 'cp1', 0, -16777216, 3.0, 'PEN', ?)", (fmtb([(1, 1), (2, 2)]),))
    con.execute("INSERT INTO state VALUES ('lastView', '2')")
    con.execute("INSERT INTO state VALUES ('lastDate', '2026-09-02')")
    con.execute("INSERT INTO event VALUES ('ev1', 'BIRTHDAY', 'Mom', '2024-10-01', '2024-10-01', 1, NULL, NULL, 1, 'YEARLY', 1, "
                "'DAY_OF_MONTH', 'NEVER', NULL, NULL, 'cake', 400.0, 200.0, ?, ?)", (T0, T0))
    con.execute("INSERT INTO event_weekday VALUES ('ev1', 1)")
    con.execute("INSERT INTO event_exception VALUES ('ev1', '2025-10-01')")
    con.execute("INSERT INTO event_reminder VALUES ('ev1', 1, 'WEEKS')")
    con.execute("INSERT INTO note_stroke VALUES ('ns1', 'ev1', 0, -16777216, 3.0, 'PEN', ?)", (fmtb([(1, 1), (2, 2)]),))
    con.commit(); con.close()

    con = _store(garden / f"{pkg}.ext.bible.db", [
        "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE recent (usfm TEXT NOT NULL, chapter INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (usfm, chapter))",
        "CREATE TABLE recent_ref (ref TEXT PRIMARY KEY, at INTEGER NOT NULL)",
        "CREATE TABLE note_ref (noteId TEXT NOT NULL, rangeIx INTEGER NOT NULL, notebookId TEXT NOT NULL, pageId TEXT NOT NULL, kind INTEGER NOT NULL, "
        "wire TEXT NOT NULL, startKey INTEGER NOT NULL, endKey INTEGER NOT NULL, notebookName TEXT NOT NULL, pageNumber INTEGER NOT NULL, at INTEGER NOT NULL, "
        "PRIMARY KEY (noteId, rangeIx))"], 4)
    con.execute("INSERT INTO state VALUES ('position', 'JHN:3:16')")
    con.execute("INSERT INTO recent VALUES ('JHN', 3, ?)", (T0,))
    con.execute("INSERT INTO recent_ref VALUES ('JHN:3:16-3:16', ?)", (T0,))
    con.execute("INSERT INTO note_ref VALUES ('x', 0, ?, ?, 0, 'JHN:3:14-3:18', 43003014, 43003018, 'Meeting', 1, ?)", (NB_HAND, P1, T0))
    con.commit(); con.close()

    con = _store(garden / f"{pkg}.ext.document.db", [
        "CREATE TABLE prefs (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        "CREATE TABLE word (word TEXT PRIMARY KEY, addedAt INTEGER NOT NULL)",
        "CREATE TABLE caret (pageKey TEXT PRIMARY KEY, offset INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"], 1)
    con.execute("INSERT INTO word VALUES ('notesprout', ?)", (T0,))
    con.commit(); con.close()

    con = _store(garden / f"{pkg}.ext.cloud.db", ["CREATE TABLE account (key TEXT PRIMARY KEY, value TEXT NOT NULL)"], 1)
    con.execute("INSERT INTO account VALUES ('refreshToken', 'secret')")
    con.commit(); con.close()


def build_index(path: Path) -> None:
    con = sqlite3.connect(path)
    for s in INDEX_DDL:
        con.execute(s)
    con.execute("PRAGMA user_version = 1")
    con.execute("INSERT INTO room_master_table VALUES (42, 'cd6b27016dcd7da0993ac85347813855')")
    o = lambda **c: _insert(con, "objects", **c)
    o(id=FOLDER, type="folder", name="Work", parentId=None, createdAt=T0, updatedAt=T0)
    o(id=SUB, type="folder", name="Inner", parentId=FOLDER, createdAt=T0, updatedAt=T0)
    o(id="gone-folder", type="folder", name="Gone", parentId=None, createdAt=T0, updatedAt=T0, deletedAt=T0 + 1)
    o(id=NB_HAND, type="notebook", name="Meeting", parentId=SUB, createdAt=T0, updatedAt=T0 + 1, pageCount=2, flags=1 | 2, keyScope="GLOBAL", templateKind="IMAGE", blob=PNG_1x1)
    o(id=NB_TEXT, type="notebook", name="Essay", parentId=None, createdAt=T0, updatedAt=T0, pageCount=1, flags=1 | 4, keyScope="GLOBAL")
    o(id=NB_SKETCH, type="notebook", name="Doodles", parentId="gone-folder", createdAt=T0, updatedAt=T0, pageCount=2, flags=1 | 8, keyScope="GLOBAL", blob=PNG_1x1)
    o(id=NB_TRASH, type="notebook", name="Trashed", parentId=None, createdAt=T0, updatedAt=T0, deletedAt=T0 + 1, pageCount=1, flags=1, keyScope="GLOBAL")
    o(id=NB_EMPTY, type="notebook", name="Blank", parentId=None, createdAt=T0, updatedAt=T0, pageCount=0, flags=1, keyScope="GLOBAL")
    o(id="missing-nb", type="notebook", name="Ghost", parentId=None, createdAt=T0, updatedAt=T0, pageCount=1, flags=1, keyScope="GLOBAL")
    o(id="own-key-nb", type="notebook", name="Secret", parentId=None, createdAt=T0, updatedAt=T0, pageCount=1, flags=1, keyScope="NOTEBOOK")
    o(id=snread.PINNED_LIST, type="list", name="pinned", parentId=None, createdAt=T0, updatedAt=T0)
    o(id="li1", type="list_item", name="", parentId=snread.PINNED_LIST, createdAt=T0, updatedAt=T0, refId=NB_HAND, sortOrder=1)
    o(id="li2", type="list_item", name="", parentId=snread.PINNED_LIST, createdAt=T0, updatedAt=T0, refId=NB_TEXT, sortOrder=0)
    o(id="li3", type="list_item", name="", parentId=snread.PINNED_LIST, createdAt=T0, updatedAt=T0, refId="missing-nb", sortOrder=2)
    o(id="nm1", type="naming", name="Meeting {date} {n:2}", parentId=SUB, createdAt=T0, updatedAt=T0)
    o(id="nm2", type="naming", name="Bad {bogus}", parentId=None, createdAt=T0, updatedAt=T0)
    o(id="tf1", type="template_folder", name="Mine", parentId=None, createdAt=T0, updatedAt=T0)
    o(id=LIBTPL, type="template", name="Cornell", parentId="tf1", createdAt=T0, updatedAt=T0, templateKind="IMAGE", flags=1, blob=PNG_1x1)
    o(id=LIBTPL_LINED, type="template", name="Narrow", parentId=None, createdAt=T0, updatedAt=T0, templateKind="LINED", flags=0)
    o(id=snread.TEMPLATE_PINNED_LIST, type="list", name="pinned_templates", parentId=None, createdAt=T0, updatedAt=T0)
    o(id="tli1", type="list_item", name="", parentId=snread.TEMPLATE_PINNED_LIST, createdAt=T0, updatedAt=T0, refId=LIBTPL, sortOrder=0)
    o(id="tli2", type="list_item", name="", parentId=snread.TEMPLATE_PINNED_LIST, createdAt=T0, updatedAt=T0, refId="00000000-0000-0000-0000-6c696e65645f", sortOrder=1)
    o(id="00000000-0000-0000-0000-6261636b7570", type="backup", name="backup", parentId=None, createdAt=T0, updatedAt=T0, flags=1, blob=b"{}")
    o(id="00000000-0000-0000-0000-636c69706264", type="clipboard", name="page", parentId=None, createdAt=T0, updatedAt=T0, flags=1, blob=b"{}")
    con.commit()
    con.close()


def build_library(root: Path) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    build_index(root / "notesprout.db")
    garden = root / "Garden"
    garden.mkdir(exist_ok=True)
    build_hand(garden / f"{NB_HAND}.soil")
    build_text(garden / f"{NB_TEXT}.soil")
    build_sketch(garden / f"{NB_SKETCH}.soil")
    build_text(garden / f"{NB_TRASH}.soil")
    build_empty(garden / f"{NB_EMPTY}.soil")
    build_empty(garden / "own-key-nb.soil")
    build_stores(garden)
    return root
