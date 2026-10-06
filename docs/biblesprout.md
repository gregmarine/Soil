# Biblesprout

The Bible reader: the third Sprout app, and the first with no items of its own. Notesprout SN's
`NSE · Bible` extension brought across whole and put in Soil's shape: the same reader, pager,
Contents, Recents, Search, cross-references, footnotes and passage view, with its state in
Soil's app store over the seam instead of a host's, found by Soil as a Sprout app, and with
Send and the pushed notes index replaced by the clipboard and Soil's own link index. The
Berean Standard Bible (public domain), bundled as `bsb.bible`, 15 MB of SQLite built by
`tools/bible/build_bible_db.py --slim` and copied once to the app's no-backup storage.

## The parts

| Module | What |
|---|---|
| `:bible-ref` | Pure Kotlin, shared by every app and Soil: the canon (66 books, aliases, chapter counts), `VerseKey`, `ReferenceParser`, `ReferenceCodec` (the wire), `ReferenceResolver`, `ReferenceText` (typed words to a wire, a wire to its label), `ReferenceScan` (references in prose), `VerseCap` |
| `:biblesprout` | The app: `BibleActivity` and its panels, `ChapterLoader`, `BibleDatabase`, `ContentInstaller`, `BibleStore` over the seam's store lease, `PassageService` |
| `:seam` | `BibleAddress` (`bible:<wire>`), `ISeamStore` and `openAppStore`, `bibleBacklinks`, `passageText` and `IBibleText`, `ACTION_OPEN_BIBLE`, `EXTRA_BIBLE_WIRE` |
| `:soil` | `ItemApps.findBible`, `FollowLinkActivity`'s Bible branch, `AppStoreLease`, `BibleTextClient`, the index's Bible columns and `LinkRebuild` |

## The reference

A reference is read by one grammar everywhere, SN's: a book by name, code or alias, with Roman
and ordinal prefixes; chapters, verses, dashes of any kind, lists that carry the chapter forward
(`John 3:14-16, 18`), several references in one line (`; Acts 1:3`). Its wire is
`USFM:c:v-c:v` per range, joined by `,`, a whole chapter as `c:0-c:999`: byte for byte SN's, so
an SN notebook's Bible links read. The chapters are bounded by the canon's own counts; whether a
verse exists only the reader knows, and it says so when a link is followed.

## Reaching it

Biblesprout's icon, and its row in Soil's side menu, open the reader where it was left. Soil
finds the app by the screen that answers `ACTION_OPEN_BIBLE`, as it finds an item app by
`ACTION_OPEN_ITEM`; the screen is guarded by the seam permission. A link into the Bible from a
notebook or a document is followed through Soil (`ACTION_FOLLOW` with `EXTRA_BIBLE_WIRE`), and
the reader opens on the passage over the app that followed it, in passage mode. Standard launch
mode on purpose: Full chapter and a cross-reference tap stack a second reader over the first,
and Back comes back.

## The store

The last-read position and the recents live in Soil's app store, `garden/app_<package>.db`,
under the global key, re-keyed and backed up with everything else. The reader declares its three
tables once (`BibleSchema`: `state`, `recent`, `recent_ref`) and opens the store with
`openAppStore`; Soil lends an `ISeamStore` minted for the app's uid, every statement checked,
no DDL through the gate, dead with the app's process. A store Soil will not lend costs the
bookmark and the recents and nothing else.

## The Notes panel

Notes, at the pager's end, lists what in the library cites the verses on screen: the chapter's
band, or in passage mode the passage's own ranges. The rows come from Soil's link index over the
seam (`bibleBacklinks`), one row per range of each link, grouped per notebook page or document
in the order the references point. A row opens its page or document through Soil over the
reader. There is no Rebuild door here: Soil's Settings has one for the whole index.

## Copy

Copy, on the top bar, puts the passage on screen, or the chapter being read as a whole chapter,
on Soil's clipboard in the `bible` slot with its verses (`clipboard.md`). The reader stays.

## The passage service

`PassageService` answers Soil alone with a passage's words as Markdown (`PassageMarkdown`: a
bold label line, then the verses as prose with plain numbers, a paragraph per chapter run), up
to a chapter. Soil relays it as `passageText`; a notebook applies the page's cap first.

## Walked on the Nomad

Phase 2 (the reader), 2026-10-05; phases 3 to 8, 2026-10-05.

## Decisions (Greg, 2026-10-05)

- The reader is SN's, as it was; only what touched SN's host is new.
- A reference in a document becomes a link on its own, after a pause in typing; a notebook's
  handwriting is converted through the lasso's Bible button, as in SN.
- The parser lives in a shared module, not behind a binder.
- Back references live in Soil's link index, not in a table pushed to the reader.
- The reader's state lives in Soil's app store over the seam.
- Copy in the reader, Paste in a notebook or a document, asked each time: the reference, or the
  verses with it. No Send.
- A removed reference link in a document is remembered with the document and never linked
  again by the pass; Relink Bible, on the selection's own menu and only for a removed reference,
  links it again and forgets the removal. The Link tool stays the tool for addresses.

Proposed by the build and standing until Greg says otherwise: the `bible:` address; the mirror's
three Bible columns with an empty item id; one mirror row per range; `ACTION_OPEN_BIBLE` for an
app with no items; the passage words relayed through Soil rather than bound by the apps; the
scanner's capital initial and the period a two-letter alias needs; the removal remembered by
the words and the wire; the Settings row that rebuilds the index.

## Traps

From SN, each guarded: `noCompress "bible"`, or the asset cannot be opened by SQLite nor stamped;
the installer's one lock, or two first opens tear the copy; `configChanges="keyboard|keyboardHidden"`
on the reader, or a keyboard attaching dismisses the Search panel; a paragraph span's end is
exclusive, or it grows into every later append; no README inside `res/font/`. New: the index's
`targetItemId` is `''` for a Bible row and any future join on it must say so; a first
`passageText` call waits for the reader's copy of its Bible.
