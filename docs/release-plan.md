# The first stable release, and the move from Notesprout SN — plan (2026-10-11)

The seven decisions at the end are Greg's (2026-10-11). The branch is `release`; its first commit
renamed the Drive root to `Sproutscape` / `Sproutscape Dev`.

## What is true today

| Fact | Where it comes from |
|---|---|
| Soil and every Sprout app and extension are `0.1.0`, `versionCode 1`; the seam is `VERSION = 1`, to be frozen at the first release put to use | `*/build.gradle.kts`, `Seam.kt`, `design.md` §10 |
| Release installs as `com.symmetricalpalmtree.soil` and the apps as `com.symmetricalpalmtree.soil.{notesprout,docsprout,sketchsprout,biblesprout,calsprout}`, the extensions as `….soil.ext.{mlkit,soilfile,pdf,image,cloud}`; debug adds `.dev` to each | `building.md` |
| The Manta is `SN100C10023972` and identifies as a Nomad: always by serial | `building.md` |
| Notesprout SN on the Manta is the release `com.symmetricalpalmtree.notesproutsn`, with the og2sn-migrated library at `/sdcard/Android/data/com.symmetricalpalmtree.notesproutsn/files/`: `notesprout.db`, `Garden/<uuid>.soil`, `Garden/<pkg>.db` for ten extension stores | SN `backup.md`, memory |
| **No converter from SN to Soil exists yet.** `design.md` §11 names the shape: a tool on the Mac reads the SN library and writes a Soil backup set; Soil's Restore installs it; SN is never modified and stays installed until the result is checked | `design.md` §11, `tools/og2sn` as the model |
| Soil's Restore is replace-all from a folder: `soil.db`, `<id>.soil` per item, `<name>.db` per store; it proves the key with the device's cached global first, else a typed passphrase, which then becomes the device's global | `backup.md` |
| I do not hold the Manta's SN passphrase (`~/.og-pass` was scrubbed after og2sn) | memory |

## The shape of the work

Three parts, in this order. Part A is the long one and happens before the Manta is plugged in.

### A. The converter, `tools/sn2soil` (on a branch, before device day)

Python 3 stdlib plus the Homebrew `sqlcipher` CLI, as og2sn was. Reads an SN library folder
(index + `Garden/`), writes a Soil backup folder, and a `report.txt` with every count and warning.
Tested on the JVM-free side with unit tests including an encrypted round trip, then dry-run on a
copy of the real library and read against the report before anything touches the Manta.

What each SN thing becomes:

| SN | Soil |
|---|---|
| `notesprout.db` `objects`: folders, notebooks, template folders, templates, pins, naming schemes | `soil.db`: `folder`, `item`, `template_folder`, `template`, `item_pin`, `folder_prefs`. Same ids, so links keep working |
| A handwritten notebook's `.soil` (18 columns, strokes parented to the page) | A Soil notebook `.soil`: the same 18 columns, `notebook_meta` → `soil_meta`, `soil_link` mirror built from its link rows |
| `shape` rows, if any | **Greg decides when the report shows the count** (`BACKLOG.md`: dropped, or shapes brought back first) |
| A text document (flag bit 2) | A Docsprout document: the notebook document's Markdown as the body |
| A handwritten notebook's page documents and notebook document | One Document beside the notebook, same folder, named after it: the notebook document if there is one, else the page documents joined in page order with a blank line between, as SN's own merge did (Greg). `design.md` §11's open question: no link left between the two |
| A Sketch notebook (flag 8): pages, templates, `sketch_graphite` and `sketch_ink` rasters, `guide_grid`, `guide_image` | A Sketchsprout sketchbook: the row names and the raster format (page-sized lossless RGBA WebP) are already Soil's. Strokes and objects on its pages become a **notebook beside the sketchbook**, same folder and name, page for page (Greg); a Sketch notebook with none gets no notebook |
| `page_text`, `undo_redo_state`, recents, `notebook_activity`, backup config, clipboard | Dropped |
| `ext.tags.db` | `soil.db` `tag` and `tag_assignment`, items and pages |
| `ext.calendar.db` (periods, pages, strokes, events and their children) | `app_com.symmetricalpalmtree.soil.calsprout.db`: SN's schema plus Soil's `calendar` row and `calendarId` columns |
| `ext.scratchpad.db` | `scratchpad.db` |
| `ext.bible.db` (position, recents) | `app_com.symmetricalpalmtree.soil.biblesprout.db` |
| `ext.document.db` user dictionary | Docsprout's proofread store, if its shape takes a word list; else reported and dropped |
| `ext.cloud.db` | Dropped; Drive is connected again in Soil by Greg, one tap |
| Any NOTEBOOK-scoped file (a passphrase of its own) | None exist (Greg); refused by name in the report, as og2sn did |

Encryption: the converter needs the SN passphrase, handed over as a `chmod 600` file that is
deleted after the run, never on a command line or in a log. The Soil set is encrypted
under the same passphrase (Greg), so Soil's Restore asks for it once on the Manta and it becomes the
device's key. Every plaintext work copy is scrubbed at the end of the run.

### B. The release build (on the same branch)

- The version is `1.0.0`, `versionCode 2`, every module in lockstep, tag `soil-1.0.0` (Greg) and write the seam freeze into `design.md` §10.
- `./gradlew test`, then `assembleRelease` for Soil, the five apps and the five extensions, with
  `DRIVE_CLIENT_ID` and `DRIVE_CLIENT_SECRET` in the shell so the cloud extension is real.
- Documents: `building.md` (the release install block), `design.md` §11 and §16, `CLAUDE.md`
  status, this file's decisions moved into the record.

### C. Device day (the Manta plugged in, Greg at the device)

Order matters. Each numbered step is mine unless marked **Greg**.

1. `adb devices`; confirm `SN100C10023972`. Record the device's HOME activity and accessibility
   setting, so "back" is known. Check whether either points at a `.dev` package.
2. **Greg:** close Notesprout SN fully (back to the library, then leave), so every notebook is
   sealed and the WAL folded in.
3. Pull the live SN library to the Mac: `notesprout.db`, its `-wal` if present, and `Garden/`.
   Pull is safe; SN is not modified. Greg's existing backup stays the safety net.
4. Run the converter on the pull. Read `report.txt` together: item counts by kind, shapes,
   NOTEBOOK-scoped files, documents split, warnings. Iterate if needed. Keep the output folder
   on the Mac as the migration record.
5. Uninstall the Soil **debug** set from the Manta, twelve packages: `com.symmetricalpalmtree.soil.dev`,
   the five `….dev` apps, the five `….ext.*.dev` extensions, and `seam-stranger` if present.
   An uninstall deletes that build's library; nothing in the Manta's dev library is wanted (Greg). If the dev build is the HOME or the bar service, step 1's
   stock values are put back before the uninstall.
6. Install the release set: Soil first, then the five apps, then the five extensions.
7. **Greg:** open Sproutscape once. It mints a recovery key for an empty library; the restore
   replaces it with the SN passphrase, so this key needs no saving. I never tick "I've saved it".
8. Push the converted folder to `/sdcard/Download/sn2soil` (never into `Android/data`).
9. **Greg:** Settings → *Files on this device* → allow All files access; let Soil restart itself.
   Then Backup → Restore → from a folder → pick `Download/sn2soil`. At the key prompt type the
   SN passphrase on the on-screen keys (adb typing is swallowed). Restore runs under the wait
   overlay and returns to Home; the link index is rebuilt after it.
10. Walk, counts against the report: folders and items, a notebook opens and shows its ink
    (committed ink does screenshot), a document, a sketchbook, the calendar's ink and events,
    the Scratch Pad's pages, the Bible's last position, tags, a link followed each way, pins,
    the paper library. Handwriting is Greg's hand.
11. The shell on the Manta, as on the Nomad (Greg): Soil as HOME and the side menu. The two settings with the release package names; the way back is step 1's record.
12. **Greg:** connect Drive in Soil (the OAuth sign-in is his), name the device's folder, and run
    the first backup to a local folder and the cloud.
13. Notesprout SN and its ten extensions stay installed until Greg has lived with the result.
    Removing them is a later, separate step.
14. After the day: the converter, its tests and the documents committed and pushed on the
    branch; merged when Greg is happy; the tag; `CLAUDE.md` status; memory.

## Risks and how each is held

- **New code against the only copy of real data.** The converter only ever reads SN's files, and
  it reads a pulled copy. Greg's SN backup is untouched throughout.
- **A wrong mapping found late.** The dry run and the report come before any install; Restore is
  replace-all, so a second converted set simply replaces the first on an otherwise empty Soil.
- **The Manta dropping off adb** mid-pull or mid-push: re-plug and repeat; both are idempotent.
- **The bar service.** Never `am force-stop` Soil; the install itself restarts it cleanly.
- **The passphrase.** Lives in a 600 file for the run and on Greg's keys at the prompt; never
  in a log, a screenshot, a view dump or a message.

## Decisions (Greg, 2026-10-11)

1. Version `1.0.0`, `versionCode 2`, tag `soil-1.0.0`.
2. A notebook's page documents and notebook document: one Document beside the notebook.
3. A Sketch notebook's strokes and objects: a notebook beside the sketchbook.
4. No NOTEBOOK-scoped files exist; any found is refused by name.
5. The Soil set under the same SN passphrase.
6. The shell on the Manta on device day, after the walk.
7. The Manta's dev library goes with the uninstall.
