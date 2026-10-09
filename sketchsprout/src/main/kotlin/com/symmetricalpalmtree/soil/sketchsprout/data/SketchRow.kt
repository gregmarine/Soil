package com.symmetricalpalmtree.soil.sketchsprout.data

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Row

/**
 * One row of the sketchbook table, neutral: every content column as stored, with its identity and
 * lineage, and no clock. What the clipboard captures and what a paste writes, without this file
 * knowing what any row means.
 */
data class SketchRow(
    val id: String,
    val parentId: String,
    val type: String,
    val order: Int = 0,
    val text: String? = null,
    val refId: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
    val color: String? = null,
    val strokeWidth: Float? = null,
    val style: String? = null,
    val flags: Long? = null,
    val blob: ByteArray? = null,
) {
    /** The row as cells, in [COLUMNS]' order. */
    fun cells(): List<Cell> = listOf(
        Cell.Text(id), Cell.Text(parentId), Cell.Text(type), Cell.Integer(order.toLong()),
        Cell.of(text), Cell.of(refId), Cell.of(x?.toDouble()), Cell.of(y?.toDouble()), Cell.of(width?.toDouble()), Cell.of(height?.toDouble()),
        Cell.of(color), Cell.of(strokeWidth?.toDouble()), Cell.of(style), Cell.of(flags), Cell.of(blob),
    )

    override fun equals(other: Any?): Boolean = other is SketchRow && other.id == id && other.parentId == parentId && other.type == type &&
        other.order == order && other.text == text && other.refId == refId && other.x == x && other.y == y && other.width == width &&
        other.height == height && other.color == color && other.strokeWidth == strokeWidth && other.style == style && other.flags == flags &&
        (other.blob?.contentEquals(blob) ?: (blob == null))

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** The columns a whole-row read answers, in this order. */
        val COLUMNS: List<String> = listOf(
            "id", "parentId", "type", "order", "text", "refId", "x", "y", "width", "height", "color", "strokeWidth", "style", "flags", "blob",
        )

        /** A row of [SketchbookSql.selectRows] or a decoded clip; null for one that will not read. */
        fun fromRow(row: Row): SketchRow? = try {
            SketchRow(
                id = row.text("id"), parentId = row.text("parentId"), type = row.text("type"), order = row.long("order").toInt(),
                text = row.textOrNull("text"), refId = row.textOrNull("refId"),
                x = row.realOrNull("x")?.toFloat(), y = row.realOrNull("y")?.toFloat(),
                width = row.realOrNull("width")?.toFloat(), height = row.realOrNull("height")?.toFloat(),
                color = row.textOrNull("color"), strokeWidth = row.realOrNull("strokeWidth")?.toFloat(), style = row.textOrNull("style"),
                flags = row.longOrNull("flags"), blob = row.blobOrNull("blob"),
            )
        } catch (_: Exception) {
            null
        }
    }
}
