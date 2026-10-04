# Items and the library

An item is one kind of content, a notebook, a sketchbook or a document, in one `.soil` file in
the garden, named by its id, under the global key. The library is the index's view of them.

## The files

- `SoilFiles` is the one path authority: `soil.db` the index, `garden/<id>.soil` the items,
  `garden/<name>.db` the app stores. The garden is flat; structure lives in the index.
- `ItemFiles` makes, opens and closes an item file. A file is made once and never made by an
  open: a missing file is a missing item. An open checks the file's own `soil_meta` first (its
  id, its kind, its format), so a file is never taken for another item. A close checkpoints and
  releases the claim. `tidy` runs the app's purge and, when something went, `VACUUM`.
- `ItemSessions` and `SessionBook`: one connection per file however many app sessions hold it,
  and none while every holder is parked. A held item cannot be deleted or re-keyed; a parked one
  stands aside for a rotation. The seam's `SeamItemSession` is one app's hold.
- `ItemApps`: which app opens which kind, found each time from the activity that answers
  `ACTION_OPEN_ITEM` and names its kind, signed with Soil's key, of Soil's build.

## The index

`soil.db`, encrypted from the first byte, opened through the derive-once raw-key cache. Its
steps, each a version:

| Step | What it added |
|---|---|
| 1 | `item` (id, kind, name, keyScope, flags, createdAt, updatedAt, deletedAt) and `meta` |
| 2 | `pageCount`, kept honest by the app through the seam |
| 3 | `openedAt`, for the Recents |
| 4 | `link`: every link in the library, mirrored from each file's `soil_link` |
| 5 | `template`, `template_folder`, `template_pin`: the paper library |
| 6 | `folder`, an item's `parentId` and cover, `item_pin`, `folder_prefs` (scheme, default template) |
| 7 | `clipboard`, `tag`, `tag_assignment`, `item_page` |
| 8, 9 | Export presets, made and then dropped (`BACKLOG.md`) |

`meta` also holds the backup config under the key `backup` (`backup.md`). Ids are stable and
never reused; a delete is soft, and the file goes with it.

## The library

Soil's home opens on it: folders and items as cover cards, three across, a page at a time,
never scrolled. Each card wears a glyph for its kind, and a notebook writes its cover through
the seam when it is put down.

- **The path line**: breadcrumb and back on the left; Search, Recents and Pinned, the three
  shelves, and Sort on the right. A shelf is a glance across the tree, not a place.
- **The top bar**: New notebook and New folder.
- **The bottom bar**: Backup and Import on the left, the pager centred.
- **The long-press sheet** of an item: Pin, Rename, Move, Tags, Export, Exclude from backup,
  Delete. Of a folder: Rename, Move, the naming scheme, the default template, Delete.
- **Search** ranks names and tags through one matcher, folders then items then pages; a tagged
  page is its own card.

### Naming schemes and the default template

A folder may carry a naming scheme: literal text plus tokens (`{date}`, `{time}`, `{year}`,
`{month}`, `{day}`, `{monthname}`, `{weekday}`, `{mon}`, `{n}` with a width). The scheme builder
puts one together from chips, a text field and a live preview, with no typed token. A folder may
also name the paper a new notebook starts on. New notebook takes both: the prefilled name over
the template browser, ticked to the folder's default. Soil makes the file empty with the app's
schema; the app lays the pick onto its first page.

### For the apps

`ItemPickerActivity` is the library in pick shape, narrowed to a kind, started by an app for a
result; there is one library, and an app never browses on its own. `FolderPickerActivity`
serves both hierarchies, the library's and the paper library's.

Walked on the Nomad through Notesprout's phases 1 and 7, 2026-09-30 and 2026-10-01.
