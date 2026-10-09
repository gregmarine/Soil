# Links and backlinks

A lasso selection wraps into a link that points at a page of this notebook, at another item,
or at a page of one. Page level on both ends; a link to a whole item is a link with no page.
A document links too, and is linked to: always as a whole, since it has no pages. A sketchbook
and its pages are linked to, from a notebook and a document, and link out nowhere for now
(Sketchsprout, 2026-10-07; `BACKLOG.md`). A notebook
and a document both link into the Bible, to a passage, and the Bible's reader lists what links
into the verses on its screen.

## In the notebook

The wrapped ink and objects are re-parented to the link row and drawn from its composite,
below fresh ink; the underline is drawn live. Edit link, Unlink, move and delete are undoable.
A finger tap follows; a swipe up walks the trail back. A target that is gone explains itself
and offers Edit link or Remove: the row is never touched on its own, since a target gone today
may be back from a backup tomorrow. A walk back that meets a dead entry skips it in silence.

The payload is Notesprout SN's grammar byte for byte, `L1|<chrome>|<kind>|<itemId>|<pageId>`,
so a converted notebook's links read. The item slot may name any kind of item; what kind it is,
the library says. A link to an item of another kind (a document, a sketchbook), or to a page of
one, and a backlink from one, is followed by asking Soil, with `Seam.ACTION_FOLLOW` and the page
when there is one, to open it in the app for its kind, over the notebook.

SN's kinds 3 and 4 read too: a **Bible reference** (`L1|1|3|<wire>|`, the wire in the item
slot) and the **verses** placed under one (kind 4). A reference on the page is a text of the
user's own words wrapped in such a link: the lasso bar's Bible on ink recognises it as one line
and offers it in the reference dialog to correct, the Insert bar's Bible reference opens the
dialog empty, Edit link on one opens the dialog on its words; words that are not a reference are
refused with the words kept, and nothing is written. The dialog's "Insert the verses", and
Verses on a placed reference, put the passage's words in the verses column (`VersePlacement`:
a tenth in from the left, the nearest clear band, refused with an alert when the page has no
room) under a kind-4 link whose Edit is the text dialog; the cap is ten verses and never a
chapter (`VerseCap`). A tap on either hands the wire to Soil, which opens the reader.

## The picker

Four shelves: this notebook's pages (the one being written on left out, the numbers counting
the whole notebook), the library's items through Soil's item picker (a notebook, a sketchbook
or a document), a notebook's or a sketchbook's page ("Notebook or sketchbook page": Soil's
picker lists both kinds, and for a sketchbook asks its app to name the page and answers at once,
the link complete), and a calendar day on the shared day picker (kind 5, `L1|1|5|<day>|`,
the day in the item slot; Calsprout, 2026-10-06).
A notebook's page cards show the page in miniature, named by its heading; another notebook is
read through a session of its own, closed the moment it is left. A sketchbook's page is chosen
by name; previews for every kind, in one picker, are in `BACKLOG.md`. New page and New notebook are offered where
the target does not exist yet. A link never targets its own home.

## In a document

The Link tool takes a typed address, **Choose a day** (the shared day picker; the address
`cal:<day>`, the words the day's own when nothing is selected), or **Choose from library**: Soil's item picker, which for
a notebook goes on to ask for a page. The link is stored in the Markdown as an ordinary link
whose address is a `soil:` address (`SoilAddress`: `soil:<item>` or `soil:<item>/<page>`), so
the text stays plain Markdown. `DocumentLinks` mirrors every such address to `soil_link` in the
same batch as the words, under the page `""`, which is the document itself.

A **Bible reference links on its own** (Greg, 2026-10-05). About 1.5 s after typing stops the
changed lines are read for references (`ReferenceScan`, SN's whole grammar, chapter-only forms
included; a book with a capital, a two-letter alias with its period), and each becomes a link
whose address is `bible:<wire>` under the words as typed; the whole document is read on open,
after an import or a paste, and on a change of surface. In the rendered document the link is a
span over the words, no character moves and one undo takes it off; in the source it is
characters. Left alone: words already in a link, code or a raw block, and a reference the caret
is touching, read again once the caret has moved. The Link tool also takes a typed reference.
**Remove** on a Bible link is remembered with the document (a `bible_unlinked` row: the words
folded, and the wire), and the pass never puts it back; **Relink Bible** on the selection's own
menu (beside Cut, Copy and Paste), there only when the selection touches a removed reference,
links it again in one tap and forgets the removal. The Link tool is for addresses and the
library and never makes a Bible link from plain words. Such a link is mirrored one row per range
of its wire (`PUT_BIBLE`).

In the rendered document a tap on a link follows it at once; a long press offers Open, Edit
link and Remove link. A web address goes to the device's browser. Another document takes this
one's place, with a trail and a Back button (`DocTrail`); a notebook opens in Notesprout at the
page. A button lists what links to the document, only when something does, and goes there.

## In Soil

`FollowLinkActivity` answers `ACTION_FOLLOW`: it looks the item up and starts the app for its
kind, at the page named (a sketchbook opens on it), or says what is gone; handed a wire instead (`EXTRA_BIBLE_WIRE`), it starts the Bible's
reader on the passage (`ItemApps.openBible`); handed a day (`EXTRA_CAL_DATE`), the calendar on
that Day page (`ItemApps.openCalendar`), in the caller's task so Back comes back.


Every file carries `soil_link`, the one Soil table an app writes, in the same batch as the link
row, through exactly the five statements the checker admits. Soil re-reads the mirror after
any batch naming it into the index's `link` table, so the index is always rebuildable from the
files. `backlinks(itemId)` answers what links into an item, from alive sources only; the page
sheet lists what links to the page and goes there.

**A link into the Bible** has no target item: `targetItemId` is `''` (the column is `NOT NULL`
in every file; `''` is never an id) and three columns, `bibleWire`, `bibleStart`, `bibleEnd`,
carry the whole wire and the verse-key span of one of its ranges, one row per range (ids
`<linkId>` and `<linkId>#<n>`), so a page of Proverbs finds a link that also names John. Soil
adds the columns to a file made before them on its next open, and to the index at step 10. A
row is indexed only when `LinkRows` finds it sound: an item id and no span, or no item, a wire
the codec reads and a span that is one of the wire's own ranges. `bibleBacklinks(start, end)`
answers by overlap, with the source's name, kind and page number, for the reader's Notes
panel (`biblesprout.md`). **A link to a day of the calendar** has no target item either and one
column, `calDate`, the day as `yyyy-MM-dd` (`PUT_CAL`; added to a file on open and to the index
at step 11, indexed); a row is sound with no item, nothing of the Bible and a day `CalAddress`
reads. `calBacklinks(from, to)` answers by range, for the calendar's Notes door
(`calsprout.md`). `LinkRebuild` re-reads every alive item's mirror, through its open
session or from its file, after a restore and from the Settings row **Links**.

Walked on the Nomad, phase 5, 2026-10-01; documents on both ends, Docsprout's phase 9,
2026-10-04; the Bible on both ends, Biblesprout's phases 3 to 8, 2026-10-05; a day on both
ends, Calsprout's phases 8 and 9, 2026-10-06; a sketch page as a target, Sketchsprout's phase 9,
2026-10-07 and 2026-10-08. A link to a heading inside a document, and a link out of a sketch
page, are in `BACKLOG.md`.
