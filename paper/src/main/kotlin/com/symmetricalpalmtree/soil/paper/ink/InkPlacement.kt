package com.symmetricalpalmtree.soil.paper.ink

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * Where pasted ink lands on a page that has no objects to place it among — the Scratch Pad's and
 * the calendar's paste (Greg, 2026-10-06: a paste of strokes is the lasso's on every surface).
 * Pure. The notebook's `ObjectPlacement` is the same arithmetic over its object rows.
 *
 * Three ways in, one rule out: [centredOn] a stylus tap (a tap is an aim, not a corner — the
 * notebook's tap-to-place), [centred] on the page, or [atSource] (the popup's Paste and a whole
 * page's ink keep the layout). All three clamp onto the page silently: pulled
 * back from the far edge first, then off the near one; ink larger than the page on an axis lands
 * from that edge; a non-positive page size means unknown and does not clamp on that axis. Every
 * stroke lands under a fresh id from [newId]: the clipboard's ids belong to the source.
 */
object InkPlacement {

    fun centred(strokes: List<Stroke>, pageWidth: Float, pageHeight: Float, newId: () -> String): List<Stroke> {
        val ink = strokes.filter { it.points.isNotEmpty() }
        if (ink.isEmpty()) return emptyList()
        val box = boxOf(ink)
        val dx = axis(box.left, box.width, (pageWidth - box.width) / 2f, pageWidth)
        val dy = axis(box.top, box.height, (pageHeight - box.height) / 2f, pageHeight)
        return ink.map { it.translated(dx, dy, newId()) }
    }

    fun centredOn(strokes: List<Stroke>, tapX: Float, tapY: Float, pageWidth: Float, pageHeight: Float, newId: () -> String): List<Stroke> {
        val ink = strokes.filter { it.points.isNotEmpty() }
        if (ink.isEmpty()) return emptyList()
        val box = boxOf(ink)
        val dx = axis(box.left, box.width, tapX - box.width / 2f, pageWidth)
        val dy = axis(box.top, box.height, tapY - box.height / 2f, pageHeight)
        return ink.map { it.translated(dx, dy, newId()) }
    }

    fun atSource(strokes: List<Stroke>, pageWidth: Float, pageHeight: Float, newId: () -> String): List<Stroke> {
        val ink = strokes.filter { it.points.isNotEmpty() }
        if (ink.isEmpty()) return emptyList()
        val box = boxOf(ink)
        val dx = axis(box.left, box.width, box.left, pageWidth)
        val dy = axis(box.top, box.height, box.top, pageHeight)
        return ink.map { it.translated(dx, dy, newId()) }
    }

    private fun boxOf(ink: List<Stroke>): Bounds {
        var box: Bounds = ink.first().bounds
        for (i in 1 until ink.size) box = box.union(ink[i].bounds)
        return box
    }

    /** One axis: the shift from [from] to [target], clamped onto a page of [page]. */
    private fun axis(from: Float, size: Float, target: Float, page: Float): Float {
        if (!from.isFinite() || !size.isFinite() || !target.isFinite()) return 0f
        var shift = target - from
        if (page <= 0f) return shift
        if (size > page) return -from
        val overshoot = (from + size + shift) - page
        if (overshoot > 0f) shift -= overshoot
        val undershoot = from + shift
        if (undershoot < 0f) shift -= undershoot
        return shift
    }
}
