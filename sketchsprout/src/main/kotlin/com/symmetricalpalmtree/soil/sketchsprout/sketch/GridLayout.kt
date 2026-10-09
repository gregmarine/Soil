package com.symmetricalpalmtree.soil.sketchsprout.sketch

/**
 * Where a guide grid's lines fall on a page (Notesprout SN's arc 51) — pure arithmetic, so every
 * position is pinned on the JVM.
 *
 * **Square cells, one count across the page's width**, so a portrait page carries more rows than
 * columns and the last row at either end is a partial cell. **Symmetric about the page's
 * centre**: an even count puts a line through the centre; an odd count puts the centre in the
 * middle of a cell. **Every position is computed from the centre outward, never accumulated**:
 * `x += cell` drifts by the float error of every step; `centre ± (offset + k·cell)` does not.
 * Only positions strictly inside the page are answered. Dots sit at every crossing.
 */
object GridLayout {

    data class Plan(val kind: GuideState.Kind, val xs: List<Float>, val ys: List<Float>, val cell: Float) {
        val dotCount: Int get() = xs.size * ys.size
    }

    private const val EDGE_EPSILON = 1e-6

    /** The grid of [kind] with [count] cells across [pageWidth], or **null** when there is nothing
     *  to draw: the grid off, a count below one, or a degenerate page. */
    fun plan(kind: GuideState.Kind, count: Int, pageWidth: Int, pageHeight: Int): Plan? {
        if (kind == GuideState.Kind.OFF || count < 1 || pageWidth <= 0 || pageHeight <= 0) return null
        val cell = pageWidth.toDouble() / count
        val offset = if (count % 2 == 0) 0.0 else cell / 2.0
        return Plan(kind, positions(pageWidth, cell, offset), positions(pageHeight, cell, offset), cell.toFloat())
    }

    private fun positions(extent: Int, cell: Double, offset: Double): List<Float> {
        val centre = extent / 2.0
        val below = ArrayList<Float>()
        val above = ArrayList<Float>()
        var k = 0
        while (true) {
            val d = offset + k * cell
            val lo = centre - d
            val hi = centre + d
            val loIn = lo > EDGE_EPSILON
            val hiIn = hi < extent - EDGE_EPSILON
            if (!loIn && !hiIn) break
            if (d == 0.0) {
                above += centre.toFloat()
            } else {
                if (loIn) below += lo.toFloat()
                if (hiIn) above += hi.toFloat()
            }
            k++
        }
        below.reverse()
        return below + above
    }
}
