# Soil — design

**Status: design, from a brainstorm on 2026-09-27 and 2026-09-28.** This document records what
was decided, what was measured on the device, and what is still open. The first build was
granted on 2026-09-28; its plan is `first-build.md`, and each part as built has a page of its own.

Decisions are Greg's. Where a line is a recommendation that was not explicitly confirmed, it is
marked *proposed*.

---

## 1. What this is

Soil replaces Notesprout SN and its extensions with a different arrangement of the same ideas.

| Today, in Notesprout SN | In Soil |
|---|---|
| One host app that is also the notebook | A hub, Soil, that is not any one kind of content |
| Features are extensions opened from a notebook | Features are apps, opened directly |
| One `.soil` file can hold ink, a document and sketches | One kind of content per `.soil` file |
| The firmware's side menu and home screen | Soil's own side menu and home screen |

### The problem it solves

Everything in Notesprout SN runs through a notebook:

- Reopening the app while in the calendar reopens the notebook first, then the calendar on top.
  The same happens with the Scratch Pad and the Bible.
- Documents and sketches are reached through a notebook by design, because they live inside
  its file.
- A single file holding every kind of content forces special cases throughout.

What works well and is kept: one owner for every file, the library, the keys, and backup.

### Principles carried over unchanged

- Handwriting first. Fixed pages, never an endless scroll.
- Calm on the surface, intelligent underneath.
- E-ink first: black on white, no colour in the interface, no animation, no shadows.
- Soft deletes and stable ids everywhere.

---

## 2. The parts

| Part | Role |
|---|---|
| **Soil** | The hub. Owns every file and everything shared. |
| **Sprout apps** | Notesprout, Sketchsprout, Docsprout, Biblesprout, Calsprout. Each owns a screen and the shape of its own data. |
| **Extensions** | Handwriting recognition, export, import, cloud storage. Services with no screen of their own to live in. |
| **g-paper** | The drawing engine. A separate repository, used as a published dependency. |

Each part is a separate install. That is wanted: a device carries only what it needs, which
matters when other devices are supported.

### What Soil owns

- The home screen, which is the library.
- The side menu, available in every app.
- Every `.soil` file, and all reading and writing of them.
- The keys and every passphrase prompt.
- The library index, tags, links and backlinks.
- The clipboard.
- Backup and restore.
- The Scratch Pad.
- The export screen, the import flow, the cloud account.
- The paper library, Settings, and the relay to every extension.

### The Sprout apps

| App | Its files are called | Storage |
|---|---|---|
| **Notesprout** | Notebooks | A `.soil` per notebook |
| **Sketchsprout** | Sketchbooks | A `.soil` per sketchbook |
| **Docsprout** | Documents | A `.soil` per document |
| **Biblesprout** | It has none | Soil's app store |
| **Calsprout** | It has none | Soil's app store, one calendar. **As built (2026-10-06):** `calsprout.md` |

Notes:

- Documents are plain documents. Nothing elaborate.
- Biblesprout uses Notesprout for notes. Commentaries may come later. It may never have files
  of its own. **As built (2026-10-05):** `biblesprout.md`.
- Paintsprout may one day work with sketchbooks too. Undecided.
- Every existing name is free to reuse, because Soil replaces everything.

### Out of scope

Tasks, routines and the Today dashboard. They are not dropped, but where they fit is undecided.

---

## 3. Storage

Soil offers an app two kinds of storage.

| | Item files | App store |
|---|---|---|
| What it is | One `.soil` file per item | One database per app |
| Shown in the library | Yes | No |
| Own passphrase | Optional | No, the global key only |
| Created by | The person, as a new item | The app, once |
| Used by | Notesprout, Sketchsprout, Docsprout | Biblesprout, Calsprout |

The app store is how Notesprout SN's extensions store data today: the app declares its tables
once, sends SQL to the owner, and gets rows back. Every statement is checked before it runs.
**As built (2026-10-05):** `openAppStore` on the seam, an `ISeamStore` over `garden/app_<package>.db`
(`seam.md`); Biblesprout's position and recents are the first in it, the calendar's pages,
events and notes the second (2026-10-06).

### Rules

- **One kind of content per `.soil` file.** A file says what kind it is, and the library opens
  it in the matching app.
- **An app never touches a file.** It asks Soil for rows and hands rows back.
- **An app never sees a key.** Decryption happens inside Soil only.
- **Large data crosses in one piece**, through shared memory, not in small chunks.

The single owner exists because of encryption. If files were not encrypted, each app could keep
its own.

### Calendar

Calsprout's tables must not assume there is only one calendar, and its screen asks Soil for
"the calendar" through a single call. This keeps a later move to one file per calendar cheap.
See `BACKLOG.md`. **As built (2026-10-06):** every per-calendar table carries a `calendarId`
and every read names one; the app holds one store lease per process (`calsprout.md`).

---

## 4. How items relate

Items are completely decoupled. A sketchbook or document belongs to no notebook.

| Relationship | How it works |
|---|---|
| **Links** | An ordinary link object on a page, pointing at another item |
| **Backlinks** | Any item can list everything that links to it, and go there |
| **Tags** | As today, across every kind of item |
| **Bundling** | None. Items are never grouped as one. |

### Links

- **Page level on both ends.** A backlink names the item and the page it came from. A link
  opens its target at the page named.
- A link to a whole item is a link with no page.
- **A link to a document opens the whole document.** Documents are flowing text and never get
  fixed pages. Linking into a document more precisely is set aside; see `BACKLOG.md`.
- **As built (2026-10-04):** a document links out as well, to a page of a notebook or to another
  document, and lists what links to it (`links.md`).
- Soil keeps one index of every link, since it writes every file. **As built (2026-10-05):**
  kept current at each write, and rebuilt from every file after a restore and from Settings
  (`LinkRebuild`); a link into the Bible is in it too, one row per verse range, so the reader
  lists what cites the verses on its screen (`links.md`, `biblesprout.md`). **As built
  (2026-10-06):** a day of the calendar is a link target too (`cal:<day>`), from a notebook and
  a document, and the calendar lists what links into the period showing (`calsprout.md`).
- *Proposed:* an item locked with its own passphrase is indexed but never prompted for until
  it is followed.

### Convert

A notebook can be the starting point for other items.

| From | To | Result |
|---|---|---|
| Strokes in a notebook | A sketch | A new sketchbook file |
| Handwritten words in a notebook | Text | A new document file |

- Convert happens once. It creates a new item and is finished.
- The person chooses whether a link to the new item is left on the notebook page.

**As built (2026-10-07):** Convert page / notebook to sketchbook, the strokes black into the ink
layer, then Open · Leave a link · Done (`sketchsprout.md`).
- Nothing is kept in step afterwards. Changing the notebook does not change the result, and the
  result does not need to know.

This removes the staleness tracking that documents carry today.

**As built (2026-10-04):** handwritten words to a document, by the page or the whole notebook
(`docsprout.md`). The choice of leaving a link on the notebook page is not built.

---

## 5. The shell

Soil is a full shell on Supernote: it is the home screen and it owns the side menu in every
app, including the firmware's own.

### What was proven on the Nomad

| Capability | Result |
|---|---|
| Be the device's home screen | Works |
| Receive the side-bar gestures in every app | Works, through an accessibility service |
| Keep the firmware's side menu shut and show our own | Works |
| Open any app from our menu | Works |
| The firmware's pull-down status bar | Still works; it is deliberately left alone |
| Take the home screen back after boot | Built; see below |

### What stays the firmware's

- The screen refresh on a swipe up of the right bar, and its flash.
- The pull-down status bar.
- The unlock screen at boot.

### What the bars can tell an app

The bars report very little. The menu's gestures have to be built from this:

| Signal | Available |
|---|---|
| Right bar, swipe down | Yes. This opens the menu. |
| Right bar, swipe up | Yes, but the firmware also refreshes the screen |
| Tap, double tap, hold | Yes, on both bars |
| One finger or two | Yes, on both bars |
| Both bars at once | Yes |
| Where on the bar, or which direction on the left bar | No |

### The menu

The library is central. The menu is the quick way to the key features from anywhere.

A menu entry can do one of three things:

| Entry | Example |
|---|---|
| Open the library showing one kind | Sketchsprout, showing sketchbooks |
| Reopen the last item of a kind | Notesprout, at the page last open |
| Open something that has no files | Biblesprout, Calsprout, the Scratch Pad |

*Proposed* quick-launch entries: Notesprout, Sketchsprout, Docsprout, Biblesprout, Calsprout
and the Scratch Pad. Tags are reached from the library. **As built:** a row for each Sprout
app installed, Biblesprout's opening the reader where it was left (`shell.md`).

Apps do not need browsers of their own. There is one library, in Soil.

### Launching

- Soil opens each app directly. No app sits underneath another.
- On relaunch, Soil reopens the last app that was in use, and nothing beneath it.

### Soil must work without the shell

The shell is a layer Soil can lose. If the accessibility service is off, or a firmware update
breaks the menu, Soil still runs as an ordinary app beside the firmware's menu. This is also how
it will run on devices that have no side bars.

### Known risks

| Risk | Consequence |
|---|---|
| The menu lock relies on the firmware launcher's internals | A firmware update could break it |
| The firmware pushes its Notes app over the home screen about 15 seconds after boot | Soil has to take the home screen back. Built in the demo; not re-tested by a reboot on 2026-09-28. |
| The accessibility service is turned on over adb | Fine for one person; a question if released |

---

## 6. The Scratch Pad

The Scratch Pad is part of Soil, not an app.

It is for the quick thought that has no place yet. It is tied to nothing, and is always
available from the menu, over any app.

- **Full screen.** It is an ordinary screen that Soil opens, so the pen works as it does in a
  notebook. Back returns to the app underneath.
- Soil therefore has one writing surface and depends on g-paper.

### Sending from the pad

| Destination | What is sent |
|---|---|
| A notebook page | Ink, as strokes |
| A document | Recognised text |
| A sketchbook | Ink drawn into a raster |

The pad can send to any item in the library, not only to the one that opened it.

**As built (2026-10-04):** the pad copies its ink to the clipboard and stays open; a notebook
pastes it as ink and a document as recognised words, in any item, any number of times
(`clipboard.md`). **As built (2026-10-06):** the pad pastes
too, in the notebook's shape: strokes under the lasso, a page from its long-press sheet.
**As built (2026-10-07):** a sketchbook pastes the clipboard's ink into its ink layer from its
page sheet (`sketchsprout.md`).

---

## 7. Keys and security

### Keys

Carried over unchanged. It is proven and works well.

- One global passphrase, or a recovery key, for the library.
- Any item may have its own passphrase instead.
- A locked item is never prompted for until it is opened.
- Soil holds the keys and shows every prompt. An app receives rows, never a key.

### Between apps

Communication between apps must be secure.

- Soil's service is guarded by a permission that Android grants only to apps signed with the
  same key as Soil.
- Soil also checks the caller's signing certificate on every call.
- Extensions accept calls from Soil alone.

Soil is built for one person for now, so trust rests on one signing key. There is no
per-app permission model.

---

## 8. Clipboard

- The clipboard belongs to Soil. Every app copies and pastes through it.
- **Same kind only, to start.** A copy pastes only into the kind of app it came from.
- Crossing kinds goes through the Scratch Pad or a convert.
- Pasting across kinds is wanted later. See `BACKLOG.md`.
- **As built (2026-10-04):** the first crossing exists. Ink copied in a notebook or the Scratch
  Pad pastes into a document as words (`clipboard.md`).

---

## 9. Export, import, recognition and cloud

These stay as extensions, shared by every app. **Everything routes through Soil.**

- An app asks Soil to export or recognise. Soil calls the extension and returns the result.
- There is one export screen, in Soil, for every kind of item: format, page range, password and
  destination.
- Apps need to know nothing about which extensions are installed.

### Rendering

Soil can copy a file, but it cannot draw a page. For a PDF or an image, the app that owns the
kind renders it, and the export extension assembles the result.

| Kind | Rendered by |
|---|---|
| Notebook | Notesprout |
| Sketchbook | Sketchsprout |
| Document | Docsprout |
| Calendar | Calsprout, in the export screen's render-only mode (2026-10-06) |

---

## 10. Versions of the seam

The interface between Soil and the apps has one version number.

- **The number does not change during development.**
- It is frozen at the first release build that is actually put to use.
- After that it goes up when the interface changes, as it does today.

---

## 11. The existing library

The current library is converted in one pass, once Soil is ready. This is how the move from the
original Notesprout to Notesprout SN was done, and nothing was lost.

- A tool on the Mac reads the old library and writes a backup that Soil restores.
- The old library is never modified.
- Each current notebook is split: a notebook file, plus a document file and a sketchbook file
  where it has those.
- Soil needs a working restore before the conversion can happen.
- Notesprout SN stays installed until the converted library has been checked.

Because a converter absorbs the differences, Soil's file layout does not have to stay
compatible with the current one.

*Open:* whether the converter leaves links between the files it splits apart.

---

## 12. Where the code comes from

Soil starts from scratch in this repository, and reuses what already works.

| Source | What |
|---|---|
| **Written new** | Soil itself, the interface between Soil and the apps, the link index, the shell |
| **Copied and adapted** | The app screens, the shared paper-screen code, ink storage, the Markdown engine, the Bible database builder, the encryption code |
| **Used unchanged** | g-paper |
| **Reference only** | The Notesprout SN documents, for why things are the way they are |

Notesprout is the largest piece of adapted work. Today it is the host and reads its file
directly.

---

## 13. Audience and devices

- **For one person, for now.** Setup over adb is acceptable.
- **Supernote first:** the Nomad and the Manta.
- BOOX and generic Android are wanted eventually. See `BACKLOG.md`.

---

## 14. What was measured

All on the Supernote Nomad, 2026-09-28. The probes are in the g-paper repository as
`probe-seam-hub` and `probe-seam-app`, with a README. The shell probes are `launcher-demo` and
`probe-slide` there.

The probe hub owns an encrypted database. The probe app reads and writes through it.

### Real notebooks

Two notebooks exported from the Manta. No caching and no prefetching, so every figure is the
worst case.

| Notebook | Strokes per page | Read through the hub | Same read inside the hub | Decode | Total |
|---|---|---|---|---|---|
| 12 pages, 11,751 strokes | 780 to 1,150 | 103 to 167 ms | 82 to 121 ms | 99 to 176 ms | 206 to 340 ms |
| 5 pages, 1,496 strokes | 39 to 670 | 11 to 86 ms | 6 to 81 ms | 18 to 124 ms | 31 to 212 ms |

- Going through the hub adds 5 to 35 ms a page.
- The first page after launch took 537 ms, with everything cold.
- Decoding costs as much as reading, and costs the same with or without a hub.
- **Flipped by hand, it felt almost identical to the notebook today.**

Not measured: the time for ink to appear on the glass. That was judged by eye.

### Synthetic pages

Strokes of 1 KiB of random data.

| Page | Through the hub | Inside the hub |
|---|---|---|
| 200 strokes | 40 ms | 22 ms |
| 1,000 strokes | 112 ms | 105 ms |
| 3,000 strokes | 415 ms | 394 ms |

| Save | Time |
|---|---|
| 1 stroke | 16 to 19 ms |
| 50 strokes | 47 to 54 ms |

Save time does not grow with the size of the page.

### Large images

| Image | In one piece | In 128 KiB chunks |
|---|---|---|
| 1 MiB | 27 ms | 47 ms |
| 3 MiB | 750 ms | 1,498 ms |

One piece is about twice as fast. The 3 MiB figures appear to be dominated by how the probe
reads a large encrypted value, not by the crossing. No comparison inside the hub was taken, so
that is an inference.

### Security

The same probe app, signed with a different key, was refused when it tried to connect. Android
stopped it before any of the hub's code ran.

---

## 15. Open questions

Small enough to settle during planning.

| Topic | Question |
|---|---|
| Ink into a sketch | Whether converted strokes land as graphite or as ink. **Settled 2026-10-07:** ink (`sketchsprout.md`) |
| The menu | Home, the Scratch Pad, Settings, then each Sprout app installed. Still open: any direct bar gestures |
| Conversion | Whether split files are linked to each other. **As built 2026-10-07:** Convert to a sketchbook offers Leave a link; to a document, not yet |
| Boot | Re-test taking the home screen back after a reboot |
| Firmware updates | How Soil notices that the menu lock has stopped working |

Settled since: a dangling link shows a dialog naming the loss and offers Edit link or Remove,
the row kept (`links.md`); kinds are told apart by a glyph on the card (`items.md`); the build
order of the Notesprout effort is in §16.

---

## 16. What was built (2026-10-03 to 2026-10-10)

The Notesprout effort, on the branch `notesprout`, fourteen phases, each walked on the Nomad:

| Phase | What | Document |
|---|---|---|
| 1 | Items over the seam, sessions, the index | `items.md`, `seam.md` |
| 2 to 4 | The notebook: ink, tools, shades, page sheet, Recents, headings, text, shapes, sticky notes, Contents | `notesprout.md` |
| 5 | Links, backlinks, the link mirror and index | `links.md` |
| 6 | The paper library | `templates.md` |
| 7 | The library: folders, shelves, schemes and the builder, default templates, New notebook, pickers | `items.md` |
| 8 | The clipboard and tags | `clipboard.md`, `tags.md` |
| 9 | Send between the Scratch Pad and a notebook | `clipboard.md` |
| 10 | The extension contract, recognition, Settings | `extensions.md` |
| 11 | Export and import | `export.md` |
| 12 | Cloud storage | `cloud.md` |
| 13 | Backup and restore, and the Home toolbars reworked | `backup.md`, `items.md` |
| 14 | These documents | |

The Docsprout effort, on the branch `docsprout`, twelve phases, each walked on the Nomad
(2026-10-04):

| Phase | What | Document |
|---|---|---|
| 1 | The app, the document file, New document | `docsprout.md` |
| 2 | The Markdown editor, from SN | `docsprout.md` |
| 3, 4 | The rich model and the rendered editor | `docsprout.md` |
| 5 | Type-to-format | `docsprout.md` |
| 6 | Proofread | `docsprout.md` |
| 7 | Export: pages at a chosen size, Markdown, text, a text PDF | `export.md` |
| 8 | Import of `.md` and `.txt` | `export.md` |
| 9 | Links, both directions | `links.md` |
| 10 | The Scratch Pad's Send reworked as a clipboard; ink pasted as words | `clipboard.md`, `scratchpad.md` |
| 11 | Convert, from a notebook | `docsprout.md` |
| 12 | These documents | |

The Biblesprout effort, on the branch `biblesprout`, twelve phases, each walked on the Nomad
(2026-10-05):

| Phase | What | Document |
|---|---|---|
| 1 | `:bible-ref`: the canon, the parser, the wire, references in prose; `BibleAddress` | `biblesprout.md` |
| 2 | The app-store lease on the seam; the reader: the Bible, the chapter flow, Contents, Recents, Search, cross-references, footnotes, the passage view; the side-menu row | `seam.md`, `biblesprout.md` |
| 3 | A link into the Bible followed through Soil | `links.md` |
| 4 | A Bible target in the link mirror and the index; `bibleBacklinks`; the rebuild from files | `links.md`, `seam.md` |
| 5 | Docsprout: `bible:` links by hand, Remove remembered, Proofread leaves a reference alone | `docsprout.md`, `links.md` |
| 6 | Docsprout: references linked as they are typed | `docsprout.md`, `links.md` |
| 7 | The reader's Notes panel | `biblesprout.md` |
| 8 | Notesprout: a Bible reference on the page, from the lasso or typed; SN's Bible links read | `notesprout.md`, `links.md` |
| 9 | The verses as words: `passageText` through Soil; Verses on a page; Insert a Bible passage in a document | `seam.md`, `links.md`, `docsprout.md` |
| 10 | The `bible` clipboard: Copy in the reader, Paste in both apps, asked each time | `clipboard.md` |
| 11 | The edges: Convert carries a reference, a backup carries the store, a restore rebuilds the index | `docsprout.md`, `backup.md` |
| 12 | These documents | |

The Calsprout effort, on the branch `calsprout`, eleven phases, each walked on the Nomad
(2026-10-05 and 2026-10-06; phase 3 JVM only, phase 10's walk skipped):

| Phase | What | Document |
|---|---|---|
| 1 | The app and the Month page; `ACTION_OPEN_CALENDAR`; the store over the app store; the dates into `:paper` | `calsprout.md`, `seam.md` |
| 2 | Week and Day, navigation, the shared day picker | `calsprout.md` |
| 3 | Events: the model and the store | `calsprout.md` |
| 4 | The events screen and the editor | `calsprout.md` |
| 5 | The note, the glyphs, the Day rows | `calsprout.md` |
| 6 | Ink across: the clipboard in the notebook's shape on the pad and the calendar; a page clip of several pages | `clipboard.md`, `scratchpad.md` |
| 7 | Export: the export screen's render-only mode | `export.md` |
| 8 | A day as a link target, in Soil | `links.md`, `seam.md` |
| 9 | Day links in the apps; the shared backlinks panel; the calendar's Notes door | `links.md`, `calsprout.md`, `biblesprout.md` |
| 10 | The edges, checked | `backup.md` |
| 11 | These documents | |

The Sketchsprout effort, on the branch `sketchsprout`, eleven phases, each walked on the Nomad
(2026-10-07 and 2026-10-08):

| Phase | What | Document |
|---|---|---|
| 1 | The app and the file: `:sketchsprout`, the schema, the rasters, the saver, pencil and eraser; New sketchbook and the New screen's kind; the pad's menu icon | `sketchsprout.md`, `items.md`, `scratchpad.md` |
| 2 | The tools: gel pen, sixteen shades, smudge and the finger rub, the remembered pen | `sketchsprout.md` |
| 3 | Undo by tiles, pages, the long-press sheet; the next arrow inserting past the last page | `sketchsprout.md` |
| 4 | Paper: template rows, the pick at New, Page template, the paper as the sheet | `templates.md`, `sketchsprout.md` |
| 5 | Guides: grid and reference image in the sheet | `sketchsprout.md` |
| 6 | Export: the sketchbook's renderer, Export page and sketchbook | `export.md` |
| 7 | The clipboard: pages between sketchbooks, ink pasted in | `clipboard.md` |
| 8 | Convert from a notebook, with Open · Leave a link · Done | `sketchsprout.md` |
| 9 | Links: a sketch page as a target; the picker's shelf; "N links to here" | `links.md` |
| 10 | The edges: the seam's cap at 16 MiB and SQLCipher's window, the effort by coverage, the over-cap message, backup pinned | `sketchsprout.md`, `seam.md`, `backup.md` |
| 11 | These documents | |
| M1 | g-paper 0.1.68 and 0.1.69: the marker's own raster, its live preview, its flat ends | `sketchsprout.md` |
| M2 | The marker in the file, the flatten and on the bar; the three-kind tool model with shades and sizes | `sketchsprout.md` |
| M3 | The sizes on the palette, four to a row, the wide markers badged | `sketchsprout.md` |
| M4 | These documents | |

Decisions taken along the way are in each document; what was set aside is in `BACKLOG.md`.

The code review, on the branch `code-review` (2026-10-08 and 2026-10-09), over Soil and g-paper
(0.1.70, Phase 53). Thirteen reviews by area, then fixes by module in three rounds, the third
after a fresh regression read of the whole diff; then what Greg's walk on the Nomad and the
Manta turned up:

| Round | What | Document |
|---|---|---|
| 1 | About 95 findings fixed: restore recovery, the rotation's gate and scope, unreadable rasters refused, the calendar's page by (period, half), the exit dialog on every ink screen, the link pass keeping redo, references on one line, reserved names as strings, Drive's trash, protected PDF exports in memory, covers and store opens off Main | each part's document |
| 2 | g-paper 0.1.70's contracts in every ink host (a lost lift committed before the swap), the pad and CloudStoreLease re-lending, no fresh index over a standing aside, the shared-memory regions closed | `seam.md`, `scratchpad.md`, `backup.md` |
| 3 | The regressions the second read found: the Ratta pin, the calendar's resume compare, an object replay's rows before its ink, a damaged layer exported blank, no fence whose words hold its character, the notebook's parser as it was, export awaiting its save, undo and redo around a link, the rule character off the clipboard, no restore under an open pad, Try again on Home | `docsprout.md`, `backup.md` |
| W | From the walk: a verse range links whole, the bar's down and up paired (the menu no longer opens on its own), Snap to guides from SN, one hidden-bars flag shared through Soil | `biblesprout.md`, `shell.md`, `notesprout.md`, `seam.md` |

Decisions taken along the way are marked proposed in each document; what was set aside is in `BACKLOG.md`.

The Sproutscape round, on the branch `sproutscape` (2026-10-10), towards the first stable
release: what the person sees named and arranged, each change walked on the Nomad. Under the
hood nothing moves: the packages, the modules, the `.soil` file, the seam and the code keep
their names.

| Step | What | Document |
|---|---|---|
| 1 | The launcher is **Sproutscape**: its label, the side menu's service, the extensions' labels and every sentence that said Soil. The apps are **Note, Document, Sketch, Bible, Calendar**, singular, in their labels and the sentences that named them. The Library is the **Garden**, in its label, its root and every sentence. Folders stay folders | `shell.md`, `items.md` |
| 2 | The Garden's icon is Tabler's seedling. The apps' marks lose the sprouting leaf: Note is the Garden's notebook (New notebook without its plus), Document a sheet with lines (`file-text`), Calendar a month (`calendar-month`), Bible the book, Sketch the pencil. The Scratch Pad's scribble is Tabler's own path (it had closed into a ring) | `shell.md` |
| 3 | The notebook's and the sketchbook's names leave the top bar for the start of the bottom bar, the pager keeping the screen's centre (`TitleBand` in `:paper`). The document, with no bottom bar, keeps its name on top | `notesprout.md`, `sketchsprout.md` |
| 4 | The Garden's New buttons in order: Note, Document, Sketch, Folder. The side menu as a grid, two cells to a row (Home, Scratch Pad; Note, Document, Sketch, Calendar, Bible), Settings an icon alone in the bottom corner | `shell.md`, `items.md` |
| 5 | The drawer's pager turned again: its Prev and Next shared ids with the Garden browser's, and view binding had wired the drawer's turns onto the Garden's buttons | `shell.md` |
| 6 | A sketchbook opened on a link leaves on a swipe up, as Back does: Soil's follow marks the open `EXTRA_VIA_LINK` | `links.md`, `sketchsprout.md`, `seam.md` |
| 7 | One leave button on every screen, the arrow (the backlog's entry closed); the document's trail button wears `arrow-bar-to-left` so it is neither the leave nor Undo | `docsprout.md`, `notesprout.md` |

Decisions taken along the way are marked proposed in each document.

The toolbar round, on the branch `toolbar-layout` (2026-10-10), each step walked on the Nomad:

| Step | What | Document |
|---|---|---|
| 1 | The collapsed chrome's mini toolbar as a **column** under the corner button on every paper screen: Back at the top, then the tools, the commands and the doors; as many as the band holds, the rest in a second column beside it on the same tap, no `…`. A sub-bar hung off a column button (Insert, Tags, the shade panels, the sketch's guides) hangs beside the column, level with its button, and stands as a column too | `notesprout.md`, `sketchsprout.md` |
| 2 | The document's format bar wrapped onto rows that are always shown, every tool on the glass; the overflow panel and its `…` gone | `docsprout.md` |
| 3 | One Heading button on the format bar in place of H1 to H3; its menu a column hung under the button, H1 to H6, down on a pick or an outside tap | `docsprout.md` |
| 4 | The format bar wears what the caret is on: Heading, quote, the lists, bold, italic, strikethrough and code bordered when their state holds, on either surface; the Heading menu opens with the caret's level bordered | `docsprout.md` |

Decisions taken along the way are marked proposed in each document.

The document editor round, on the branch `document-editor` (2026-10-10), walked on the Nomad:

| Step | What | Document |
|---|---|---|
| 1 | A numbered list and a bulleted list at one depth are two lists: `RichRules.tight` reads the sequence (one list while every item at a depth is of one family, nested lists and the way back out tight), the writer puts a blank line between them, the plain text too, and the rendered editor the full gap | `docsprout.md` |
| 2 | Enter in an empty paragraph does nothing: Markdown holds no empty paragraph, so a second Return under a paragraph leaves what is seen as what is saved | `docsprout.md` |

Decisions taken along the way are marked proposed in each document.

The export round, on the branch `export` (2026-10-10), walked on the Nomad:

| Step | What | Document |
|---|---|---|
| 1 | The export screen in two columns, half and half: scope and **File type** on the left, the format's options with the passphrase block on the right, **Destination** full width under both | `export.md` |
| 2 | The last destination remembered, the cloud opened on only while the account is connected (the memory kept) | `cloud.md` |
| 3 | The Drive folder on the screen: a Folder row under the cloud radio showing the whole path, remembered per kind of item, `Exports` until picked; a tap opens the browser there and *Save here* only sets the row; Export lists the parent (a gone folder is said so, the browser in its place) and the folder (*Replace?*) and uploads | `cloud.md` |

Decisions taken along the way are marked proposed in each document.

The cleanup round, on the branch `cleanup` (2026-10-10), towards the first version, each step
walked on the Nomad:

| Step | What | Document |
|---|---|---|
| 1 | A title bar's action as a filled button, black with white text (`Widget.Soil.ElevatedButton`): Export, Back up now, the cloud browser's Save here; Cancel stays a text button | |
| 2 | The cloud wherever the device's picker is offered: *this device or the provider* asked first, through one owner (`CloudFilePick`); the template library's Import from the provider and a template's Export to a folder under `Exports/`; a Sprout app's file through Soil's file-pick screen (`ACTION_PICK_FILE`, a FileProvider answer), the sketchbook's reference image first | `cloud.md`, `templates.md`, `seam.md`, `sketchsprout.md` |
| 3 | The Settings row **Links** under the wait overlay for its whole run, with how far it is and Cancel: the rebuild stops at its next item, the items walked fresh and the rest as they were (nothing to clean up, an item's rows being one statement), and says how many. The overlay's Cancel is general, for any work that can stop between its steps; Greg prefers the full-screen overlay to a dialog for such waits | `links.md` |
| 4 | **Back up now** under the same overlay in place of its dialog, with Cancel: the engine asks before every unit on either leg; nothing to undo (atomic writes, stamps per success), the rest copied next time, the last-run figures unmoved, reported as *Backup stopped* | `backup.md` |
| 5 | **Restore** under the same overlay in place of its dialog, Cancel through the copy and the checks (the fetch asks before each file, the orphan check before each store; the staging discarded, the garden untouched, *Restore cancelled*), and none from *Installing* on, the commit being the point of no return | `backup.md` |
| 6 | Backup and Restore park every session first, as the passphrase change does: an app behind holds no file, one whose park is in flight gives its file up, the app resumes as ever; a held item is the exception, not the rule (Greg) | `backup.md` |
| 7 | Soil's own browser over this device's files in place of the Android picker, behind Android's All files access (offered once at the first pick, a Settings row, Soil restarting itself to take the access): the browser generalised over a source (`BrowserSource`: the cloud, the device), Import, the template library's import and export, the file-pick screen, Export's local Folder row remembered per kind, the backup folder by path (`FileBackupWriter` behind `BackupWriter`) and the restore's folder (`FileBackupReader`); the Android picker kept behind the access (Greg) | `files.md` |
| 8 | Sproutscape's launcher icon is the seedling, the Garden's mark (Tabler "seeding"), in place of the ploughed field (Greg) | |
| 9 | A notebook card's corner glyph is the Note app's own mark, the bound cover with three tabs (`ic_notebook_mark`), in place of Tabler "notebook", which stays the tag popup's door (Greg) | |
| 10 | Sketch's launcher icon is the Garden's sketchbook glyph (Tabler "sketching") in place of the pencil with a leaf, and New sketchbook wears that stroke with the plus in its corner (`ic_sketching_plus`) in place of pencil-plus (Greg) | |

