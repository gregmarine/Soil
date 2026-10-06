# The clipboard and Send

## The clipboard

It lives in Soil's index, one slot per kind of item, sticky, replaced by the next copy and
cleared only on an app's say, so a copy outlives the app and travels between notebooks. Soil
stores the header beside the bytes and answers it blob-free, so a sheet can decide what to
offer without reading megabytes. A payload over the seam's value cap is refused on the way in.

In the notebook: a page from the page sheet (Copy, Cut, Paste before or after), a lasso
selection from the selection bar (Copy, Cut), placed by a pen tap on bare paper or by the
lasso popup's Paste, undoable both ways. The pure half is `PageClip` and `ObjectClip`: every
pasted row gets a fresh id through one map, so a link's wrapped children re-parent onto the
copied link; `"order"` is preserved on content and rebased per type for an object paste; a
copied link's own-notebook target is re-pointed across notebooks; a sticky note's content
travels un-shifted.

## Ink between the Scratch Pad, a notebook and a document

Reworked on 2026-10-04 (Greg): what the pad sends is a copy to the clipboard, not a hand-over to
whatever is behind it.

- **The pad copies.** Copy is always on the pad: the page from the top bar, or the lasso's
  strokes from the selection bar. The ink goes onto the notebook kind's slot, written as a
  notebook's own lasso Copy writes it (`seamkit.clip.ClipEnvelope`, `ClipRow`, `InkClip`, in
  `:seam-kit` so Soil and both apps share the one shape). The pad stays where it is. The copy
  is stored, so it outlives Soil and a restart, and it is pasted as many times as wanted.
- **A notebook pastes it** with the Paste it already has, as ink. Its clipboard mark is read
  again each time it comes to the front.
- **A document pastes it as words.** The Paste tool on the format bar offers Paste as words and
  Clear clipboard; Ctrl+V pastes whichever was copied last, the ink or the device's own text.
  The ink is read by the recogniser as a page, hand-wrapped lines joined into paragraphs, and
  put in at the cursor of whichever mode is showing (`InkPaste`). The words are read once and
  kept beside the ink, in a slot of their own (`ink_words`) keyed by the copy's time, for the
  next paste. Ink lassoed and copied in a notebook pastes into a document the same way.
- **Clear clipboard** empties the ink, its kept words, and the device's clipboard too, so
  nothing is left to paste.

The other direction is still Send: a notebook's selection bar sends ink alone to the pad, on a
new page or the current one, as one `InkWire` document with fresh ids on arrival; Soil parks it
in memory (`sendInkToPad`, `PadTransfer`) and the pad lands it selected as it opens over the
notebook. Replacing that with the same copy and paste is in `BACKLOG.md`.

## A passage from the Bible

Reworked from SN's Send on 2026-10-05 (Greg): the reader copies, the apps paste, and the paste
asks each time.

- **The reader copies.** Copy on its top bar puts the passage on screen, or the chapter being
  read as a whole chapter, on the `bible` kind's slot: the wire, its label and the verses as the
  reader's Markdown (`seamkit.clip.BibleClip`), so a paste needs nothing more from the reader.
  The reader stays where it is.
- **A paste asks: the reference, or the verses with it.** In a document, Ctrl+V and the Paste
  tool take the passage when it was copied after the clipboard's ink and the device's text:
  the reference goes in as a link under its label, the verses as paragraphs under that link. In
  a notebook, the lasso popup's Paste and a pen tap on bare paper do the same: the reference as
  a linked text where the pen tapped, the verses in the verses column near it, under the page's
  cap. Clear clipboard empties this slot too.

Walked on the Nomad, phases 8 and 9, 2026-10-02; the rework, Docsprout's phase 10, 2026-10-04.
