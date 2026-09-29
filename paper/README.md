# :paper

What Soil and every Sprout app with a paper surface share: the theme, the chrome, the stroke
codec and ink kept as rows.

| Package | Holds |
|---|---|
| `core` | Stroke codec, dialogs, logging, swipe arithmetic |
| `chrome` | The toolbar, the floating bars, the hide and show of the bars, page gestures, the undo stack |
| `ink` | The two screen base classes, the ink document and the stroke table |
| `store` | `RowStore`, and the values that cross it |

It depends on g-paper and androidx only. It never depends on `:soil` or `:seam`. The g-paper
version is pinned in `build.gradle.kts` here and nowhere else.

## Where it came from

Copied from Notesprout SN's `:sn-screen` and `:ext-ink` on 2026-09-28, with the packages renamed.
Comments that cite an "arc" and a step, such as "arc 36 / C2", refer to Notesprout SN's history.
The matching documents are listed in `docs/references.md`.

What changed in the copy:

- Ink is read and written through `RowStore`, not through Notesprout SN's extension store. The
  batch splitting and planned reads that existed for the binder's size limit are gone.
- Send, the pen shade picker and the transfer session were left behind.
- The chrome state is remembered by the screen itself, not passed in and out on an Intent.
