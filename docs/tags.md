# Tags

Tags live in Soil's index: `tag` and `tag_assignment`, an assignment naming an item and a
page (`""` for the item itself). `TagRules`, shared by both sides of the seam, says what makes
two pieces of text the same tag: trim, collapse whitespace, fold case under the root locale.
Case is kept as first entered. The transaction is the lock between the two writers, a screen on
IO and the seam on a Binder thread: the insert carries its caps, and an assignment resolves the
tag by identity inside its statement.

## The screen

One item's tags, or one page's, with every tag of the library below to take from. Started for a
result by the library's Tags… row and by the apps (`ACTION_TAGS`, modes browse, add, manage).
Three surfaces: the target's own tags (tap removes from this target), the add field (normalised,
created if new, attached), the list below (tap toggles; long press deletes everywhere, behind a
confirm). Manage opens on the item and every page the index knows. Every edit is written before
it is shown; the keyboard is never hidden while the field has focus.

## In the notebook

The tag button's sub-bar: Tag notebook, Tag page, Manage. A lone heading is tagged from the
selection bar with its words; Tag on ink goes through recognition first. The library's search
finds a tagged page as its own card.

Walked on the Nomad, phase 8, 2026-10-02.
