package com.symmetricalpalmtree.soil.paper.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.core.StrokeCodec
import com.symmetricalpalmtree.soil.paper.store.Row

/**
 * One `stroke` row → one stroke — pure, JVM-tested.
 *
 * **A bad row is a dropped stroke, never a lost page.** A row says exactly one stroke, so a
 * malformed geometry blob, a cell of the wrong storage class or a stroke with no points is skipped
 * and counted, and the page loads with everything else on it. An unknown style name reads as `PEN`.
 *
 * The row shape is [InkSql]'s: `id, "order", color, width, style, blob`, the blob being
 * [StrokeBlob]'s format B.
 */
object StrokeRows {

    /** Columns: `id, "order", color, width, style, blob`. Null = drop this row. */
    fun decode(row: Row): Pair<Long, Stroke>? = try {
        val id = row.text("id")
        val order = row.long("order")
        val color = row.long("color").toInt()
        val width = row.real("width").toFloat()
        val style = styleOf(row.text("style"))
        val points = StrokeCodec.decode(row.blob("blob"))
        if (points.size == 0) {
            null
        } else {
            val list = ArrayList<StrokePoint>(points.size)
            for (i in 0 until points.size) {
                list += StrokePoint(
                    points.x[i],
                    points.y[i],
                    points.pressure?.get(i) ?: 1f,
                    points.tilt?.get(i) ?: 0f,
                    0L,
                )
            }
            order to Stroke(id = id, points = list, color = color, width = width, style = style)
        }
    } catch (e: Exception) {
        null
    }

    /** A style this build does not know reads as `PEN`, so ink written by a later build still shows. */
    fun styleOf(name: String): StrokeStyle =
        StrokeStyle.values().firstOrNull { it.name == name } ?: StrokeStyle.PEN
}

/** A stroke's points as `StrokeCodec` format B — what every consumer's `putStroke` binds as the
 *  row's `blob`. */
object StrokeBlob {
    fun encode(stroke: Stroke): ByteArray {
        val n = stroke.points.size
        val x = FloatArray(n)
        val y = FloatArray(n)
        val p = FloatArray(n)
        val t = FloatArray(n)
        for (i in 0 until n) {
            val pt = stroke.points[i]
            x[i] = pt.x; y[i] = pt.y; p[i] = pt.pressure; t[i] = pt.tilt
        }
        return StrokeCodec.encode(x, y, p, t)
    }
}
