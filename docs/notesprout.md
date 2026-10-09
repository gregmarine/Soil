# Notesprout

The first Sprout app: Notesprout SN's notebook, built over Soil. Its own APK,
`com.symmetricalpalmtree.soil.notesprout` (`.dev` in debug), a module of this repository. A
notebook file holds ink and what is placed among it, and nothing else: no sketches, no
documents, no passphrase of its own.

## Reaching it

Through the library, through the side menu's row for it, and through its own drawer icon,
which reopens the notebook last open at its last page, or goes to the library when there is
none. Soil starts `NotebookActivity` with an id, or with the name of a notebook to make.

## The file

One table, `notebook`, Notesprout SN's column for column: every thing is a row and its type says
what it is. The root row holds the title and the page last open; a `template` row an image and
its token; a `page` its template and size; `stroke`, `heading`, `text`, `link` and `sticky_note`
rows are parented to pages, a link's wrapped children to the link, a note's content to the
note. `NotebookSql` is every statement as a pure builder, every write idempotent: strokes are
upserted and soft-deleted, pages made with `INSERT OR IGNORE` then updated, never replaced.
`"order"` is quoted everywhere and is load-bearing.

## The screen

`NotebookActivity` on `:paper`'s `InkScreenActivity`: full-bleed g-paper, the page-op lock, the
undo stack, the debounced save, the chrome band and its exclusions, the EPD handoff. The rows
live in Soil: the notebook is held through an `ISeamItem`, parked at `onStop` and resumed on
return, so a notebook in the background holds no file. Frame silence while the pen is active.
`NotebookDocument` owns which page shows, the page list and the objects on the page;
`InkDocument` owns the ink, op-logged.

- **The toolbar**: Close, the three tools and the name above; the page arrows and the indicator
  below. The pen has one width and one of sixteen greys, remembered device-wide; a re-tap on
  the armed pen opens the shade panel, on the armed eraser the eraser bar. The chrome collapses.
- **The page sheet** (a long press): Erase page, Delete page, Page template, Save as template,
  Copy, Cut, Paste before or after (every page the clip carries, a calendar Day's two in order,
  one undo step), what links here, Export page, Export notebook.
- **The Recents** panel, from Soil's index, switches to a notebook opened lately.
- **Objects**: typed headings (H1 to H6), Markdown text objects (the `:markdown` module, from
  SN whole), six shapes, sticky notes with an editor of their own over the notebook's store.
  The Insert bar places each at the nearest clear spot; the lasso's bar knows what it caught:
  H, Make text, Bible, Verses, Link, Edit link, Unlink, Copy, Cut, Tag, Send, Delete. Every act
  is one undo step, ink and objects together.
- **The Contents** lists the headings as a tree and goes to the page tapped.
- **Recognition**: H on ink makes a heading, Make text a text object, Tag on ink opens the tag
  screen prefilled, through Soil's relay and behind the consent flow (`extensions.md`).
- **Links** and the picker, and a **Bible reference** on the page (the lasso's Bible, the
  Insert bar's Bible reference, the verses placed under one): `links.md`. **Copy, paste and
  Send**, and a passage pasted from the Bible: `clipboard.md`. **Tags**: `tags.md`.
  **Templates**: `templates.md`.
- **The lasso's paste** takes the newer of the objects and the Bible's passage; with a page on
  the notebook's slot (the lasso cannot paste one) it takes the passage. A paste that fails
  retires only its own slot, never the other kind's.
- **A new ask over the screen** (another notebook from the library) flushes first; a flush
  that fails asks, as every exit does: Try again, or Leave anyway.

## What it gives Soil

- `FrontPaper`: the notebook and the sticky editor attach the app's client on resume and
  detach on pause, so Soil knows paper is in front and the bars reach it from the window.
- `CoverSnapshot`: the showing page, scaled, as the library card's cover.
- `RenderService`: the pages' names and a rendered page bundle for Soil's export screen, one
  page in memory at a time; and the statements that relabel a file for an import.
- `setPages` and `setPageCount` after every structural edit, so the library names a page
  without opening the file.

## Device-local

`NotebookPrefs`: the notebook last open, the pen's shade, whether the chrome was hidden.
`LinkTrail`: the hops of a link story, ids only. Never a name, never backed up.

Walked on the Nomad, phases 2 to 4 and 9 to 11, 2026-09-30 to 2026-10-03. The lost-stroke
investigation of 2026-10-03 and its fix are in `shell.md`.
