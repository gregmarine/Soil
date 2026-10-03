# Backlog

Ideas that are wanted but deliberately not part of the first version. Each entry records what
was decided when it was set aside, so it can be picked up without repeating the discussion.

Nothing here is scheduled. An entry moves into a plan only on a decision to do it.

---

## Clipboard across kinds

**Set aside 2026-09-28. Definitely wanted later.**

The first version's clipboard pastes only into the kind of app it was copied from. Anything
crossing kinds goes through the Scratch Pad or a one-shot convert.

The later version: the receiving app asks Soil for the clipboard in a form it can take, and Soil
or an extension converts it.

| Copied from | Pasted into | What would happen |
|---|---|---|
| Notebook ink | Sketchbook | Ink drawn into the raster |
| Notebook ink | Document | Recognised text |
| Document text | Notebook | A text object on the page |
| Bible passage | Notebook or document | Verses as text, with the reference |
| Sketch | Notebook | An image on the page, which notebooks do not have today |

These are the same conversions the Scratch Pad's "send to" needs, so one mechanism in Soil
should serve both. Adding this later should not change what the clipboard stores.

---

## Multiple calendars

**Set aside 2026-09-28.**

Calsprout starts with one calendar kept in Soil's app store.

When multiple calendars are wanted, each calendar is its own `.soil` file, so that it can be
exported or locked individually. The purpose is scope, for example personal and work.

Not yet decided: how several calendars are viewed together.

To keep this cheap, the first version's calendar tables must not assume there is only one
calendar, and the calendar screen must ask Soil for its calendar through a single call.

---

## Heading links into documents

**Set aside 2026-09-28. Needs a deeper discussion before any design.**

In the first version a link to a document opens the whole document. Documents are flowing text
and never get fixed pages.

The later idea is a link that opens a document at a named heading. This was only sketched, and
document linking as a whole has not been thought through. Discuss it with a real document on
the device, not in the abstract, before designing anything.

---

## Tasks and Today

**Set aside 2026-09-28. Not dropped, but not placed.**

The original Notesprout has a task list with routines and a Today dashboard. Notesprout SN never
had them. Whether and where they fit in Soil is undecided, so they are outside this effort.

---

## Releasing to other people

**Set aside 2026-09-28.**

Soil is built for one person for now, set up over adb. If it is ever released:

- A computer-side installer is the likely route, since sideloading is normal on Supernote.
- The side menu depends on an accessibility service. Turning it on without adb has not been
  looked at.
- Anyone installing it needs a safe way back to the stock device.

---

## Other devices

**Set aside 2026-09-28.**

Supernote comes first. BOOX and generic Android are wanted eventually, but g-paper is not
complete on BOOX. Two things are already known:

- The side bars are Supernote hardware. Elsewhere Soil runs as an ordinary app without the shell.
- Separate installs let each device carry only the apps it can support.

## Shapes in a notebook

Set aside 2026-09-30, during Notesprout's phase 4. SN's six shapes (rectangle, ellipse, triangle,
line, arrow, star) and the engine's transform mode were built and walked, and Greg asked for them to
go, at least for now: the tools, the `shape` row type, its reading and its rendering. Notesprout
does not read a `shape` row. Bringing them back is git history at `1e8d78d` (the last commit with
them whole).

**For the converter from Notesprout SN** (design.md § 11): check every SN notebook for `shape` rows
before converting. Greg believes he never used shapes but may have a few times and forgotten; if any
are found, he decides then what becomes of them (dropped, or shapes brought back first).

---

## Export formats beyond today's

**Set aside 2026-10-03, while building phase 11 of Notesprout.**

- **Colour in a PDF.** Pages go into a PDF as 8-bit grayscale, losslessly, because the Nomad's
  ink is grey on white and a page measured at a twentieth of the colour JPEG it used to be. When
  a Sprout app draws in colour, the PDF extension's page encoding is the one place that changes.
- **Vector ink in a PDF.** Strokes as paths would be crisp at any zoom. Not smaller: a page of
  strokes compresses to about what the lossless picture does. The bundle would carry a stroke
  form beside the picture, and the PDF extension a path renderer.
- **Real text in a PDF.** Headings and text objects as PDF text, with a font, need the bundle to
  carry positioned text runs. For a notebook they sit among ink; for Docsprout a document is
  nothing but text, so a text PDF belongs with that app.
- **The cloud destination** of the export screen, and the cloud source of import, come with
  phase 12.

---

## Export presets

**Set aside 2026-10-03, at the phase 11 walk.**

Built as in Notesprout SN (a named set of exporter and option values, a row of radios above the
formats, a long press to rename or delete) and taken out before it was walked: the SN shape was
never liked, and the direction is undecided. Nothing of it remains but the index step that made
its table and the one that drops it. When it comes back it starts from the question of what a
preset is for, not from the SN screen.
