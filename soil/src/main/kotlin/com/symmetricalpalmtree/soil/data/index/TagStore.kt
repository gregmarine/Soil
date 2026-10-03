package com.symmetricalpalmtree.soil.data.index

import android.util.Log
import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.store.Row
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.seam.TagRules
import java.util.UUID

/** One tag as stored. [identityKey] is re-derived from [display]: one function is the identity. */
data class TagRecord(val id: String, val display: String) {
    val identityKey: String = TagRules.identityKey(display)
}

/** One attachment: a tag on an item, or on a page of it ([pageId] `""` for the item itself). */
data class Assignment(val tagId: String, val itemId: String, val pageId: String) {
    val isItemTag: Boolean get() = pageId.isEmpty()
    val pageIdOrNull: String? get() = pageId.ifEmpty { null }
    fun isOn(itemId: String, pageId: String?): Boolean = this.itemId == itemId && this.pageId == (pageId ?: "")
}

/**
 * The tag index over [TagSql]. **Blocking**: IO, or a Binder thread, never Main. The
 * transaction is the lock: every write is one statement, or two in one batch, correct whoever
 * else is writing; the caps ride inside the inserts rather than being checked beforehand.
 *
 * A refusal by a cap is `IllegalStateException(TagRules.TAGS_FULL)`, compared verbatim.
 */
class TagStore(
    private val rows: RowStore = SqlCipherRowStore(SoilIndex.db()),
    private val maxTags: Int = TagRules.MAX_TAGS,
    private val maxAssignments: Int = TagRules.MAX_ASSIGNMENTS,
) {

    /** How much of the library one tag reaches: the numbers the delete confirm names. */
    class Usage(val items: Int, val pages: Int) {
        val total: Int get() = items + pages
    }

    /** What [assign] did: the tag's stored spelling, and whether anything was written. */
    class Assigned(val display: String, val changed: Boolean)

    // ── Reads ──────

    /** Every tag in the library, in browse order. */
    fun tags(): List<TagRecord> {
        var dropped = 0
        val out = rows.query(TagSql.selectTags()).rows.mapNotNull { row ->
            tag(row).also { if (it == null) dropped++ }
        }
        if (dropped > 0) Log.w(TAG, "$dropped tag row(s) would not read")
        return out
    }

    /** Every assignment on one item: its own and its pages'. */
    fun assignmentsOfItem(itemId: String): List<Assignment> = assignments(rows.query(TagSql.selectAssignmentsOfItem(itemId)).rows)

    /** The assignments of [tagIds], chunked under SQLite's bind cap. */
    fun assignmentsOf(tagIds: Collection<String>): List<Assignment> =
        tagIds.distinct().chunked(CHUNK).flatMap { chunk -> assignments(rows.query(TagSql.selectAssignmentsOf(chunk)).rows) }

    fun usageOf(tagId: String): Usage {
        val row = rows.query(TagSql.selectUsage(tagId)).rows.firstOrNull()
        return Usage((row?.longOrNull("items") ?: 0L).toInt(), (row?.longOrNull("pages") ?: 0L).toInt())
    }

    // ── Writes ──────

    /**
     * Normalise [text], make the tag if the library has never seen it, attach it to the target.
     * Idempotent: a tag already on the target writes nothing and answers `changed = false`. The
     * answer is the **stored** display, the casing of whoever entered it first.
     *
     * @throws IllegalArgumentException [text] is not a tag, or an id is not a UUID.
     * @throws IllegalStateException [TagRules.TAGS_FULL]: a cap refused it, nothing was written.
     */
    fun assign(text: String, itemId: String, pageId: String?, now: Long = System.currentTimeMillis()): Assigned {
        require(TagRules.isValid(text)) { "not a tag" }
        require(TagRules.isId(itemId)) { "not an item id" }
        require(pageId == null || TagRules.isId(pageId)) { "not a page id" }
        val display = TagRules.display(text)
        val key = TagRules.identityKey(text)
        val page = pageId ?: ""

        val before = identity(key, itemId, page)
        if (before != null && before.attached) return Assigned(before.display, changed = false)
        val batch = ArrayList<com.symmetricalpalmtree.soil.paper.store.Statement>(2)
        if (before == null) batch += TagSql.insertTag(UUID.randomUUID().toString(), display, key, now, maxTags, maxAssignments)
        batch += TagSql.insertAssignment(key, itemId, page, now, maxAssignments)
        rows.exec(batch)

        // `INSERT OR IGNORE` reports a cap that refused it as silence: the only way to know is to look.
        val after = identity(key, itemId, page)
        if (after == null || !after.attached) throw IllegalStateException(TagRules.TAGS_FULL)
        return Assigned(after.display, changed = true)
    }

    /** Detach [tagId] from one target; true when a row went. The tag itself stays. */
    fun unassign(tagId: String, itemId: String, pageId: String?): Boolean =
        rows.exec(listOf(TagSql.deleteAssignment(tagId, itemId, pageId ?: "")))[0] > 0

    /** Delete a tag and every assignment of it; true when the tag was there. */
    fun deleteTag(tagId: String): Boolean = rows.exec(TagSql.deleteTag(tagId))[1] > 0

    // ── Internals ──────

    private class Identity(val id: String, val display: String, val attached: Boolean)

    private fun identity(key: String, itemId: String, pageId: String): Identity? {
        val row = rows.query(TagSql.selectTagByIdentity(key, itemId, pageId)).rows.firstOrNull() ?: return null
        return Identity(row.text("id"), row.text("display"), row.long("attached") != 0L)
    }

    private fun assignments(from: Iterable<Row>): List<Assignment> {
        var dropped = 0
        val out = from.mapNotNull { row -> assignment(row).also { if (it == null) dropped++ } }
        if (dropped > 0) Log.w(TAG, "$dropped assignment row(s) would not read")
        return out
    }

    companion object {
        private const val TAG = "TagStore"
        private const val CHUNK = 500

        /** A bad row is a dropped record, never a lost index. Columns: `id, display`. */
        fun tag(row: Row): TagRecord? = try {
            val id = row.text("id")
            val display = row.text("display")
            if (TagRules.isId(id) && TagRules.isValid(display) && TagRules.display(display) == display) TagRecord(id, display) else null
        } catch (_: Exception) {
            null
        }

        /** Columns: `tagId, itemId, pageId`. */
        fun assignment(row: Row): Assignment? = try {
            val tagId = row.text("tagId")
            val itemId = row.text("itemId")
            val pageId = row.text("pageId")
            if (TagRules.isId(tagId) && TagRules.isId(itemId) && (pageId.isEmpty() || TagRules.isId(pageId))) Assignment(tagId, itemId, pageId) else null
        } catch (_: Exception) {
            null
        }
    }
}
