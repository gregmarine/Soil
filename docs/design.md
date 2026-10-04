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
| **Calsprout** | It has none yet | Soil's app store, one calendar |

Notes:

- Documents are plain documents. Nothing elaborate.
- Biblesprout uses Notesprout for notes. Commentaries may come later. It may never have files
  of its own.
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
See `BACKLOG.md`.

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
- Soil keeps one index of every link, since it writes every file. *Proposed:* kept current at
  each write, with a way to rebuild it from the whole library, as the Bible's notes index works
  today.
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
- Nothing is kept in step afterwards. Changing the notebook does not change the result, and the
  result does not need to know.

This removes the staleness tracking that documents carry today.

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
and the Scratch Pad. Tags are reached from the library.

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
| Ink into a sketch | Whether converted strokes land as graphite or as ink |
| The menu | Home, the Scratch Pad, Settings, then each Sprout app installed. Still open: any direct bar gestures |
| Conversion | Whether split files are linked to each other |
| Boot | Re-test taking the home screen back after a reboot |
| Firmware updates | How Soil notices that the menu lock has stopped working |

Settled since: a dangling link shows a dialog naming the loss and offers Edit link or Remove,
the row kept (`links.md`); kinds are told apart by a glyph on the card (`items.md`); the build
order of the Notesprout effort is in §16.

---

## 16. What was built (2026-10-03)

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

Decisions taken along the way are in each document; what was set aside is in `BACKLOG.md`.

