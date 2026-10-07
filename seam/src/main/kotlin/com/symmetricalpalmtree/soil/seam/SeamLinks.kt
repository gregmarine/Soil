package com.symmetricalpalmtree.soil.seam

/**
 * **The link mirror.** Every item file carries one table of Soil's own, `soil_link`, where the
 * app writes down each live link the item holds: the page it sits on, and the item and page it
 * points at. The app writes it **in the same batch** as the link row itself, so the mirror and
 * the link can never disagree; Soil reads the whole table back after any batch that names it,
 * and keeps the library's link index in step. The index is what answers "what links here"; the
 * mirror in each file is what the index is rebuilt from.
 *
 * It is the one `soil_` table an app may write, and only through these five statements: an
 * `INSERT` of one row pointing at an item, an `INSERT` of one row pointing into the Bible, an
 * `INSERT` of one row pointing at a day of the calendar, a `DELETE` of one link, and a `DELETE`
 * of a page's links. The statement checker admits exactly
 * those shapes ([SeamSql]); every other use of the name is refused.
 *
 * A link to a page of its own item names its own item id. A link to a whole item names no page.
 *
 * **A link into the Bible** names no item: `targetItemId` is `''` (the column is `NOT NULL` in
 * every file already, and `''` is never an id), and the three Bible columns carry the passage:
 * the whole wire, and the verse-key span of **one** of its ranges. A wire naming several ranges
 * is several rows, ids `<linkId>` and `<linkId>#<n>`, so a page of Proverbs finds a link that
 * also names John. The reader asks the index "what links into these verses" by that span.
 *
 * **A link to a day of the calendar** names no item either: `targetItemId` is `''`, the Bible
 * columns are NULL, and `calDate` carries the day as [CalAddress] spells it. The calendar asks
 * the index "what links into these days" by a range on it.
 */
object SeamLinks {

    const val TABLE = "soil_link"

    /** Soil makes the table; an app never can. */
    const val CREATE = "CREATE TABLE IF NOT EXISTS $TABLE (id TEXT PRIMARY KEY, pageId TEXT NOT NULL, targetItemId TEXT NOT NULL, targetPageId TEXT)"
    const val CREATE_INDEX = "CREATE INDEX IF NOT EXISTS soil_link_page ON $TABLE(pageId)"

    /** The Bible columns, added by Soil to a file made before them (each once, guarded by `PRAGMA table_info`). */
    const val ADD_BIBLE_WIRE = "ALTER TABLE $TABLE ADD COLUMN bibleWire TEXT"
    const val ADD_BIBLE_START = "ALTER TABLE $TABLE ADD COLUMN bibleStart INTEGER"
    const val ADD_BIBLE_END = "ALTER TABLE $TABLE ADD COLUMN bibleEnd INTEGER"
    const val CREATE_BIBLE_INDEX = "CREATE INDEX IF NOT EXISTS soil_link_bible ON $TABLE(bibleStart, bibleEnd)"

    /** The calendar column, added by Soil to a file made before it (once, guarded by `PRAGMA table_info`). */
    const val ADD_CAL_DATE = "ALTER TABLE $TABLE ADD COLUMN calDate TEXT"
    const val CREATE_CAL_INDEX = "CREATE INDEX IF NOT EXISTS soil_link_cal ON $TABLE(calDate)"

    /** Binds: `id, pageId, targetItemId, targetPageId` (the last may be null). */
    const val PUT = "INSERT INTO $TABLE (id, pageId, targetItemId, targetPageId) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT(id) DO UPDATE SET pageId = excluded.pageId, targetItemId = excluded.targetItemId, targetPageId = excluded.targetPageId, " +
        "bibleWire = NULL, bibleStart = NULL, bibleEnd = NULL, calDate = NULL"

    /** Binds: `id, pageId, bibleWire, bibleStart, bibleEnd`. One row per range of the wire. */
    const val PUT_BIBLE = "INSERT INTO $TABLE (id, pageId, targetItemId, targetPageId, bibleWire, bibleStart, bibleEnd) VALUES (?, ?, '', NULL, ?, ?, ?) " +
        "ON CONFLICT(id) DO UPDATE SET pageId = excluded.pageId, targetItemId = '', targetPageId = NULL, " +
        "bibleWire = excluded.bibleWire, bibleStart = excluded.bibleStart, bibleEnd = excluded.bibleEnd, calDate = NULL"

    /** Binds: `id, pageId, calDate` (`yyyy-MM-dd`). */
    const val PUT_CAL = "INSERT INTO $TABLE (id, pageId, targetItemId, targetPageId, calDate) VALUES (?, ?, '', NULL, ?) " +
        "ON CONFLICT(id) DO UPDATE SET pageId = excluded.pageId, targetItemId = '', targetPageId = NULL, " +
        "bibleWire = NULL, bibleStart = NULL, bibleEnd = NULL, calDate = excluded.calDate"

    /** Binds: `id`. */
    const val DROP = "DELETE FROM $TABLE WHERE id = ?"

    /** Binds: `pageId`. */
    const val DROP_PAGE = "DELETE FROM $TABLE WHERE pageId = ?"

    /** What Soil reads back after a batch that wrote the mirror. */
    const val READ = "SELECT id, pageId, targetItemId, targetPageId, bibleWire, bibleStart, bibleEnd, calDate FROM $TABLE"

    /** The id of range [n] of a Bible link: the link's own for the first, `#n` after it. */
    fun rangeId(linkId: String, n: Int): String = if (n == 0) linkId else "$linkId#$n"
}
