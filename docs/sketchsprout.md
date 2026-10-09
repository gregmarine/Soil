# Sketchsprout

The sketchbook: the fifth Sprout app, and the third with items of its own. Notesprout SN's
`NSE · Sketch` extension brought across and given a file of its own: a sketchbook is one `.soil`
of pages, each page three page-sized rasters, graphite under ink under a translucent marker,
drawn with SN's pencil, gel pen, rubbing eraser and smudge and a marker SN never had, each pen
at a fixed size from its own ladder, over paper from the library and under guides that are
never saved into the picture. SN's sketch was a flag on a notebook page; Soil's is an item, made from the
library's toolbar, carried by backup and the cloud like any item, exported through Soil's screen,
copied page by page through the clipboard, made from a notebook by Convert, and linked to.

## The parts

| Module | What |
|---|---|
| `:sketchsprout` | The app: `SketchActivity` on `:paper`'s `PaperScreenActivity` in g-paper's RASTER page mode; `SketchbookSchema`, `SketchbookSql`, `SketchbookStore`, `SketchRow`; the rasters (`RasterRows`, `RasterImage`, `ImageHeader`, `RasterTiles`, `RasterEffort`, `PageFlatten`, `GuideRows`); the saves (`SketchSaver`, `SketchSaveGovernor`, `SketchSaveCadence`, `PushTracker`); the screen's pieces (`SketchToolbar`, `SketchToolState`, `SketchPalette`, `SmudgeRub`, `SketchEdit`, `PageTurn`, `GuideState`, `GridLayout`, `GuideSheet`, `GuidesBar`, `SketchGuides`); `export.RenderService`, `Relabel`, `RenderPlan`; `ingest.InkIngest`; `clip.SketchPageClip`, `SketchClipboard`; `MainActivity`, `SketchsproutApp`, `SketchPrefs` |
| `:paper` | Shared since this effort: `CoverSnapshot` (from Notesprout), `ShadeIcon.pencil` / `marker` and their fill glyphs, the icons `pencil-plus`, `scribble` and `marker` (Tabler `highlight`); `PaperToolbar`, `CollapsedTools` and `CollapsedChrome.PenKinds` taking N pen kinds (a kind an index), `PaletteBar`'s size rows |
| `:seam` | `EXTRA_PAGE_OF_ITEM` (the item picker straight at one item's pages); the value cap raised to 16 MiB (`SeamLimits`) |
| g-paper | 0.1.68: `RasterLayer.MARKER`, a third raster drawn over the other two, translucent, previewed live on the Supernote; 0.1.69: `MarkerTrim`, the marker's ends kept flat |
| `:soil` | New sketchbook on the library's top bar; `NewNotebookActivity` with a kind and its "Creating…" cover; the item picker taking several kinds and one item's pages; the index's cover from any kind; SQLCipher's cursor window sized above the seam's cap; the Scratch Pad's menu icon |
| Notesprout | Convert page / notebook to sketchbook on the page sheet; the picker's "Notebook or sketchbook page" shelf; a page of another kind handed to Soil with its page; the next-page arrow inserting past the last page (the pad's too) |

## Reaching it

Sketchsprout's icon and its row in the side menu open the last sketchbook, or the library when
there is none. The library's top bar has **New sketchbook** beside New notebook: the same screen
with the kind named in its title, the folder's naming scheme and the template browser, and a
"Creating…" box while the file is made. The card wears the sketch glyph and the cover the page
last showed, flattened as the export is. Soil opens a sketchbook in `SketchActivity`
(`ACTION_OPEN_ITEM`, kind `sketchbook`, behind the seam permission), at its last page or at the
page a link names.

## The file

`SketchbookSchema`: one table, `sketchbook`, with NotebookSchema's columns, so the notebook's
page, template and order SQL port by table name and the renderer's relabel is two statements.

| Row | Parent | Fields |
|---|---|---|
| `sketchbook` root | — | `text` the title, `refId` the page last open |
| `template` | root | token, width and height, `blob` the picture (`templates.md`'s reuse-by-bytes rule) |
| `page` | root | `"order"`, `refId` its template, width and height |
| `sketch_graphite`, `sketch_ink`, `sketch_marker` | page | `"order"` −1, `blob` a page-sized lossless RGBA WebP; one live row each, minted on the first save and rewritten in place; an empty layer is a soft-deleted row. The marker's row came with the marker (2026-10-08), no schema step: a build before it never asks for the row, copies it in a page clip, purges it by parent, and drops it only from its own export and cover |
| `guide_grid` | page | `text` JSON: lines or dots, the count, shown or hidden |
| `guide_image` | page | `blob` a lossy WebP fit to the page, `text` JSON: the opacity, shown or hidden |

A raster crosses the seam whole, in shared memory, as one value under the seam's cap; nothing
of SN's chunked push survives. `ImageHeader` reads the WebP's own header before any decode, so a
raster is only ever composited at the page's exact size. A delete is soft; the purge at the tidy
close takes every soft-deleted row but a template.

## The screen

`PaperScreenActivity`'s skeleton — the chrome band, the collapsed corner chrome, the exclusion
rects, the EPD hand-off — over g-paper in **RASTER** mode: the engine holds the three rasters
and composites the pen into them; the screen owns no stroke document. The paper is a g-paper
**sheet**, never a template: on the Supernote's direct path the template is not read on a raster
page, so the paper, the reference image and the grid are composed into one sheet bitmap
(`GuideSheet`) and set together. A page turn reads and decodes everything on IO first, then
clears, sizes, sets the sheet and loads both rasters back to back on Main: one coalesced rebuild
and one panel present (a suspension between them is two). The pager's next arrow past the last
page inserts one, like the swipe.

The top bar: Back, Pencil, Pen, Marker, Eraser, Smudge, Guides. The three pens are one
`Tool.PEN` to the engine, told apart by what it is armed with (`SketchToolState`: the kind, and
each kind's shade and size). A re-tap on the armed pen of any kind hangs the palette: Atelier's
sixteen tones in four rows, white first, and under them the kind's **sizes**, four to a row,
each cell a sample of the stroke at its real width on the page (the marker's at its
translucency, the two widest badged "2x" and "4x" since their samples fill the cell alike). The
ladders are fixed (Greg, 2026-10-08), a millimetre being a millimetre on the page at its 300 ppi
(`SketchPalette.PPI`, the Nomad's and the Manta's pitch alike), stored as an index so a size can
be retuned without stranding a choice: the pencil 1, 2, 4 px and a 5 mm shading lead (default
1 px, the hand's hairline); the gel pen 0.1, 0.38, 0.5, 0.7, 1.0 mm (default 0.5 mm, 5.9 px, a
hair over the 5 px that stood before); the marker 1, 3, 5, 10, 20 mm (default 3 mm). The
pencil's default shade is `#505050`, the pen's and the marker's black. The **marker** is
translucent, the renderer's 45 %, laid on its own raster over graphite and ink so both show
through it; two strokes are darker where they cross; the rubber and the smudge never touch it
and it comes off by undo alone; its ends stay flat, the samples within half its width of either
end trimmed so a wobble at the lift is never a notch. The eraser rubs at 12 px; the smudge tool
is 24 px, and a single finger rubs too (`SmudgeRub`). The device remembers the pen kind and
each kind's shade and size, never the eraser or the smudge. The bottom bar is the pager alone. Undo
and redo are the gestures, no arrows: a raster edit is the tiles it touched, 64 px squares in
both directions (`RasterTiles`, `RasterEditBuilder`), swapped back through the engine; a page
insert, delete, paste or template change is a structural entry of no cost; the stack's budget is
48 MiB and evicts the oldest.

Saves are a governor per raster under one cadence: a copy of the raster on Main three
seconds after the last mark or at the fifteen-second deadline (at the pen's lift, or under the
pen), the encode and the write on IO under one lock so a page's rasters land in order. A turn
waits only for the copy, and holds the pen off the page from that copy to the next page's load; a
page is not read again until its writes in flight land and its parked bytes are offered again, and
what still cannot land is loaded from those bytes rather than from the older row. A contact
whose lift was lost is ended by the engine inside the swap, on the outgoing page (g-paper
0.1.70): its mark is copied there and then and saved to that page, and is dropped with a page the
operation has just deleted. The
encoder's effort follows the page's coverage (`RasterEffort`): a sparse page takes the full
search, a page over a tenth marked the fast one — measured on the Nomad, a page shaded edge to
edge is 3.4 MB either way and 54 s against 1.4 s. A write that fails parks its bytes and retries
on the beat; a raster over the cap is told once ("Too much to save") and waits for the next
mark, kept for its page if the screen has turned away; Back flushes first and, failing that, asks:
Try again, or Leave anyway (as does an open of another sketchbook over this one). A stored raster
that is there and will not read refuses the page — the turn or the open, with a message — and is
never opened blank, which the next mark would save over it. The session closes only after every
write in the air has landed.

**Guides** (SN's, per page, never in the file's picture): a grid of lines or dots with SN's cell
counts, and a reference image from the system picker, fit to the page at 10, 25, 50 or 75 %,
each shown or hidden from the Guides bar. They live in their own rows and never reach the export
or the cover. A page whose guide rows could not be read shows none, and its picks are not written.

A finger long-press raises the page sheet: Copy page, Paste page (while the clipboard holds
one), Paste ink (while the notebook's slot holds ink), Page template, Delete page, "N links to
here" (while anything links to the page or the sketchbook), Export page…, Export sketchbook….
Debug builds keep two doors for walks, since adb cannot draw: a broadcast that composites test
strokes (a lattice, or `--ei dense N` lines edge to edge, or `--ez noise true` random colour),
and one that dumps the page as the export sees it.

## Paper

New sketchbook offers the template browser as New notebook does; the pick lands on page 1, the
folder's default when nothing was picked. Page template on the sheet opens the picker
(`ACTION_PICK_TEMPLATE`) and the answer is a reused or minted `template` row, one undo step
(`TemplateChanged`). The paper is drawn under the rasters on the screen, under the export and
under the cover.

## Export

`export.RenderService` answers `ACTION_RENDER` for the kind `sketchbook` in the notebook's
shape: the item opened through the seam for the call and closed untidied, `pages()` naming the
ids and numbers, `render()` flattening each page as the cover is — white, the paper under the
export screen's paper toggle, graphite, ink, then the marker on top, in true greys, never the
guides — one page in
memory at a time into Soil's page bundle; `relabelStatements` for an import under a new id;
pages only. Export page… and Export sketchbook… go through `ACTION_EXPORT` with the page id and
`RETURN_TO_APP` after the exit's flush, and Soil opens the sketchbook again on the way back.

## The clipboard

**Copy page** writes the page onto the `sketchbook` kind's slot as the seam's binary rows
(`SketchPageClip` over `RowCodec`, never Base64: a raster is megabytes): its row, its paper's
template row, its rasters, both guide rows; the rasters are flushed first, and a page over the
slot's cap is refused with a message. **Paste page**, offered while that slot holds one, asks
Before or After and inserts the copy with every id fresh, the paper reused by token and size when
this sketchbook already holds it, one structural undo entry. **Paste ink**, offered while the
notebook kind's slot holds a lasso's, the pad's or a copied page's ink (of several copied pages,
the first, as the pad and the calendar take it), lays the strokes centred
(`InkPlacement`) and bakes them black into the ink layer as one undo entry. The headers are read
at open and at every return to the front. Sketch out to a notebook is in `BACKLOG.md`.

## Convert, in Notesprout

A notebook's page sheet has **Convert page to sketchbook** and **Convert notebook to
sketchbook**. The pages' strokes, their own and those their links wrap, and their paper travel as
the notebook's page envelope (`InkClip.pageEnvelopeOf`), handed to Soil by `makeItemFromFile`
with the extension `soilink` and a type no file on the device carries, which Soil routes to the
app whose renderer takes that file in; Sketchsprout's `ingest` reads the pages (`InkIngest`,
pure), bakes every stroke black as pen ink into an ink raster one page at a time
(`StrokeRasterizer`, headless), writes the root, one template per distinct paper, the pages and
the rasters in batches under the seam's caps (a failure part-way fails the Convert, and Soil takes
the item away), tells the library the pages and sets the first as the cover.
Then **Open · Leave a link · Done** (Greg, 2026-10-07): Leave a link lands the sketchbook's name
as a text object wrapped in an item link at the nearest clear spot on the page showing. This
settles `design.md` §15's "graphite or ink" as ink.

## A sketch page as a link target

A notebook links to a sketchbook, or to a page of one, from the picker's shelves: "Notebook or
document" for the whole, "Notebook or sketchbook page" for a page, where Soil's item picker
lists notebooks and sketchbooks and, for a sketchbook, asks its app for the page and answers at
once. A document's Choose from library does the same. Edit link reopens the picker on the
sketchbook chosen. A tap follows through Soil (`ACTION_FOLLOW` with the page), and the
sketchbook opens on that page over the notebook or document; Back returns. The sheet's "N links
to here" lists them in the shared backlinks panel, "The whole sketchbook" beside a link to the
whole, and a row opens its source over the sketchbook. A sketch page links **out** nowhere for
now (Greg, 2026-10-07): the two shapes considered are in `BACKLOG.md`.

## Walked on the Nomad

Phases 1 to 10, 2026-10-07 and 2026-10-08; the marker and the sizes (M1 to M3), 2026-10-08.

## Decisions (Greg, 2026-10-06 to 2026-10-08)

- All of SN's tools: pencil, gel pen, sixteen shades each, the rubbing eraser, the smudge tool
  and the finger rub, the guides.
- The paper library under the raster too, picked at New sketchbook and from the page sheet.
- Convert from a notebook replaces SN's "Bring in ink": strokes black into the ink layer, with
  Open · Leave a link · Done at the end.
- The clipboard: Copy page and Paste page between sketchbooks, asked Before or After; a
  notebook's or the pad's ink pasted into a sketch page as ink, centred. Sketch out: the backlog.
- The page sheet: Export page, Export sketchbook, Copy page, Paste page, Delete page; inserts
  stay on the swipe, and the next-page arrow inserts past the last page like the swipe.
- The sketchbook keeps the sketch glyph; the Scratch Pad's menu row takes Tabler's `scribble`;
  New sketchbook is Tabler's `pencil-plus`.
- A sketch page is a link target now; a link out of one is set aside.
- The device remembers the last pen kind only, never the eraser or the smudge.
- Undo and redo by gesture only, no arrows.
- The New screen names the kind it makes, and making a file shows "Creating…".
- The seam's value cap is 16 MiB.
- **A marker** (2026-10-08): translucent, on its own raster over pencil and pen, both showing
  through; crossings build up darker; removed by undo only; all sixteen shades, black the
  default; its ends flat, the wobble at the lift trimmed.
- **Fixed sizes**, chosen on each tool's palette: pencil 1, 2, 4 px and 5 mm; pen 0.1, 0.38,
  0.5, 0.7, 1.0 mm; marker 1, 3, 5, 10 ("2x"), 20 mm ("4x"); four to a row.

Proposed by the build and standing: `SketchbookSchema` as NotebookSchema's columns; the rasters
as lossless RGBA WebP, one value each; paper and guides in one sheet; no seam or index change
beyond the page-of-item extra and the cap; a 48 MiB undo budget; "Paste ink" as its own row;
the effort by coverage; the over-cap message; the marker's row without a schema step; 300 ppi as
a constant of the page; a pen kind as an index on the bars; Tabler `highlight` for the marker;
Paste ink and Convert baking into ink, never the marker.

## Traps

On a RASTER page every whole-page engine call posts one coalesced rebuild for the next main-loop
turn; a suspension between them (a decode on IO) is two rebuilds and two panel presents, so the
page is decoded first and the engine calls are made back to back. The Supernote's direct path
never reads the template on a raster page: paper lives in the sheet. SQLCipher's cursor window
was 8 MiB, which is why the seam's cap was 6; a value larger than the window is written and never
read back, so Soil sizes the window above the cap where the library loads. The lossless encoder
at effort 100 is a minute on a dense page. A broadcast receiver that bakes on Main holds the
broadcast past its timeout and the app is killed: the debug doors post their work. A reloaded
raster differs from the live one by at most one grey level (premultiplied alpha). Sketchsprout's
own icon reopens the last sketchbook, which on a walk is the person's; reach a test sketchbook
through the library. A butt-capped path with round joins ends however the hand's last samples
wobble inside its width — a stub out of the join's disc is a notch — hence g-paper's
`MarkerTrim`. A size added at the front of a ladder shifts every stored index one step; add at
the end, or accept it once as the 0.1 mm pen did. A third page raster is +10.5 MB on the Nomad
and +19.7 MB on the Manta, only once a marker lands.
