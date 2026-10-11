# Soil — instructions for Claude Code

Soil is a hub app for handwriting-first e-ink devices, with the Sprout apps (Notesprout,
Sketchsprout, Docsprout, Biblesprout, Calsprout) built over it. It replaces Notesprout SN.
Supernote Nomad and Manta first. To the person the launcher is **Sproutscape**, the apps are
**Note, Document, Sketch, Bible, Calendar**, and the library is the **Garden** (2026-10-10);
the packages, modules, files and code keep the Soil and -sprout names.

## Status

**The first build is done and merged** (2026-09-29): the home screen with the library and the app
drawer, the side menu, the Scratch Pad, encryption, the library index and the seam's handshake.

**The Notesprout effort is done and merged** (2026-09-30 to 2026-10-03): the seam's storage calls, the notebook app, links, the paper library,
the library with folders and schemes, the clipboard and tags, Send, the extension contract with
recognition and Settings, export and import, cloud storage, backup and restore, and these
documents. `docs/design.md` §16 lists the phases and their documents.

**The Docsprout effort is done and merged** (2026-10-04): the document app with its rendered
editor and its Markdown editor, type-to-format, Proofread, a document's export and the import of
text files, links both ways, the Scratch Pad's Send reworked as a clipboard, Convert from a
notebook, and these documents. `docs/design.md` §16 lists the phases.

**The Biblesprout effort is done and merged** (2026-10-05): the Bible reader as a Sprout app
with no items, its state in Soil's app store over the seam, a shared reference module,
references linked as they are typed in a document and converted from handwriting in a notebook,
back references in Soil's link index and the reader's Notes panel, the verses as words, the
`bible` clipboard, and these documents. `docs/design.md` §16 lists the phases.

**The Calsprout effort is done and merged** (2026-10-05 to 2026-10-06): the calendar as a
Sprout app with no items, SN's three pages and its events in Soil's app store over the seam,
the clipboard in the notebook's shape on the pad and the calendar (strokes under the lasso,
pages from a long-press sheet, a Day as two pages), the export screen's render-only mode, a
day as a link target (`cal:`) from a notebook and a document with the calendar's Notes door on
the shared backlinks panel, and these documents. `docs/design.md` §16 lists the phases.

**The Sketchsprout effort is done and merged** (2026-10-06 to 2026-10-08): the
sketchbook as a Sprout app with items of its own, SN's pencil, gel pen, shades, eraser, smudge
and guides over two rasters a page in one `.soil`, paper from the library under the raster,
undo by tiles, export through Soil's screen, the clipboard (pages between sketchbooks, ink
pasted in), Convert from a notebook, a sketch page as a link target, the seam's value cap at
16 MiB, then a translucent marker on a third raster (g-paper 0.1.68 and 0.1.69) and fixed sizes
for every pen on the palette, and these documents. `docs/design.md` §16 lists the phases.

**The code review is done and merged** (2026-10-08 to 2026-10-09): every module of Soil and
g-paper (0.1.70) read by area, the findings fixed by module in three rounds with a fresh regression
read between, then the walk's findings (verse ranges, the bar keys paired, Snap to guides from SN,
one hidden-bars flag through Soil). `docs/design.md` §16 lists the rounds and their documents.

**The Sproutscape round is done and merged** (2026-10-10): the names the person sees
(Sproutscape, Note, Document, Sketch, Bible, Calendar, the Garden), the apps' marks without
their sprouts, the Garden's seedling, the notebook's and the sketchbook's names on the bottom
bar, the side menu as a two-column grid with Settings in the corner, the drawer's pager fixed,
a sketchbook's swipe-up walk-back, the arrow as every screen's leave. `docs/design.md` §16
lists the steps.

**The toolbar round is done and merged** (2026-10-10): the collapsed chrome's mini toolbar as a
column with Back on top and a second column beside it when the first is full, sub-bars beside it
as columns; the document's format bar on rows always shown, one Heading button with a column menu
of H1 to H6, and the bar wearing what the caret is on. `docs/design.md` §16 lists the steps.

**The document editor round is done and merged** (2026-10-10): a numbered list and a bulleted
list at one depth as two lists (a blank line between in the Markdown, the full gap on the
screen), and Enter in an empty paragraph swallowed so what is seen is what is saved.
`docs/design.md` §16 lists the steps.

**Nothing further is granted.** Do not write a plan, open a branch or start anything, from the
design or from `BACKLOG.md`, until Greg asks for it. When he does: a branch for the work, merged
when he is happy with it, not before.

## Build

- `./gradlew test` — the JVM tests. `./gradlew assembleDebug assembleRelease` — both builds.
- Modules: `:soil` (the app), `:seam` (the interface to the Sprout apps), `:paper` (shared
  theme, chrome and ink), `:seam-kit`, `:markdown`, `:bible-ref` (the canon and the parser),
  `:notesprout`, `:sketchsprout`, `:docsprout`, `:biblesprout`, `:calsprout`, `:ext-api` (the extension
  contract), the extensions `:ext-mlkit`, `:ext-soilfile`, `:ext-pdf`, `:ext-image`,
  `:ext-cloud` (needs `DRIVE_CLIENT_ID` and `DRIVE_CLIENT_SECRET` in the shell), and
  `:seam-stranger` (joins the build only where its key exists).
- Debug installs as `com.symmetricalpalmtree.soil.dev`, release as `com.symmetricalpalmtree.soil`.
  Both are signed with `~/.android/debug.keystore`.
- g-paper comes from `mavenLocal()`. Its version is pinned in `paper/build.gradle.kts` only.

## Testing on the device

- Live ink does not show in a screenshot; committed ink does, once the page is on the window. A
  pen cannot be injected. Writing is walked by Greg.
- The side bars cannot be injected. Only a real swipe tests the menu.
- `adb shell input text` is swallowed. Tap the on-screen keys, or use Copy and Paste.
- Never tick "I've saved it" for Greg. A key acknowledged in a walk is a key nobody wrote down;
  say so at once if a walk needs it.
- Never change what Greg has set on the device to test something: his hidden apps, his pad's
  pages. Cancel out of prompts, or say what could not be checked.
- The Supernote's file picker does not answer injected taps on its rows. Push the file and have
  Greg pick it.
- Docsprout's rendered editor is tested by its on-device self-test (`docs/building.md`).
- A view added inside a layout pass is not drawn. Build rows before the window shows, or post
  them. A view dump lists hidden views too: confirm what is visible with a screenshot.
- Never read a recovery key off the device: not in a screenshot, not in a view dump, not in a
  log. To move one, use the screen's own Copy and the field's Paste.
- Ask before changing the device's home screen or accessibility settings. The commands, and the
  way back, are in `docs/building.md`.
- Never `am force-stop` Soil on the device: Android drops its accessibility service, and the
  side menu with it. The way back is in `docs/building.md`.

## Read first

- `docs/first-build.md` — the first build's plan as granted, and its decisions. A record; never
  resume from it.
- `docs/building.md` — building, installing, the shell on and off.
- `docs/encryption.md`, `docs/shell.md`, `docs/scratchpad.md`, `docs/seam.md`, `docs/items.md`,
  `docs/notesprout.md`, `docs/sketchsprout.md`, `docs/docsprout.md`, `docs/biblesprout.md`, `docs/calsprout.md`, `docs/links.md`, `docs/templates.md`, `docs/clipboard.md`, `docs/tags.md`,
  `docs/extensions.md`, `docs/export.md`, `docs/cloud.md`, `docs/backup.md` — each part as built.

- `docs/design.md` — every decision so far, the device measurements, and the open questions.
- `docs/references.md` — where the shell and seam probes, the Notesprout SN documents, and the
  known traps live.
- `BACKLOG.md` — ideas set aside on purpose. Do not re-raise them as new; do not schedule them
  without a decision.

## How to work here

- Assume nothing. Ask clarifying questions, one at a time.
- Explain first, then ask. A long explanation and a question in the same turn gets cut off.
- Decisions are Greg's. Mark anything unconfirmed as proposed.
- Commit and push together.

## Standing rules carried from Notesprout

- Kotlin, Java 17.
- `kotlinx.serialization` only for JSON.
- No new Gradle dependency without discussion.
- No Material Components.
- Never block the main thread.
- E-ink design: black on white, no colour in the interface, no animation, no shadows,
  Tabler outline icons.
- Passphrases and keys are never logged, never put in an Intent, never written to the index.
- Install only on the device asked for. Nomad by default: `SN078D10012852`. The Manta is
  `SN100C10023972` and identifies as a Nomad, so target by serial.

## Related repositories

- `~/git/Notesprout` — the current apps and their documents. Reference, and the source of
  reused code.
- `~/git/g-paper` — the drawing engine and the device probes.
