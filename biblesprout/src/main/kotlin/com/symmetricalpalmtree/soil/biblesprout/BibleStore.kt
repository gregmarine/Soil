package com.symmetricalpalmtree.soil.biblesprout

import com.symmetricalpalmtree.soil.bibleref.*
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement

/** The reader cannot reach its store: any store exception, treated as one. */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/**
 * The reader's per-device state, in its app store over the seam ([BibleSchema]): the last-read
 * position and the recents. **Blocking** — every call runs on `Dispatchers.IO`, never Main.
 * Biblesprout writes nothing to disk itself: the store is Soil's, lent for the showing.
 *
 * Every exception becomes [StoreUnavailable] via [guard]. **Neither the position nor a recent is
 * ever logged**: nothing that names where the user has read is written to a log.
 */
class BibleStore(private val rows: RowStore) {

    /** The last-read position, or null when nothing has been saved yet. */
    fun readPosition(): String? = guard {
        rows.query(Statement(BibleSql.SELECT_STATE, BibleSql.KEY_POSITION)).rows.firstOrNull()?.text("value")
    }

    /** Save the last-read position. One statement: `INSERT OR REPLACE` is safe because `state`
     *  has no children for a replacement to cascade away. */
    fun writePosition(value: String) = guard {
        rows.exec(listOf(Statement(BibleSql.UPSERT_STATE, BibleSql.KEY_POSITION, value)))
        Unit
    }

    /**
     * The recent chapters, newest first, at most [limit]. A row this build cannot read (an
     * unknown book code, a chapter below 1, a cell of the wrong class) is dropped, not thrown:
     * a malformed history row is never a dialog.
     */
    fun readRecents(limit: Int): List<RecentRef> = guard {
        rows.query(Statement(BibleSql.SELECT_RECENTS, limit.toLong())).rows.mapNotNull { row ->
            runCatching { RecentRef.of(row.text("usfm"), row.long("chapter").toInt(), row.long("at")) }.getOrNull()
        }
    }

    /** Record a pick, stamped [at], and trim the table to its newest [keep] rows: one two-statement
     *  batch, so the trim can never run against a store the upsert did not reach. */
    fun writeRecent(ref: ChapterRef, at: Long, keep: Int) = guard {
        rows.exec(
            listOf(
                Statement(BibleSql.UPSERT_RECENT, ref.usfm, ref.chapter.toLong(), at),
                Statement(BibleSql.TRIM_RECENTS, keep.toLong()),
            ),
        )
        Unit
    }

    /**
     * The recent references, newest first, at most [limit]. A row whose wire this build cannot
     * decode is dropped, not thrown. Decoded here, on IO, so a panel row names itself without
     * parsing on Main.
     */
    fun readRecentRefs(limit: Int): List<RecentEntry.Reference> = guard {
        rows.query(Statement(BibleSql.SELECT_RECENT_REFS, limit.toLong())).rows.mapNotNull { row ->
            runCatching {
                val wire = row.text("ref")
                val passages = ReferenceCodec.decode(wire) ?: return@runCatching null
                RecentEntry.Reference(wire, passages, row.long("at"))
            }.getOrNull()
        }
    }

    /** Record a passage opened, stamped [at], and trim to the newest [keep]: [writeRecent]'s
     *  shape. The wire is the key, so re-opening the same reference re-stamps its row. */
    fun writeRecentRef(wire: String, at: Long, keep: Int) = guard {
        rows.exec(
            listOf(
                Statement(BibleSql.UPSERT_RECENT_REF, wire, at),
                Statement(BibleSql.TRIM_RECENT_REFS, keep.toLong()),
            ),
        )
        Unit
    }

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: StoreUnavailable) {
            throw e
        } catch (e: Exception) {
            throw StoreUnavailable(e)
        }
}
