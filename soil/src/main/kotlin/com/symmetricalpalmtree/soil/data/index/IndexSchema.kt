package com.symmetricalpalmtree.soil.data.index

import com.symmetricalpalmtree.soil.data.Schema

/**
 * The library index, `soil.db`: one row per item in the library, and nothing of what an item
 * holds. Encrypted under the global key.
 *
 * An item is one kind of content — a notebook, a sketchbook or a document — in one `.soil` file
 * named by the row's id. Ids are stable and never reused; a delete is soft.
 *
 * Links are here since step 4: one row per link in the library, mirrored from each file's
 * `soil_link` table whenever an app writes it. Covers and the library's shape since step 6; the
 * clipboard, tags and each item's page order since step 7.
 */
object IndexSchema {

    const val KIND_NOTEBOOK = "notebook"
    const val KIND_SKETCHBOOK = "sketchbook"
    const val KIND_DOCUMENT = "document"

    /** Opens under the global key. */
    const val KEY_SCOPE_GLOBAL = "GLOBAL"

    /** Opens under a passphrase of its own, asked for when the item is opened. */
    const val KEY_SCOPE_ITEM = "ITEM"

    private val V1 = listOf(
        """CREATE TABLE item (
               id TEXT PRIMARY KEY,
               kind TEXT NOT NULL,
               name TEXT NOT NULL,
               keyScope TEXT NOT NULL DEFAULT 'GLOBAL',
               flags INTEGER NOT NULL DEFAULT 0,
               createdAt INTEGER NOT NULL,
               updatedAt INTEGER NOT NULL,
               deletedAt INTEGER);""",
        """CREATE INDEX item_kind_alive ON item(kind, deletedAt);""",
        """CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);""",
    )

    /** The page count, so the library can say it without opening a file. The app that writes
     *  the item keeps it honest through the seam. */
    private val V2 = listOf(
        """ALTER TABLE item ADD COLUMN pageCount INTEGER NOT NULL DEFAULT 0;""",
    )

    /** When the item was last opened, so the Recents can be answered without a store of their
     *  own. Null for an item never opened. */
    private val V3 = listOf(
        """ALTER TABLE item ADD COLUMN openedAt INTEGER;""",
    )

    /**
     * The link index: every link in every item, by its own id, with the item and page it sits on
     * and the item and page it points at (no page for a link to a whole item). Kept in step
     * from each file's mirror table; a source item's delete leaves its rows, and the reads
     * join the item table to leave them out.
     */
    private val V4 = listOf(
        """CREATE TABLE link (
               id TEXT PRIMARY KEY,
               sourceItemId TEXT NOT NULL,
               sourcePageId TEXT NOT NULL,
               targetItemId TEXT NOT NULL,
               targetPageId TEXT);""",
        """CREATE INDEX link_target ON link(targetItemId);""",
        """CREATE INDEX link_source ON link(sourceItemId);""",
    )

    /**
     * The paper library: templates (a picture, the original bytes, under a fit), their folders, and
     * the pins. `parentId` is `''` at the root. Blank, the Default folder and the three built-in
     * papers are sentinels, never rows. A delete is soft and the blob is cleared with it: a dead
     * row's six megabytes are nothing anyone can read again.
     */
    private val V5 = listOf(
        """CREATE TABLE template (
               id TEXT PRIMARY KEY,
               parentId TEXT NOT NULL DEFAULT '',
               name TEXT NOT NULL,
               fit INTEGER NOT NULL DEFAULT 0,
               createdAt INTEGER NOT NULL,
               updatedAt INTEGER NOT NULL,
               deletedAt INTEGER,
               blob BLOB);""",
        """CREATE INDEX template_parent ON template(parentId, deletedAt);""",
        """CREATE TABLE template_folder (
               id TEXT PRIMARY KEY,
               parentId TEXT NOT NULL DEFAULT '',
               name TEXT NOT NULL,
               createdAt INTEGER NOT NULL,
               updatedAt INTEGER NOT NULL,
               deletedAt INTEGER);""",
        """CREATE INDEX template_folder_parent ON template_folder(parentId, deletedAt);""",
        """CREATE TABLE template_pin (id TEXT PRIMARY KEY, pinnedAt INTEGER NOT NULL);""",
    )

    /**
     * The library's shape: folders, an item's folder (`''` at the root), each item's cover (a
     * small picture of its last-shown page, written by its app on close), the pinned items, and
     * what a folder says of what is made inside it: a naming scheme and a default template. The
     * root's own say is the `folder_prefs` row whose `folderId` is `''`.
     */
    private val V6 = listOf(
        """ALTER TABLE item ADD COLUMN parentId TEXT NOT NULL DEFAULT '';""",
        """ALTER TABLE item ADD COLUMN cover BLOB;""",
        """CREATE INDEX item_parent ON item(parentId, deletedAt);""",
        """CREATE TABLE folder (
               id TEXT PRIMARY KEY,
               parentId TEXT NOT NULL DEFAULT '',
               name TEXT NOT NULL,
               createdAt INTEGER NOT NULL,
               updatedAt INTEGER NOT NULL,
               deletedAt INTEGER);""",
        """CREATE INDEX folder_parent ON folder(parentId, deletedAt);""",
        """CREATE TABLE item_pin (id TEXT PRIMARY KEY, pinnedAt INTEGER NOT NULL);""",
        """CREATE TABLE folder_prefs (folderId TEXT PRIMARY KEY, scheme TEXT, template TEXT);""",
    )

    /**
     * Three things the apps need the library to hold for them:
     *
     *  - the **clipboard**: one slot per kind of item, the app's own bytes under a header Soil can
     *    answer blob-free, so a copy outlives the app and travels between items;
     *  - **tags**: a tag is one row under a locale-neutral identity (`identityKey`, unique), and
     *    an assignment names an item and, for a page tag, the page (`''` for the item itself, and
     *    in the key, since `NULL` is not equal to `NULL`); the cascade is done by hand;
     *  - each item's **pages in order**, told by its app, so the library can say "Page 3" of a
     *    file it never opens.
     */
    private val V7 = listOf(
        """CREATE TABLE clipboard (
               kind TEXT PRIMARY KEY,
               payloadKind TEXT NOT NULL,
               sourceItemId TEXT NOT NULL,
               copiedAt INTEGER NOT NULL,
               blob BLOB NOT NULL);""",
        """CREATE TABLE tag (
               id TEXT PRIMARY KEY,
               display TEXT NOT NULL,
               identityKey TEXT NOT NULL UNIQUE,
               createdAt INTEGER NOT NULL);""",
        """CREATE TABLE tag_assignment (
               tagId TEXT NOT NULL,
               itemId TEXT NOT NULL,
               pageId TEXT NOT NULL DEFAULT '',
               createdAt INTEGER NOT NULL,
               PRIMARY KEY (tagId, itemId, pageId));""",
        """CREATE INDEX tag_assignment_target ON tag_assignment(itemId, pageId);""",
        """CREATE TABLE item_page (
               itemId TEXT NOT NULL,
               pageId TEXT NOT NULL,
               position INTEGER NOT NULL,
               PRIMARY KEY (itemId, pageId));""",
        """CREATE INDEX item_page_item ON item_page(itemId, position);""",
    )

    /** Export presets: a name over which exporter and which option values, as JSON. */
    private val V8 = listOf(
        """CREATE TABLE export_preset (
               id TEXT PRIMARY KEY,
               name TEXT NOT NULL,
               json TEXT NOT NULL,
               createdAt INTEGER NOT NULL,
               updatedAt INTEGER NOT NULL);""",
    )

    /** Export presets were set aside before they shipped (2026-10-03, `BACKLOG.md`); the table goes. */
    private val V9 = listOf("DROP TABLE IF EXISTS export_preset;")

    val SCHEMA = Schema("index", listOf(V1, V2, V3, V4, V5, V6, V7, V8, V9))
}
