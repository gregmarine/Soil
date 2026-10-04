# Links and backlinks

A lasso selection wraps into a link that points at a page of this notebook, at another item,
or at a page of one. Page level on both ends; a link to a whole item is a link with no page.

## In the notebook

The wrapped ink and objects are re-parented to the link row and drawn from its composite,
below fresh ink; the underline is drawn live. Edit link, Unlink, move and delete are undoable.
A finger tap follows; a swipe up walks the trail back. A target that is gone explains itself
and offers Edit link or Remove: the row is never touched on its own, since a target gone today
may be back from a backup tomorrow. A walk back that meets a dead entry skips it in silence.

The payload is Notesprout SN's grammar byte for byte, `L1|<chrome>|<kind>|<itemId>|<pageId>`,
so a converted notebook's links read. The item slot may name any kind of item; what kind it is,
the library says.

## The picker

Three shelves: this notebook's pages (the one being written on left out, the numbers counting
the whole notebook), the library's items through Soil's item picker, and a notebook's pages.
Page cards show the page in miniature, named by its heading; another notebook is read through a
session of its own, closed the moment it is left. New page and New notebook are offered where
the target does not exist yet. A link never targets its own home.

## In Soil

Every file carries `soil_link`, the one Soil table an app writes, in the same batch as the link
row, through exactly the three statements the checker admits. Soil re-reads the mirror after
any batch naming it into the index's `link` table, so the index is always rebuildable from the
files. `backlinks(itemId)` answers what links into an item, from alive sources only; the page
sheet lists what links to the page and goes there.

Walked on the Nomad, phase 5, 2026-10-01.
