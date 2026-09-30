package com.symmetricalpalmtree.soil.notesprout.objects

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import kotlin.math.ceil
import kotlin.math.max

/** Where an inserted box lands: the page centre, clamped onto the page. Pure. */
object TextPlacement {
    fun centred(pageW: Float, pageH: Float, w: Float, h: Float): Pair<Float, Float> = axis(pageW, w) to axis(pageH, h)

    private fun axis(page: Float, size: Float): Float {
        if (!page.isFinite() || !size.isFinite()) return 0f
        val at = (page - size) / 2f
        return if (at > 0f) at else 0f
    }
}

/**
 * Where a dropped object lands when the centre is taken: **the nearest clear spot to the centre**,
 * searched outward in rings on a [STEP_DP] grid, "clear" meaning the box grown by [GAP_DP] meets
 * none of what is already there. A box the page cannot hold, or a page with no clear spot, lands
 * at the centre as it always did: a drop is never refused.
 */
object FreePlacement {
    const val GAP_DP = 8f
    const val STEP_DP = 16f

    fun nearCentre(pageW: Float, pageH: Float, w: Float, h: Float, occupied: List<Bounds>, density: Float): Pair<Float, Float> {
        val centre = TextPlacement.centred(pageW, pageH, w, h)
        if (occupied.isEmpty()) return centre
        if (!(pageW > 0f && pageH > 0f) || w > pageW || h > pageH || !w.isFinite() || !h.isFinite()) return centre
        val gap = GAP_DP * max(density, 0f)
        val step = (STEP_DP * max(density, 1f)).coerceAtLeast(1f)
        if (clear(centre.first, centre.second, w, h, gap, occupied)) return centre
        val (cx, cy) = centre
        val maxX = pageW - w
        val maxY = pageH - h
        val rings = ceil(max(max(cx, maxX - cx), max(cy, maxY - cy)) / step).toInt() + 1
        val tried = HashSet<Long>()
        for (r in 1..rings) {
            var best: Pair<Float, Float>? = null
            var bestD2 = Float.MAX_VALUE
            for (dy in -r..r) for (dx in -r..r) {
                if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                val x = (cx + dx * step).coerceIn(0f, maxX)
                val y = (cy + dy * step).coerceIn(0f, maxY)
                val key = (x.toBits().toLong() shl 32) or (y.toBits().toLong() and 0xFFFFFFFFL)
                if (!tried.add(key)) continue
                val ddx = x - cx
                val ddy = y - cy
                val d2 = ddx * ddx + ddy * ddy
                if (d2 >= bestD2) continue
                if (clear(x, y, w, h, gap, occupied)) { best = x to y; bestD2 = d2 }
            }
            if (best != null) return best
        }
        return centre
    }

    internal fun clear(x: Float, y: Float, w: Float, h: Float, gap: Float, occupied: List<Bounds>): Boolean {
        val box = Bounds(x - gap, y - gap, x + w + gap, y + h + gap)
        return occupied.none { it.intersects(box) }
    }
}

/**
 * The two ways a text object's source is tidied before it is stored. They are not the same rule:
 * a recognizer's line breaks are guesses ([normalize] collapses), a person's are decisions
 * ([typed] keeps the interior as it is). Both answer `""` for nothing, and a blank text object
 * never exists.
 */
object TextLines {
    private val HORIZONTAL = Regex("[^\\S\\n]+")

    fun normalize(raw: String): String {
        val lines = splitLines(raw).map { it.replace(HORIZONTAL, " ").trim() }
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out.add(line)
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out.joinToString("\n")
    }

    fun typed(raw: String): String {
        val lines = splitLines(raw).map { it.trimEnd() }
        var first = 0
        var last = lines.size - 1
        while (first <= last && lines[first].isEmpty()) first++
        while (last >= first && lines[last].isEmpty()) last--
        if (first > last) return ""
        return lines.subList(first, last + 1).joinToString("\n")
    }

    private fun splitLines(raw: String): List<String> = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
}

/** A sticky note as the Insert bar places it: a 72 dp icon at the centre, its content size fixed
 *  to this device's editor paper, which is the whole window. */
object StickyDefaults {
    fun at(id: String, pageWidth: Float, pageHeight: Float, density: Float, contentW: Int, contentH: Int): PageSticky {
        val edge = ObjectRows.STICKY_ICON_DP * density
        val x = ((pageWidth - edge) / 2f).coerceAtLeast(0f)
        val y = ((pageHeight - edge) / 2f).coerceAtLeast(0f)
        return PageSticky(id = id, x = x, y = y, width = edge, height = edge, contentW = contentW, contentH = contentH, order = 0)
    }

    fun contentSize(windowW: Int, windowH: Int): Pair<Int, Int> =
        windowW.coerceIn(1, StickyFlags.MAX) to windowH.coerceIn(1, StickyFlags.MAX)
}

/**
 * The paper a note does **not** own in the editor's full-bleed view: the band below its page, and
 * the band to its right when it is narrower. g-paper leaves everything beyond the page white and
 * writable, so these bands are excluded from ink. The two never overlap. Pure; [Band] rather than
 * a `Rect`, which is a stub on the JVM.
 */
object StickyPageRects {
    data class Band(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun offPage(pageW: Int, pageH: Int, viewW: Int, viewH: Int): List<Band> {
        if (viewW <= 0 || viewH <= 0) return emptyList()
        val bands = ArrayList<Band>(2)
        if (pageH < viewH) bands += Band(0, pageH, viewW, viewH)
        if (pageW < viewW) bands += Band(pageW, 0, viewW, pageH)
        return bands
    }
}

fun StickyPageRects.Band.toRect(): android.graphics.Rect = android.graphics.Rect(left, top, right, bottom)
