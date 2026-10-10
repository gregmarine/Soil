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
| Notebook ink | Sketchbook | Ink drawn into the raster. **Done 2026-10-07** (`docs/sketchsprout.md`: Paste ink) |
| Notebook ink | Document | Recognised text. **Done 2026-10-04** (`docs/clipboard.md`) |
| Document text | Notebook | A text object on the page |
| Bible passage | Notebook or document | Verses as text, with the reference. **Done 2026-10-05** (`docs/clipboard.md`) |
| Sketch | Notebook | An image on the page, which notebooks do not have today |

These are the same conversions the Scratch Pad's "send to" needs, so one mechanism in Soil
should serve both. Adding this later should not change what the clipboard stores.

**2026-10-04:** the first row exists, built with Docsprout: the Scratch Pad and a notebook's
lasso copy ink, and a document pastes it as words, read at the paste and kept beside the ink.
The pad's "send to" is that copy now. The other rows are as they were.

---

## Multiple calendars

**Set aside 2026-09-28.**

Calsprout starts with one calendar kept in Soil's app store.

When multiple calendars are wanted, each calendar is its own `.soil` file, so that it can be
exported or locked individually. The purpose is scope, for example personal and work.

Not yet decided: how several calendars are viewed together.

To keep this cheap, the first version's calendar tables must not assume there is only one
calendar, and the calendar screen must ask Soil for its calendar through a single call.
**2026-10-06:** built that way — a `calendarId` on every table, one lease per process.

---

## Heading links into documents

**Set aside 2026-09-28. Needs a deeper discussion before any design.**

In the first version a link to a document opens the whole document. Documents are flowing text
and never get fixed pages.

The later idea is a link that opens a document at a named heading. This was only sketched, and
document linking as a whole has not been thought through. Discuss it with a real document on
the device, not in the abstract, before designing anything.

**2026-10-04:** documents exist and link both ways, whole (`docs/links.md`). A `soil:` address
has room for a part after the item; nothing reads a heading there yet.

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
  nothing but text, so a text PDF belongs with that app. **Done for documents 2026-10-04**
  (`docs/export.md`); a notebook's headings and text objects are still pictures.
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

---

## Documents: what Docsprout left out

**Set aside 2026-10-04, when the Docsprout effort was granted and as it was built.**

- **Tables, code blocks and images.** The first cut is Notesprout SN's Markdown set. A table's
  rows and a fenced code block in an imported file are kept as raw lines and written back
  untouched; image syntax stays literal text. Each needs its own editing in the rendered mode,
  and images need somewhere to live in the file.
- **Paper behind a document.** A template from the paper library as a document's background.
  The known problem: typed lines do not sit on ruled lines, since the text size changes and a
  heading is taller than a paragraph, so a ruled paper would need the layout to follow its
  pitch, or only unruled papers would be offered.
- **Starter content.** A new document that begins with words already in it (a letter, minutes),
  chosen when it is made. Apart from paper: it is content, not a background.
- **Bible lookup.** SN's editor could insert a passage. It waits for Biblesprout, which owns
  the text. **Done 2026-10-05**: Insert a Bible passage (`docs/docsprout.md`).
- **A password on the text PDF.** Docsprout writes that PDF itself with Android's own writer,
  which cannot protect one. Protection is `:ext-pdf`'s work, so the text PDF would have to pass
  through it.
- **The Proofread dictionary in Soil.** The person's own words and the on/off switch are in
  Docsprout's preferences on this device, so they are not backed up and do not follow the
  library. When Soil has an app store for such things, they move there, and the words already
  added come along.

---

## A table of contents in a PDF

**Raised by Greg 2026-10-04, at Docsprout's phase 7 walk.**

A PDF made from a notebook or a document could carry its headings as a table of contents: a
PDF outline (the reader's bookmarks), a printed contents page, or both. For the picture PDF the
page bundle would have to carry the headings and the page each is on, and `:ext-pdf` write the
outline. The text PDF does not pass through `:ext-pdf` today; this is the same road a password
on it needs.

---

## Soil's own picker for local files

**Raised by Greg 2026-10-04, at Docsprout's phase 8 walk.**

Import, the export destination and the backup folder use the Supernote's system picker, which
has no back button and can leave a person stranded in it. Google Drive already has a picker of
Soil's own (`CloudBrowserDialog`); local files and folders should get the same.

---

## Copy and paste in place of Send to the Scratch Pad

**Raised by Greg 2026-10-04, at Docsprout's phase 10 walk.**

The Scratch Pad copies to the clipboard now, and a notebook and a document paste from it. The
other direction is still the old Send: a notebook's selection bar sends ink to the pad. The
wish is one way for both: the pad gets a Paste that places the clipboard's ink (strokes only),
and then the notebook's Send to Scratch Pad, its placement sheet, `sendInkToPad` and
`PadTransfer` can go.

**2026-10-06:** the pad has its Paste (strokes under the lasso, a page from the long-press
sheet; `scratchpad.md`). What remains is retiring the notebook's Send to Scratch Pad.

## The Bible: what Biblesprout left out

**Set aside 2026-10-05, as the Biblesprout effort was built.**

- **A text size setting in the reader.** SN fixed 30sp at 1.5; it stays fixed.
- **Red letters.** Stored in the Bible, never rendered, as in SN.
- **A second translation.** The BSB alone; the builder takes any USFM, the reader one file.
- **Commentaries**, as the design says: may come later.
- **References linked in a notebook's text objects.** A typed text on a page is not read for
  references; only a document's words are. A text object's reference is linked through the
  Insert bar's Bible reference, or by lassoing handwriting.
- **A link the pass declined, offered later.** A reference the writer unlinked is linked again
  only by the Link tool on its words; there is no list of removed references to review.

---

## The calendar: what Calsprout left out

**Set aside 2026-10-06, as the Calsprout effort was built.**

- **Tasks, routines and the Today dashboard**: as above, not placed.
- **Search over event titles.** None; the list is by day.
- **Notifications.** Reminders are the look-ahead on the list, as in SN; nothing fires.
- **A date-change receiver.** Today's ring is re-read when the screen resumes, as in SN; a day
  that rolls over while the calendar is in front shows it on the next navigation.
- **Multiple calendars**: as above.
- **A typed date linking on its own.** A date in a document's prose is too common to link
  unasked; the Link tool's Choose a day, a typed `cal:` address, and the notebook picker's
  Calendar day shelf are the roads.
- **The marks without the grid in an export.** The paper toggle takes today's ring and the
  events' marks off with the grid; ink alone on white is the other setting.
- **Retiring the notebook's Send to Scratch Pad**: as above.

---

## The sketchbook: what Sketchsprout left out

**Set aside 2026-10-06 to 2026-10-08, as the Sketchsprout effort was built.**

- **A sketch out to a notebook.** The clipboard's last row above: a sketch page's picture onto a
  notebook page, which notebooks cannot hold today.
- **A link out of a sketch page** (Greg, 2026-10-07: none for now). A sketch has no objects and
  no lasso, so the link has to be something else. Two shapes were considered: one link per page
  from the page sheet, drawn as a mark in a corner; or a hotspot, a rectangle drawn with the
  stylus and wrapped as a `link` row over the raster. Either writes `soil_link` as a notebook
  does. Decide the shape before building.
- **Paintsprout** (`design.md` §2): whether it works with sketchbooks, undecided.
- **Reopening an item after Soil is lost.** If Soil's process dies while a sketchbook — or a
  notebook — is open, the app's session is dead and the screen cannot make a new one: a save
  retries and fails, and leaving asks Try again or Leave anyway. `SeamConnection.onLost` is the
  hook; the screen would reopen the item and re-offer what it holds.
- **A large bake on Main.** Pasting hundreds of strokes as ink composites them on the main
  thread; a notebook page's worth is seconds. Chunk the bake, or bake off Main as Convert does.

---

## One link picker, Soil's, with page previews for every kind

**Raised by Greg 2026-10-08, at Sketchsprout's phase 9 walk. Set aside for after Sketchsprout.**

A sketchbook's page is chosen in Soil's item picker as a list of names, while a notebook's
pages are previewed as cards in Notesprout's own picker, which came over from SN before Soil's
existed. The two are one job done twice, and Greg asked for the universal shape:

- **Soil's picker grows a page-card grid** with previews for any item whose app can draw its
  pages, through the app's renderer (the export service: Notesprout's for a notebook,
  Sketchsprout's for a sketchbook), a grid page rendered at a time and scaled to the card.
  Docsprout's Choose from library gets the previews with it.
- **Notesprout's picker is retired.** Soil holds every file's one connection, so This
  notebook's pages are drawn through the renderer too, the current page marked and the home
  notebook not excluded; New page (before or after) and New notebook become *answers* the
  picker gives and the app carries out, making the page and the link together; the calendar
  day shelf moves into Soil's picker; Edit link reopens it on the item and page already chosen.
  The link's style (underline or none) stays Notesprout's, asked after the pick.
- One chooser for anything that links, in every Sprout app to come.
