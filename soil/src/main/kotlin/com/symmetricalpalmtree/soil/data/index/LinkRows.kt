package com.symmetricalpalmtree.soil.data.index

import com.symmetricalpalmtree.soil.bibleref.ReferenceCodec
import com.symmetricalpalmtree.soil.paper.store.Row
import com.symmetricalpalmtree.soil.seam.BibleAddress
import com.symmetricalpalmtree.soil.seam.CalAddress

/**
 * **A file's mirror rows, read and judged.** A file is untrusted input: what its `soil_link`
 * says is indexed only when it is a row this build can read. A row pointing at an item needs an
 * item id and nothing of the Bible; a row pointing into the Bible needs no item, a wire the
 * codec decodes, and a span that is one of that wire's own ranges, so a row cannot claim a
 * passage its wire does not name; a row pointing at a day of the calendar needs no item, nothing
 * of the Bible, and a day [CalAddress] reads. Pure, so the rule is JVM-tested.
 */
object LinkRows {

    /** A mirror row as read; the Bible columns are null in a file made before them. */
    fun of(row: Row): LinkRow = LinkRow(
        id = row.text("id"),
        pageId = row.text("pageId"),
        targetItemId = row.text("targetItemId"),
        targetPageId = row.textOrNull("targetPageId"),
        bibleWire = row.textOrNull("bibleWire"),
        bibleStart = row.longOrNull("bibleStart")?.toInt(),
        bibleEnd = row.longOrNull("bibleEnd")?.toInt(),
        calDate = row.textOrNull("calDate"),
    )

    /** The rows the index takes: every one [sound]. */
    fun indexable(rows: List<LinkRow>): List<LinkRow> = rows.filter(::sound)

    fun sound(row: LinkRow): Boolean {
        if (row.id.isEmpty()) return false
        if (row.calDate != null) {
            return row.targetItemId.isEmpty() && row.targetPageId == null &&
                row.bibleWire == null && row.bibleStart == null && row.bibleEnd == null && CalAddress.isDate(row.calDate)
        }
        if (row.bibleWire == null) return row.targetItemId.isNotEmpty() && row.bibleStart == null && row.bibleEnd == null
        if (row.targetItemId.isNotEmpty() || row.targetPageId != null) return false
        val start = row.bibleStart ?: return false
        val end = row.bibleEnd ?: return false
        if (!BibleAddress.isWire(row.bibleWire)) return false
        val passages = ReferenceCodec.decode(row.bibleWire) ?: return false
        return passages.any { p -> p.ranges.any { it.startKey == start && it.endKey == end } }
    }
}
