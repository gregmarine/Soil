# The seam

The interface between Soil and the Sprout apps: one bound service in Soil, `SoilSeamService`,
answering `ISoilSeam`; one binder per open item, `ISeamItem`; and one callback the app hands
Soil while its paper is in front, `ISeamClient`. `:seam` holds the AIDL, the parcelables, the
limits and the statement checker; `:seam-kit` holds what an app uses to speak it (the
connection, the row codec, the row store).

## The rules

- An app never touches a file. It asks Soil for rows and hands rows back.
- An app never sees a key, and never asks for one. When the library is locked, the person
  unlocks it in Soil, and every storage call is refused until the library is open.
- Trust rests on one signing key. There is no per-app permission model.
- Every statement is checked on both sides by `SeamSql`: one statement, an allowed head keyword,
  no `ATTACH`, `PRAGMA`, `VACUUM`, DDL or transaction words, no identifier in a reserved space
  (`soil_*`, `sqlite_*`, `pragma_*`, `sqlcipher_*`), positional binds that match the arguments,
  values under `SeamLimits`. The one exception is the link mirror, below.
- Large data crosses whole in shared memory (`SeamBytes`), never in chunks.
- Only `SecurityException`, `IllegalArgumentException` and `IllegalStateException` cross. A
  failure of any other kind becomes an `IllegalStateException` naming its class and nothing else.

## The calls

### The handshake and the client

| Call | Meaning |
|---|---|
| `hello()` | `seamVersion`, `libraryUnlocked`, `libraryOpen` (unlocked, the recovery key saved, no rotation unfinished) |
| `attachClient(client)` / `detachClient(client)` | The app's paper screen is in front; Soil may ask it `penActive`, `releasePanel` (the side menu is about to draw over it) and `releaseForHandoff` (the Scratch Pad is about to open over it). One client at a time; a dead client is detached by its binder's death |
| `penActive()` / `releasePanel()` / `releaseForHandoff()` | The same questions, asked of Soil's own paper by an app |
| `barKey(keyCode, action, eventTime, repeatCount)` | A side-bar key the app's window received. Soil's key filter is off while paper is in front, so this is how a swipe reaches the menu there (`shell.md`) |

### Items

| Call | Meaning |
|---|---|
| `createItem(name, schema)` | A new item of the schema's kind, under the global key, empty |
| `listItems(kind)`, `recentItems(kind, limit)`, `item(id)` | The index's rows, blob-free |
| `renameItem`, `deleteItem`, `setPageCount`, `setCover(bytes)`, `setPages(ids)` | What the library shows without opening a file: the name, the count, the cover, the page order |
| `openItem(id, schema, owner)` | An `ISeamItem`: the app's hold on the file, bound to its uid and to `owner`'s death |
| `openAppStore(schema, owner)` | An `ISeamStore`: the app's own store in Soil (`garden/app_<package>.db`, under the global key), for an app with no items (Biblesprout); made on first use at the schema's steps, bound to the caller's uid and to `owner`'s death, closed by `close()` (`biblesprout.md`) |
| `makeItemFromFile(besideItemId, name, fileExtension, bytes)` | A new item beside another, made from a file's bytes by the app that imports that extension: the maker an import of a picked file uses. A notebook's Convert makes its document this way (`docsprout.md`) |

An open item answers `exec(batch)` (N statements, one transaction, each checked), `query(one)`,
`park()` (the file is checkpointed and closed while every holder is parked; the session stays),
`resume()` and `close(tidy)`. Soil holds one connection per file however many sessions hold it.
`SeamSchema` carries the app's tables as ordered steps and its purge statements; Soil runs the
missing steps on open, refuses a file newer than the schema, and runs the purge itself when a
file is closed for good.

Every item file carries two tables of Soil's own: `soil_meta` (what the file is) and
`soil_link` (the link mirror). An app writes `soil_link` in the same batch as its link row,
through exactly four admitted statements (a row to an item, a row into the Bible, a drop, a
page's drop); Soil re-reads the mirror after any batch naming it and keeps the index's link
table in step. `backlinks(itemId)` answers what links into an item, `bibleBacklinks(start, end)`
what links into a span of verses (`links.md`).

### The library's services

| Call | Meaning |
|---|---|
| `template(id)`, `templateImage(id)`, `templateUsed(cardId)`, `stageTemplate(image)` | The paper library (`templates.md`) |
| `putClip(kind, header, payload)`, `clip(kind)`, `clipHeader(kind)`, `clearClip(kind)` | The clipboard, one slot per kind, the header answered without the bytes (`clipboard.md`) |
| `assignTag(itemId, pageId, text)`, `stageText(text)` | Tags (`tags.md`) |
| `sendInkToPad(ink, placement)` | A notebook's ink parked for the Scratch Pad (`clipboard.md`). The way back is the clipboard: `takeIncomingInk` is gone |
| `recognizerStatus()`, `prepareRecognizer()`, `recognizeInk(...)`, `recognizePage(...)` | Recognition relayed to the recogniser chosen in Settings (`extensions.md`) |
| `passageText(wire)` | A passage's words as Markdown, relayed to Biblesprout's `IBibleText` service, one bind per call (`biblesprout.md`). Refused with `Seam.BIBLE_NO_APP`, `BIBLE_TOO_LONG`, `BIBLE_UNREADABLE` or `BIBLE_FAILED` |

### Screens an app starts, and the one Soil starts

An app starts Soil's screens for a result with the actions in `Seam`: `ACTION_PICK_TEMPLATE`,
`ACTION_SAVE_TEMPLATE`, `ACTION_PICK_ITEM`, `ACTION_TAGS`, `ACTION_SCRATCH_PAD`, `ACTION_EXPORT`,
`ACTION_FOLLOW`. Each is guarded by the seam permission and carries ids only, never a path or a
key. `ACTION_PICK_ITEM` with `EXTRA_PICK_PAGE` also asks for a page when a notebook is picked,
and answers the item's name with its id. `ACTION_FOLLOW` opens an item, at a page or whole, in
the app for its kind, whichever app asks: how a notebook opens a document and a document a
notebook (`links.md`); with `EXTRA_BIBLE_WIRE` in place of an item it opens the Bible's reader
on a passage. `SoilAddress` is a link to an item as text, `soil:<item>[/<page>]`;
`BibleAddress` a link into the Bible, `bible:<wire>`, checked by its character set alone.

Soil starts the app's: the activity answering `ACTION_OPEN_ITEM` with `META_KIND` naming the
kind, with `EXTRA_ITEM_ID` (or `EXTRA_NEW_NAME` for a notebook to make) and `EXTRA_PAGE_ID`;
or, for the one app with no items, the activity answering `ACTION_OPEN_BIBLE`, with
`EXTRA_BIBLE_WIRE` or nothing. An app that answers either is a Sprout app, listed in the side
menu.
A document is made by Soil and opened by its id. For export and import, Soil binds the app's
`ACTION_RENDER` service, answering `IItemRenderer` (`export.md`).

## The version

`Seam.VERSION` is 1. It does not change during development. It is frozen at the first release
build that is actually put to use, and goes up from there.

## The two guards

| Guard | Where | What it stops |
|---|---|---|
| A signature permission on the service | Android, at the bind | Any app not signed with Soil's key, before any of Soil's code runs |
| `SeamCallerCheck.enforce` | Soil, first thing in every call | The same, checked again at the moment of the call |

The permission is named after the install, `<package>.permission.SEAM`, so a debug Soil and a
release Soil never declare the same name. An app says which Soil it talks to when it is built,
and Soil opens an item only in an app of its own build.

## For an app

```xml
<uses-permission android:name="com.symmetricalpalmtree.soil.permission.SEAM" />
<queries>
    <package android:name="com.symmetricalpalmtree.soil" />
</queries>
```

Depend on `:seam` and `:seam-kit`. `SeamConnection` binds and waits; `SeamRowStore` is a
`RowStore` over an open item, which is what `:paper`'s ink writes to, every statement checked
in the app before it is sent; `SeamStoreRows` is the same over an app store. Install Soil
first: it declares the permission. Notesprout is the worked example with paper
(`notesprout.md`), Docsprout the one without: it attaches no client (`docsprout.md`),
Biblesprout the one with no items (`biblesprout.md`).

## Walked on the Nomad

The handshake and the stranger, 2026-09-28: signed with Soil's key, answered; signed with
another key, refused at the bind (`:seam-stranger`). Every call above was walked through
Notesprout's phases, 2026-09-30 to 2026-10-03, what Docsprout added through its own,
2026-10-04, and what Biblesprout added through its own, 2026-10-05.
