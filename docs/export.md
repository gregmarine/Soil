# Export and import

Getting an item out of the library and into it: one export screen in Soil for every kind of
item, one Import button on the library, and the extensions that write and read the files.
Soil keys and prepares; an extension only ever streams bytes between two file descriptors.

## The parts

| Part | Where | What it does |
|---|---|---|
| The exporter and importer points | `:ext-api` | `IExporter` (describe, export) and `IImporter` (describe, importDocument), their descriptors, the page bundle |
| `ExportActivity` | `:soil` | The export screen: scope, format, the format's options, the password block |
| `ImportFlow` | `:soil` | The library's Import button and the whole import pipeline |
| `IItemRenderer` | `:seam` | What a Sprout app offers Soil: its pages as names, as a rendered bundle, and the statements that relabel its file |
| `RenderService` | `:notesprout` | Notesprout's renderer |
| `:ext-soilfile` | extension | Exports and imports the item file itself (`.soil`) |
| `:ext-pdf` | extension | A PDF of the pages on pdfbox: grayscale lossless pages, page links, an optional password |
| `:ext-image` | extension | One image per page: PNG, JPEG or WebP, with a quality for the two lossy ones |

## The seam

Everything that touches a key runs in Soil: the checkpoint, the copy, the keying transform,
the destination. An exporter receives two descriptors, the read side carrying what its
descriptor asked for, and a spec of option values and the item's display name. Two source kinds:

| Source kind | The read fd carries | Verified by |
|---|---|---|
| `SOURCE_FILE` | The prepared item file, verbatim | The byte count must equal the file's |
| `SOURCE_PAGES` | A page bundle rendered by the item's app | The destination's own size account |

A `DELIVERY_PER_PAGE` exporter (images) is called once per page, each with a fresh document in
a folder the person picked; Soil splits the bundle. The one secret that crosses is the password
for a protected output, typed in Soil's own fields and never logged, saved or put in an Intent.

Reserved option ids, recognised by Soil: `keying` (keep, a new passphrase, plain), executed by
Soil on the copy; `template`, executed by the render; `protect`, the extension's work with the
password Soil collected; `format`, which names the file after the image format chosen. An
exporter is dropped at discovery when it declares an option kind this build cannot draw, a
reserved option for the wrong source kind, an unknown keying or format choice, or both `rekey`
and `protect` (one field block).

## Rendering

Soil cannot draw a page. The app that owns the kind answers `Seam.ACTION_RENDER` with a
service guarded by Soil's permission; Soil binds it and asks for the pages' names (numbers and
titles, for the scope row and the file names) or a rendered bundle into a descriptor. The
service opens the item through the seam exactly as the app's screen does, reads it page by
page, draws each with the raster the templates use, encodes it losslessly (PNG), and writes
nothing to the item. With a bundle version that knows links it adds sticky notes as endnote
pages after the item's pages, captioned "Note N — from page P", with jumps both ways, and a link
to another page of the same item becomes a jump too.

## The export screen

Reached from an item's long-press sheet in the library, and from an app's page sheet with
`Seam.ACTION_EXPORT`; an app closes its item first so the file is free, and Soil reopens it on
the way back (`EXTRA_RETURN_TO_APP`). Rows, top to bottom: the scope (this
page or the whole item, only from a page sheet and only when a pages exporter is installed);
the format, a plain label with one exporter; the format's options; the passphrase or password
block; the plain-text warning. The last exporter used is remembered. Export presets were set aside before the walk
(`BACKLOG.md`); the index step that made their table is followed by one that drops it.

The file export: the item is refused while an app holds it open; its meta table is stamped
with the library's name for it and the folders it is in; the file is checkpointed, copied into
the cache, keyed as asked (a new passphrase or plain through the transform the rotation uses,
each copy read back before it is accepted), streamed, and verified. A failure deletes a partial
destination only when Soil touched it or it was empty to begin with, and says which.

## Import

The Import button shows while an importer is installed. The picker is filtered on every
importer's MIME types plus anything; the importer is chosen by the picked name's extension.
The bytes land in the cache, are checked against what the provider said, probed, unlocked
(this device's passphrase first, then a typed one under the attempt limiter), and keyed to this
device: a file already under it passes through after an integrity check, any other is
transformed. Then the file's meta table says what it is: a Soil item of some kind, which must
have an app installed to open it.

Three questions, none writing anything: the same id already alive in the library (Replace or
Keep both; a dead id, a folder's id or another kind's id gets a fresh id with no question); the
folders (its own, recreated by id where missing and stopped one level up at anything else, or a
picked folder); the same name in that folder (Replace or Keep both; a Keep both already chosen
names it "X Copy"). A fresh id means relabelling the file: Soil's meta and link mirror by
Soil's hand, the app's rows by the statements its renderer gives, each held to the seam's
statement rules. Then the file goes into the garden by a staged copy and one rename, the row
into the index, and whatever Replace retires is deleted last.

## Decisions

- `.soil` stays the extension. SN's files share it; Soil refuses one as not its item.
- PDF pages are 8-bit grayscale compressed losslessly, measured on a page of handwriting at a
  twentieth of a colour JPEG at quality 100, and exact. No size option: a lossy one would be larger.
- Images: PNG, JPEG and WebP, a quality choice for the two lossy formats.
- Items have no passphrase of their own, so every import keys to this device. "New passphrase"
  on export keys the exported copy alone.
- The cloud destination waits for phase 12. Documents and sketches are not Notesprout's.

## Not yet walked

Everything above is built and tested on the JVM; the device walk is phase 11's gate.
