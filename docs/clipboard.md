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

## Send between the Scratch Pad and a notebook

Ink crosses as one `InkWire` document, fresh ids on arrival, coordinates one to one. The
notebook's selection bar sends ink alone to the pad, on a new page or the current one; Soil
parks it in memory and the pad lands it selected as it opens over the notebook. The pad's own
Send, the page from the top bar or the lasso's strokes from the selection bar, shows only with
a notebook behind, parks the ink in Soil, and the notebook pastes it as it comes back to the
front, one undo step, landed selected. A parking never taken up is replaced by the next, and
all of it is gone with Soil's process.

Walked on the Nomad, phases 8 and 9, 2026-10-02.
