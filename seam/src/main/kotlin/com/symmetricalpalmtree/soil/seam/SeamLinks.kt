package com.symmetricalpalmtree.soil.seam

/**
 * **The link mirror.** Every item file carries one table of Soil's own, `soil_link`, where the
 * app writes down each live link the item holds: the page it sits on, and the item and page it
 * points at. The app writes it **in the same batch** as the link row itself, so the mirror and
 * the link can never disagree; Soil reads the whole table back after any batch that names it,
 * and keeps the library's link index in step. The index is what answers "what links here"; the
 * mirror in each file is what the index is rebuilt from.
 *
 * It is the one `soil_` table an app may write, and only through these three statements: an
 * `INSERT` of one row, a `DELETE` of one link, and a `DELETE` of a page's links. The statement
 * checker admits exactly those shapes ([SeamSql]); every other use of the name is refused.
 *
 * A link to a page of its own item names its own item id. A link to a whole item names no page.
 */
object SeamLinks {

    const val TABLE = "soil_link"

    /** Soil makes the table; an app never can. */
    const val CREATE = "CREATE TABLE IF NOT EXISTS $TABLE (id TEXT PRIMARY KEY, pageId TEXT NOT NULL, targetItemId TEXT NOT NULL, targetPageId TEXT)"
    const val CREATE_INDEX = "CREATE INDEX IF NOT EXISTS soil_link_page ON $TABLE(pageId)"

    /** Binds: `id, pageId, targetItemId, targetPageId` (the last may be null). */
    const val PUT = "INSERT INTO $TABLE (id, pageId, targetItemId, targetPageId) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT(id) DO UPDATE SET pageId = excluded.pageId, targetItemId = excluded.targetItemId, targetPageId = excluded.targetPageId"

    /** Binds: `id`. */
    const val DROP = "DELETE FROM $TABLE WHERE id = ?"

    /** Binds: `pageId`. */
    const val DROP_PAGE = "DELETE FROM $TABLE WHERE pageId = ?"

    /** What Soil reads back after a batch that wrote the mirror. */
    const val READ = "SELECT id, pageId, targetItemId, targetPageId FROM $TABLE"
}
