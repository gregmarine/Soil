# The Scratch Pad

For the quick thought that has no place yet. It is part of Soil, tied to nothing, and opened
from the side menu over whatever app is in front. Back returns to that app.

Its row in the side menu wears Tabler's `scribble` since Sketchsprout (Greg, 2026-10-06): the
sketch glyph it wore is the sketchbook's.

It is Notesprout SN's pad, moved inside Soil. How the screen works underneath is in Notesprout
SN's `scratchpad.md` and `sn-screen.md` (see `references.md`).

## What it does

| | |
|---|---|
| Pages | As many as wanted. A swipe past the last page makes one. The pad always has one page |
| Pen | One: black, 3 px |
| Eraser | Point or lasso. A second tap on the armed eraser chooses |
| Lasso | Select, drag, delete |
| Undo, redo | Two-finger and three-finger double-tap. Kept in memory, for the screen's life |
| Bars | A one-finger double-tap hides and shows them. The pad opens as it was left |
| Delete a page | A long press asks first. Undo brings the page and its ink back |
| Copy | The page (the top bar, or the long-press sheet) as a notebook page clip, or the lasso's strokes from the selection bar, to the library's clipboard. The pad stays open. A notebook pastes a page as a page and ink as ink, a document ink as words (`clipboard.md`) |
| Paste | Strokes: a stylus tap on bare paper under the armed lasso, centred on the tap, or the lasso's re-tap popup at the source. A page: the long-press sheet's Paste page, a new page after this one at the copied page's size (2026-10-06) |
| Page sheet | A long press: Copy page, Paste page while the clipboard holds a page, Delete page behind its confirm |
| Receive | Ink sent from a notebook's selection lands selected, on a new page or the current one |

## What it does not do yet

- **Retiring Send.** The pad pastes now, but a notebook's selection bar still sends ink to it
  the old way (`BACKLOG.md`).
- **A drawing to a sketchbook.** It waits for Sketchsprout.
- **Shades.** There is no shade picker.

## Where the pages live

In a store of its own, `garden/scratchpad.db`, encrypted under the global key. It is not an item
and is not shown in the library.

```sql
page   (id, position, width, height, createdAt, updatedAt)
stroke (id, pageId -> page.id ON DELETE CASCADE, "order", color, width, style, blob)
state  (key, value)      -- 'current' -> the current page
```

- Ink is saved 800 ms after the pen rests, and always before the screen is left.
- A page turn ends a contact whose lift was lost (g-paper's `clearForContentSwap`) before the
  departing page's last flush, so the stroke it commits stays on the page it was written on.
- A store closed under the pad (a rotation, Forget, a restore) is opened again at its next call.
- A page row is never written with `INSERT OR REPLACE`: the replace deletes first, and the
  delete would take the page's strokes with it.

## How it differs from Notesprout SN's

| Notesprout SN | Soil |
|---|---|
| A separate app, lent a store by its host over a binder | A screen inside Soil, with a database in the same process |
| Writes split into batches under 4 MiB; reads planned in ranges | One transaction per write, one query per page |
| Opened from a notebook or the library | Opened from the menu, over any app |
| Pen shade handed in by the host | Fixed |

## The key gate

The pad checks the gate before it draws anything. Until the recovery key has been saved, or
while the library is locked, it leads to the screen that opens the gate and comes back.

## Walked on the Nomad, 2026-09-28

The pad opens, its store is created encrypted in about two seconds, and it reopens under a new
key after a rotation.

## Walked by hand, 2026-09-29

Writing, erasing, the lasso, undo and redo, pages, and the bars, including over other apps.
