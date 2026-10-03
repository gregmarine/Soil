package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.core.InkColorCodec
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Row

/**
 * One row of the notebook table, neutral: every content column as stored, with its identity and
 * lineage, and no clock. What the clipboard captures and what a paste writes, without this file
 * knowing what any row means; the typed readers ([ObjectRows], [LinkRows], [NotebookStrokeRows])
 * read one through [toRow].
 */
data class NotebookRow(
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
    /** The row as the typed readers take it: the columns of [NotebookSql.selectRows]. */
    fun toRow(): Row = Row(
        COLUMNS,
        listOf(
            Cell.Text(id), Cell.Text(parentId), Cell.Text(type), Cell.Integer(order.toLong()),
            Cell.of(text), Cell.of(refId), Cell.of(x?.toDouble()), Cell.of(y?.toDouble()), Cell.of(width?.toDouble()), Cell.of(height?.toDouble()),
            Cell.of(color), Cell.of(strokeWidth?.toDouble()), Cell.of(style), Cell.of(flags), Cell.of(blob),
        ),
    )

    override fun equals(other: Any?): Boolean = other is NotebookRow && other.id == id && other.parentId == parentId && other.type == type &&
        other.order == order && other.text == text && other.refId == refId && other.x == x && other.y == y && other.width == width &&
        other.height == height && other.color == color && other.strokeWidth == strokeWidth && other.style == style && other.flags == flags &&
        (other.blob?.contentEquals(blob) ?: (blob == null))

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** The columns a whole-row read answers, in this order. */
        val COLUMNS: List<String> = listOf(
            "id", "parentId", "type", "order", "text", "refId", "x", "y", "width", "height", "color", "strokeWidth", "style", "flags", "blob",
        )

        /** A row of [NotebookSql.selectRows]; null for one that will not read. */
        fun fromRow(row: Row): NotebookRow? = try {
            NotebookRow(
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

        /** A stroke as its row: the columns [NotebookSql.putStroke] writes. */
        fun ofStroke(parentId: String, order: Int, stroke: Stroke): NotebookRow = NotebookRow(
            id = stroke.id, parentId = parentId, type = NotebookSchema.TYPE_STROKE, order = order,
            color = InkColorCodec.encode(stroke.color), strokeWidth = stroke.width, style = stroke.style.name, blob = StrokeBlob.encode(stroke),
        )
    }
}
