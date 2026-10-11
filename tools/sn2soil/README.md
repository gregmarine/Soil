# sn2soil — Notesprout SN → Soil

Converts a whole Notesprout SN library into a **Soil backup set** that Sproutscape's Restore
installs in one go: the index, every item, the Scratch Pad, the calendar (ink and events) and the
Bible's position. `design.md` §11 names the shape; og2sn (`~/git/Notesprout/tools/og2sn`) was
the model and did the same move from the original Notesprout into SN.

One-user desktop tool. Python 3.11+ stdlib and the Homebrew `sqlcipher` CLI; no pip packages.

```
brew install sqlcipher
cd tools/sn2soil
python3 -m unittest tests.test_convert        # 18 tests, incl. an encrypted round trip
```

## What becomes what (Greg's decisions, 2026-10-11)

Every SN notebook has up to three parts: its paper (ink and objects), its sketch (rasters and
guides) and its words (page documents and the notebook document). The part the SN kind names is
the **primary** item and **keeps the SN id**, so every link to it and every tag on it still
resolves. Each other part that is not empty becomes an item **beside** it, same folder, same
name, a fresh id.

| SN | Soil |
|---|---|
| Handwritten notebook | Notebook (same id). Its documents → a Document beside: the notebook document if there is one, else the page documents joined in page order with a blank line between |
| Text document (flag 4) | Document (same id). Any ink on its pages → a notebook beside |
| Sketch notebook (flag 8) | Sketchbook (same id): pages, templates, `sketch_graphite`, `sketch_ink`, `guide_grid`, `guide_image`. Its strokes and objects → a notebook beside, page for page, fresh page ids |
| `shape` rows | Carried into the notebook file, counted in the report. Notesprout does not draw them (`BACKLOG.md`); Greg decides |
| `sketch` (the dead PNG row), `page_text`, deleted rows | Left behind |
| Folders, pins, naming schemes, template folders, picture templates, template pins | The same tables in `soil.db`; the paper library keeps pictures only |
| Tags (`ext.tags`) | `tag` and `tag_assignment` in `soil.db`; a page tag whose page is not in the item that kept the id becomes an item tag |
| Calendar (`ext.calendar`) | `app_com.symmetricalpalmtree.soil.calsprout.db`: SN's rows under Soil's `default` calendar |
| Scratch pad (`ext.scratchpad`) | `scratchpad.db` |
| Bible (`ext.bible`) | `app_com.symmetricalpalmtree.soil.biblesprout.db`: the position and the recents; `note_ref` is Soil's link index's job |
| Links | Payloads carried as they are (the grammar is the same); each file's `soil_link` mirror is built, Bible ranges as verse keys |
| User dictionary (`ext.document`), the cloud account, backup config, clipboard, recents | Left behind, named in the report |
| A NOTEBOOK-scoped file | Refused by name |

Alive rows only: SN's soft-deleted strokes and pages do not travel. A notebook with no live page
gets one blank page. The index's `link` table is left empty; Soil rebuilds it from every file's
`soil_link` after a restore.

## Run

1. **Get the SN library onto the Mac**, with SN closed on the device (back to the library, then
   leave, so every notebook is sealed):

   ```
   mkdir -p ~/sn-library
   adb -s SN100C10023972 pull /sdcard/Android/data/com.symmetricalpalmtree.notesproutsn/files/notesprout.db ~/sn-library/
   adb -s SN100C10023972 pull /sdcard/Android/data/com.symmetricalpalmtree.notesproutsn/files/notesprout.db-wal ~/sn-library/
   adb -s SN100C10023972 pull /sdcard/Android/data/com.symmetricalpalmtree.notesproutsn/files/Garden ~/sn-library/
   ```
   SN's own local backup folder has the same shape (flat, no `Garden/`) and is accepted too.

2. **Convert.** The SN passphrase comes from a `chmod 600` file, deleted after; it is never on a
   command line or in a log. The Soil set is encrypted under the same passphrase unless
   `--out-passphrase-file` says otherwise.

   ```
   python3 sn2soil.py --in ~/sn-library --out ~/soil-migration --passphrase-file ~/.sn-pass
   ```
   Options: `--in-variant dev` (the SN Dev build's store names), `--out-variant dev` (the Soil
   debug build's), `--keep-work` (keep the plaintext work folder for inspection; otherwise it is
   scrubbed).

   The output folder holds `soil.db`, one `<id>.soil` per item, `scratchpad.db`, the calendar's
   and the Bible's stores, and `report.txt` (what each notebook became, every count, every
   warning, and the **Split** list: every SN notebook that became more than one item, with each
   item's kind, name and id, for Greg to go through on the device afterwards).

3. **Restore in Sproutscape.** Push the folder to the device and pick it from Backup → Restore:

   ```
   adb -s SN100C10023972 push ~/soil-migration /sdcard/Download/soil-migration
   ```
   Restore is replace-all. On a fresh install the key prompt asks for the passphrase the set was
   encrypted under; it becomes the device's key.

## Format facts the code stands on (read from source, 2026-10-11)

- SN index `notesprout.db`: one Room table `objects` (14 columns); folders, notebooks, `list` +
  `list_item` (pins), `naming`, `template_folder`, `template`, `backup`, `clipboard`,
  `export_preset`. Notebook flags: 1 encrypted, 2 exclude from backup, 4 text document, 8 sketch.
- SN `.soil`: Room, `user_version` 1, `notebook` (18 columns) + `notebook_meta`; no layers; the
  root row's `refId` is the page last open. Soil's `notebook` and `sketchbook` tables have the
  same 18 columns.
- SN stores: SQLCipher under the global key, `user_version` 2, `host_schema(0, version)`. Soil's
  stores: `PRAGMA user_version` is the schema's step count (index 11, items 1, scratch pad 1,
  calendar 2, Bible 3); no version table; the index's `export_preset` was made and dropped.
- Every item file carries `soil_meta` (`format`, `id`, `kind`, `name`, `createdAt`) and
  `soil_link`; `ItemFiles.open` refuses a file whose `id` or `kind` disagree with the index.
- Stroke blob format B and the stroke colour text are byte-identical on both sides; the stores'
  stroke colour is a signed ARGB integer on both sides.
- Both apps use stock SQLCipher 4 defaults, so the CLI opens either with the passphrase text.
