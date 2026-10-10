package com.symmetricalpalmtree.soil.sketchsprout.clip

import com.symmetricalpalmtree.soil.seamkit.RowCodec
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchRow
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookSchema

/**
 * **A sketch page on the clipboard** — the `sketchbook` slot's one payload kind: the page row,
 * its template row when it has paper, and everything alive under it (the three rasters, the two
 * guide rows), as the seam's own **binary** rows ([RowCodec.encodeRows]) rather than the
 * notebook's JSON-with-Base64 envelope, since a raster is megabytes and Base64 would cost a
 * third of the slot's cap for nothing. Pure.
 *
 * A paste re-ids every row: the page and its children are new rows, reparented; the paper is
 * **reused** when the target already holds the same paper at the page's size, else the carried
 * template row is inserted under the target. No row names the source sketchbook.
 */
object SketchPageClip {

    /** The header's payload kind for this slot. */
    const val KIND_PAGE = "page"

    /** The page as captured: the rows in insert order — the template (if any), the page, its children. */
    fun encode(page: SketchRow, template: SketchRow?, children: List<SketchRow>): ByteArray =
        RowCodec.encodeRows(SketchRow.COLUMNS, (listOfNotNull(template) + page + children).map { it.cells() })

    /** The rows a payload carries, or null for anything unusable: no page row, or bytes that will not read. */
    fun decode(bytes: ByteArray?): List<SketchRow>? {
        if (bytes == null || bytes.isEmpty()) return null
        val rows = try { RowCodec.decodeRows(bytes).rows.mapNotNull { SketchRow.fromRow(it) } } catch (_: Exception) { return null }
        if (rows.none { it.type == SketchbookSchema.TYPE_PAGE }) return null
        return rows
    }

    /** How the paste gets the page's paper: none, a row the target already holds, or the carried
     *  row inserted under a fresh id. */
    sealed interface Template {
        data object None : Template
        data class Reuse(val id: String) : Template
        data class Insert(val id: String) : Template
    }

    /** The rows to insert, in order, and the new page row — every id fresh, every child reparented. */
    class Plan(val page: SketchRow, val rows: List<SketchRow>)

    /**
     * The first page of [rows] as it will be inserted under [sketchbookId] at [order]:
     * [template] decides the paper from the carried template row (or null when the page has
     * none), [newId] mints every other id.
     */
    fun plan(rows: List<SketchRow>, sketchbookId: String, order: Int, template: (SketchRow?) -> Template, newId: () -> String): Plan? {
        val pageRow = rows.firstOrNull { it.type == SketchbookSchema.TYPE_PAGE } ?: return null
        val carried = rows.firstOrNull { it.type == SketchbookSchema.TYPE_TEMPLATE && it.id == pageRow.refId }
        val out = ArrayList<SketchRow>()
        val refId = when (val t = template(carried)) {
            Template.None -> ""
            is Template.Reuse -> t.id
            is Template.Insert -> {
                if (carried == null) "" else {
                    out += carried.copy(id = t.id, parentId = sketchbookId, order = 0)
                    t.id
                }
            }
        }
        val pageId = newId()
        val page = pageRow.copy(id = pageId, parentId = sketchbookId, order = order, refId = refId)
        out += page
        for (child in rows) {
            if (child.parentId != pageRow.id) continue
            if (child.type == SketchbookSchema.TYPE_PAGE || child.type == SketchbookSchema.TYPE_TEMPLATE) continue
            out += child.copy(id = newId(), parentId = pageId)
        }
        return Plan(page, out)
    }
}
