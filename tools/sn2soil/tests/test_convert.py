from __future__ import annotations

import os
import shutil
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import fixture  # noqa: E402
from sn2soil import cipher, soilschema as S  # noqa: E402
from sn2soil.cli import main  # noqa: E402

PAPER_COLS = ["id", "parentId", "type", "order", "createdAt", "updatedAt", "deletedAt", "text", "refId", "x", "y", "width",
              "height", "color", "strokeWidth", "style", "flags", "blob"]


def q(db: Path, sql: str, *args):
    con = sqlite3.connect(db)
    try:
        return con.execute(sql, args).fetchall()
    finally:
        con.close()


def one(db: Path, sql: str, *args):
    rows = q(db, sql, *args)
    assert len(rows) == 1, rows
    return rows[0]


class PlainConversion(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="sn2soil-"))
        cls.src = fixture.build_library(cls.tmp / "sn")
        cls.dst = cls.tmp / "soil"
        rc = main(["--in", str(cls.src), "--out", str(cls.dst), "--plain"])
        assert rc == 0
        cls.report = (cls.dst / "report.txt").read_text()
        cls.index = cls.dst / "soil.db"

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def items(self, kind=None):
        sql = "SELECT id, kind, name, parentId, flags, pageCount, cover FROM item WHERE deletedAt IS NULL"
        return [r for r in q(self.index, sql) if kind is None or r[1] == kind]

    def item_file(self, item_id: str) -> Path:
        return self.dst / f"{item_id}.soil"

    # ── the set ──

    def test_output_set(self):
        names = sorted(p.name for p in self.dst.iterdir())
        self.assertIn("soil.db", names)
        self.assertIn("scratchpad.db", names)
        self.assertIn("app_com.symmetricalpalmtree.soil.calsprout.db", names)
        self.assertIn("app_com.symmetricalpalmtree.soil.biblesprout.db", names)
        self.assertNotIn("_work", names)
        self.assertEqual({r[0] for r in q(self.index, "SELECT id FROM item")}, {p.stem for p in self.dst.glob("*.soil")})
        self.assertEqual(q(self.index, "PRAGMA user_version"), [(S.INDEX_USER_VERSION,)])
        tables = {r[0] for r in q(self.index, "SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertTrue({"item", "folder", "link", "template", "template_folder", "template_pin", "item_pin", "folder_prefs",
                         "clipboard", "tag", "tag_assignment", "item_page", "meta"} <= tables)
        self.assertNotIn("export_preset", tables)

    def test_items_made(self):
        kinds = sorted((r[1], r[2]) for r in self.items())
        # Meeting → notebook + Document beside; Essay → Document; Doodles → sketchbook + notebook beside; Blank → notebook
        self.assertEqual(kinds, [("document", "Essay"), ("document", "Meeting"), ("notebook", "Blank"), ("notebook", "Doodles"),
                                 ("notebook", "Meeting"), ("sketchbook", "Doodles")])
        by = {(r[1], r[2]): r for r in self.items()}
        self.assertEqual(by[("notebook", "Meeting")][0], fixture.NB_HAND)        # keeps its id
        self.assertEqual(by[("document", "Essay")][0], fixture.NB_TEXT)
        self.assertEqual(by[("sketchbook", "Doodles")][0], fixture.NB_SKETCH)
        self.assertNotEqual(by[("document", "Meeting")][0], fixture.NB_HAND)     # fresh id beside
        self.assertEqual(by[("document", "Meeting")][3], fixture.SUB)            # same folder
        self.assertEqual(by[("notebook", "Meeting")][4], S.ITEM_FLAG_EXCLUDE_FROM_BACKUP)
        self.assertEqual(by[("notebook", "Meeting")][5], 2)                      # alive pages only
        self.assertEqual(by[("notebook", "Meeting")][6], fixture.PNG_1x1)        # the primary keeps the cover
        self.assertIsNone(by[("document", "Meeting")][6])
        self.assertEqual(by[("notebook", "Doodles")][3], "")                     # its folder was gone → root
        self.assertNotIn("Trashed", {r[2] for r in self.items()})
        self.assertIn("Ghost", self.report)
        self.assertIn("NOTEBOOK-scope", self.report)

    # ── the notebook ──

    def test_notebook_rows(self):
        f = self.item_file(fixture.NB_HAND)
        self.assertEqual(q(f, "PRAGMA user_version"), [(1,)])
        cols = [r[1] for r in q(f, "PRAGMA table_info(notebook)")]
        self.assertEqual(cols, PAPER_COLS)
        meta = dict(q(f, "SELECT key, value FROM soil_meta"))
        self.assertEqual((meta["format"], meta["id"], meta["kind"], meta["name"]), ("1", fixture.NB_HAND, "notebook", "Meeting"))
        self.assertEqual(one(f, "SELECT id, parentId, text, refId FROM notebook WHERE type='notebook'"), (fixture.NB_HAND, "", "Meeting", fixture.P2))
        pages = q(f, 'SELECT id, "order", refId, width FROM notebook WHERE type=\'page\' ORDER BY "order"')
        self.assertEqual(pages, [(fixture.P1, 0, fixture.TPL, 1920.0), (fixture.P2, 1, "", 1920.0)])
        self.assertEqual(one(f, "SELECT text, length(blob) FROM notebook WHERE type='template'"), ("IMG#0123abcd", len(fixture.PNG_1x1)))
        self.assertEqual(q(f, "SELECT count(*) FROM notebook WHERE deletedAt IS NOT NULL"), [(0,)])
        types = dict(q(f, "SELECT type, count(*) FROM notebook GROUP BY type"))
        self.assertEqual(types.get("stroke"), 4)       # s1, s2 (in the link), s3 (in the sticky), s4; s1del and s5 gone
        self.assertEqual(types.get("link"), 4)
        self.assertEqual(types.get("shape"), 1)
        self.assertNotIn("document", types)
        self.assertNotIn("sketch", types)
        self.assertEqual(one(f, "SELECT parentId FROM notebook WHERE id='s2'"), (fixture.LINK_PAGE,))
        self.assertEqual(one(f, "SELECT parentId, flags FROM notebook WHERE id='s3'"), (fixture.STICKY, None))
        self.assertEqual(one(f, "SELECT text, flags FROM notebook WHERE id=?", fixture.HEAD), ("## Agenda", 2))
        self.assertIn("SHAPES: 1", self.report)

    def test_notebook_link_mirror(self):
        f = self.item_file(fixture.NB_HAND)
        rows = q(f, "SELECT id, pageId, targetItemId, targetPageId, bibleWire, bibleStart, bibleEnd, calDate FROM soil_link ORDER BY id")
        by = {r[0]: r for r in rows}
        self.assertEqual(by[fixture.LINK_PAGE][1:4], (fixture.P1, fixture.NB_HAND, fixture.P2))
        self.assertEqual(by[fixture.LINK_NB][2:4], (fixture.NB_TEXT, None))
        self.assertEqual(by[fixture.LINK_SKETCH][2:4], (fixture.NB_SKETCH, fixture.PS2))
        wire = "JHN:3:14-3:18,PRO:3:5-3:6"
        self.assertEqual(by[fixture.LINK_BIBLE][2:7], ("", None, wire, 43_003_014, 43_003_018))
        self.assertEqual(by[fixture.LINK_BIBLE + "#1"][2:7], ("", None, wire, 20_003_005, 20_003_006))
        self.assertEqual(len(rows), 5)

    def test_index_pages(self):
        pages = q(self.index, "SELECT pageId, position FROM item_page WHERE itemId=? ORDER BY position", fixture.NB_HAND)
        self.assertEqual(pages, [(fixture.P1, 0), (fixture.P2, 1)])

    # ── documents ──

    def test_document_beside_notebook(self):
        did = [r for r in self.items("document") if r[2] == "Meeting"][0][0]
        f = self.item_file(did)
        self.assertEqual(q(f, "PRAGMA user_version"), [(1,)])
        self.assertEqual(one(f, "SELECT id, parentId, text FROM document WHERE type='document'"), (did, "", None))
        self.assertEqual(one(f, "SELECT parentId, text FROM document WHERE type='body'"), (did, "# Page one\n\nDraft.\n\nPage two words."))
        self.assertEqual(dict(q(f, "SELECT key, value FROM soil_meta"))["kind"], "document")
        self.assertEqual(q(f, "SELECT count(*) FROM soil_link"), [(0,)])

    def test_text_document(self):
        f = self.item_file(fixture.NB_TEXT)
        self.assertEqual(one(f, "SELECT text FROM document WHERE type='body'"), ("# Essay\n\nThe whole thing.",))
        self.assertEqual(one(self.index, "SELECT pageCount FROM item WHERE id=?", fixture.NB_TEXT), (0,))

    # ── the sketchbook ──

    def test_sketchbook_and_notebook_beside(self):
        f = self.item_file(fixture.NB_SKETCH)
        cols = [r[1] for r in q(f, "PRAGMA table_info(sketchbook)")]
        self.assertEqual(cols, PAPER_COLS)
        self.assertEqual(one(f, "SELECT text, refId FROM sketchbook WHERE type='sketchbook'"), ("Doodles", fixture.PS2))
        types = dict(q(f, "SELECT type, count(*) FROM sketchbook GROUP BY type"))
        self.assertEqual((types.get("page"), types.get("sketch_graphite"), types.get("sketch_ink"), types.get("guide_grid")), (2, 1, 2, 1))
        self.assertNotIn("stroke", types)
        self.assertEqual(q(f, 'SELECT DISTINCT "order" FROM sketchbook WHERE type IN (\'sketch_graphite\',\'sketch_ink\',\'guide_grid\')'), [(-1,)])
        beside = [r for r in self.items("notebook") if r[2] == "Doodles"][0]
        g = self.item_file(beside[0])
        self.assertEqual(beside[5], 2)
        pages = q(g, 'SELECT id FROM notebook WHERE type=\'page\' ORDER BY "order"')
        self.assertEqual(len(pages), 2)
        self.assertNotIn(fixture.PS1, [p[0] for p in pages])               # fresh page ids
        new_p1, new_p2 = pages[0][0], pages[1][0]
        self.assertEqual(one(g, "SELECT parentId FROM notebook WHERE id='ss1'"), (new_p2,))
        self.assertEqual(one(g, "SELECT text FROM notebook WHERE id='sl1'"), (f"L1|1|0||{new_p1}",))
        self.assertEqual(one(g, "SELECT pageId, targetItemId, targetPageId FROM soil_link"), (new_p2, beside[0], new_p1))
        self.assertEqual(one(g, "SELECT refId FROM notebook WHERE type='notebook'"), (new_p2,))

    def test_empty_notebook_gets_a_page(self):
        f = self.item_file(fixture.NB_EMPTY)
        self.assertEqual(q(f, "SELECT count(*) FROM notebook WHERE type='page'"), [(1,)])
        self.assertEqual(one(self.index, "SELECT pageCount FROM item WHERE id=?", fixture.NB_EMPTY), (1,))

    # ── the index ──

    def test_folders_pins_naming_templates(self):
        self.assertEqual(sorted(q(self.index, "SELECT id, parentId, name FROM folder")), sorted([(fixture.FOLDER, "", "Work"), (fixture.SUB, fixture.FOLDER, "Inner")]))
        self.assertEqual(sorted(q(self.index, "SELECT id FROM item_pin")), sorted([(fixture.NB_HAND,), (fixture.NB_TEXT,)]))
        self.assertEqual(q(self.index, "SELECT folderId, scheme FROM folder_prefs"), [(fixture.SUB, "Meeting {date} {n:2}")])
        self.assertIn("bogus", self.report)
        self.assertEqual(q(self.index, "SELECT id, parentId, name FROM template_folder"), [("tf1", "", "Mine")])
        self.assertEqual(q(self.index, "SELECT id, parentId, name, fit, length(blob) FROM template"), [(fixture.LIBTPL, "tf1", "Cornell", 1, len(fixture.PNG_1x1))])
        self.assertEqual(q(self.index, "SELECT id FROM template_pin"), [(fixture.LIBTPL,)])
        self.assertIn("Narrow", self.report)

    def test_tags(self):
        self.assertEqual(sorted(q(self.index, "SELECT id, display, identityKey FROM tag")),
                         sorted([(fixture.TAG_A, "Work Stuff", "work stuff"), (fixture.TAG_B, "Ideas", "ideas")]))
        assigns = sorted(q(self.index, "SELECT tagId, itemId, pageId FROM tag_assignment"))
        self.assertEqual(assigns, sorted([(fixture.TAG_A, fixture.NB_HAND, ""), (fixture.TAG_B, fixture.NB_HAND, fixture.P2),
                                          (fixture.TAG_B, fixture.NB_TEXT, "")]))  # the page tag on the text document became an item tag

    # ── stores ──

    def test_scratch_store(self):
        f = self.dst / "scratchpad.db"
        self.assertEqual(q(f, "PRAGMA user_version"), [(S.SCRATCH_USER_VERSION,)])
        self.assertEqual(q(f, "SELECT id, position FROM page ORDER BY position"), [("sp1", 0), ("sp2", 1)])
        self.assertEqual(q(f, "SELECT pageId, color, style FROM stroke"), [("sp1", -16777216, "PEN")])
        self.assertEqual(q(f, "SELECT value FROM state WHERE key='current'"), [("sp2",)])
        self.assertEqual(q(f, "SELECT count(*) FROM sqlite_master WHERE name='host_schema'"), [(0,)])

    def test_calendar_store(self):
        f = self.dst / S.store_name("calsprout", "release")
        self.assertEqual(q(f, "PRAGMA user_version"), [(S.CALENDAR_USER_VERSION,)])
        self.assertEqual(q(f, "SELECT id, name FROM calendar"), [("default", "Calendar")])
        self.assertEqual(sorted(q(f, "SELECT id, calendarId, kind, date FROM period")), [("per-d", "default", 2, "2026-09-02"), ("per-m", "default", 0, "2026-09-01")])
        self.assertEqual(q(f, "SELECT id, periodId, half FROM page ORDER BY id"), [("cp1", "per-m", 0), ("cp2", "per-d", 1)])
        self.assertEqual(q(f, "SELECT pageId, color FROM stroke"), [("cp1", -16777216)])
        self.assertEqual(sorted(q(f, "SELECT calendarId, key, value FROM state")), [("default", "lastDate", "2026-09-02"), ("default", "lastView", "2")])
        self.assertEqual(q(f, "SELECT id, calendarId, type, freq, endMode FROM event"), [("ev1", "default", "BIRTHDAY", "YEARLY", "NEVER")])
        self.assertEqual(q(f, "SELECT weekday FROM event_weekday"), [(1,)])
        self.assertEqual(q(f, "SELECT date FROM event_exception"), [("2025-10-01",)])
        self.assertEqual(q(f, "SELECT amount, unit FROM event_reminder"), [(1, "WEEKS")])
        self.assertEqual(q(f, "SELECT eventId FROM note_stroke"), [("ev1",)])

    def test_bible_store(self):
        f = self.dst / S.store_name("biblesprout", "release")
        self.assertEqual(q(f, "PRAGMA user_version"), [(S.BIBLE_USER_VERSION,)])
        self.assertEqual(q(f, "SELECT key, value FROM state"), [("position", "JHN:3:16")])
        self.assertEqual(q(f, "SELECT usfm, chapter FROM recent"), [("JHN", 3)])
        self.assertEqual(q(f, "SELECT ref FROM recent_ref"), [("JHN:3:16-3:16",)])
        self.assertEqual(q(f, "SELECT count(*) FROM sqlite_master WHERE name='note_ref'"), [(0,)])

    def test_split_list(self):
        sec = self.report.split("Split (")[1].split("SHAPES")[0]
        self.assertTrue(sec.startswith("2 SN notebook(s)"))
        self.assertIn("'Meeting':", sec)
        self.assertIn("'Doodles':", sec)
        self.assertNotIn("'Essay'", sec)
        self.assertIn(f"- notebook 'Meeting' ({fixture.NB_HAND})", sec)
        self.assertIn("- document 'Meeting' (", sec)
        self.assertIn("[beside]", sec)

    def test_left_behind_named(self):
        self.assertIn("user dictionary", self.report)
        self.assertIn("cloud account", self.report)
        self.assertIn("backup", self.report)


class WireRanges(unittest.TestCase):
    def test_ranges(self):
        self.assertEqual(S.wire_ranges("GEN:1:1-1:1"), [(1_001_001, 1_001_001)])
        self.assertEqual(S.wire_ranges("JHN:3:0-3:999"), [(43_003_000, 43_003_999)])
        self.assertIsNone(S.wire_ranges("XXX:1:1-1:1"))
        self.assertIsNone(S.wire_ranges("GEN:2:1-1:1"))
        self.assertIsNone(S.wire_ranges("gen:1:1-1:1"))
        self.assertIsNone(S.wire_ranges(""))

    def test_dev_store_names(self):
        self.assertEqual(S.store_name("calsprout", "dev"), "app_com.symmetricalpalmtree.soil.calsprout.dev.db")


@unittest.skipUnless(shutil.which("sqlcipher"), "sqlcipher CLI not installed")
class EncryptedRoundTrip(unittest.TestCase):
    def test_round_trip(self):
        tmp = Path(tempfile.mkdtemp(prefix="sn2soil-enc-"))
        try:
            plain = fixture.build_library(tmp / "plain")
            enc = tmp / "sn"
            (enc / "Garden").mkdir(parents=True)
            cipher.encrypt_from_plain(plain / "notesprout.db", "walkpass1", enc / "notesprout.db", 1)
            for f in (plain / "Garden").iterdir():
                cipher.encrypt_from_plain(f, "walkpass1", enc / "Garden" / f.name, 2 if f.suffix == ".db" else 1)
            pf = tmp / "pass"
            pf.write_text("walkpass1\n")
            os.chmod(pf, 0o600)
            out = tmp / "soil"
            self.assertEqual(main(["--in", str(enc), "--out", str(out), "--passphrase-file", str(pf)]), 0)
            self.assertFalse((out / "_work").exists())
            for f in out.iterdir():
                if f.suffix in (".soil", ".db"):
                    self.assertTrue(cipher.is_encrypted(f), f.name)
                    self.assertFalse(Path(str(f) + "-wal").exists())
            cipher.verify(out / "soil.db", "walkpass1", S.INDEX_USER_VERSION)
            cipher.verify(out / f"{fixture.NB_HAND}.soil", "walkpass1", 1)
            cipher.verify(out / "scratchpad.db", "walkpass1", S.SCRATCH_USER_VERSION)
            cipher.verify(out / S.store_name("calsprout", "release"), "walkpass1", S.CALENDAR_USER_VERSION)
            dec = tmp / "dec.db"
            cipher.decrypt_to_plain(out / "soil.db", "walkpass1", dec)
            self.assertEqual(q(dec, "SELECT count(*) FROM item"), [(6,)])
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
