package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.core.InkColorCodec
import com.symmetricalpalmtree.soil.paper.core.StrokeCodec
import com.symmetricalpalmtree.soil.paper.ink.StrokeRows
import com.symmetricalpalmtree.soil.paper.store.Row

/**
 * One `stroke` row of the notebook table to one stroke. Pure.
 *
 * **A bad row is a dropped stroke, never a lost page.** A malformed blob, a cell of the wrong
 * class or a stroke with no points is skipped and counted, and the page loads with everything
 * else on it. An unknown style name reads as `PEN`, so ink from a later build still shows.
 *
 * The row shape is [NotebookSql.selectStrokes]'s: `id, "order", color, strokeWidth, style, blob`.
 */
object NotebookStrokeRows {

    fun decode(row: Row): Pair<Long, Stroke>? = try {
        val id = row.text("id")
        val order = row.long("order")
        val color = InkColorCodec.decode(row.textOrNull("color"))
        val width = row.realOrNull("strokeWidth")?.toFloat() ?: Stroke.DEFAULT_WIDTH
        val style = StrokeRows.styleOf(row.textOrNull("style").orEmpty())
        val points = StrokeCodec.decode(row.blob("blob"))
        if (points.size == 0) {
            null
        } else {
            val list = ArrayList<StrokePoint>(points.size)
            for (i in 0 until points.size) {
                list += StrokePoint(points.x[i], points.y[i], points.pressure?.get(i) ?: 1f, points.tilt?.get(i) ?: 0f, 0L)
            }
            order to Stroke(id = id, points = list, color = color, width = width, style = style)
        }
    } catch (_: Exception) {
        null
    }
}
