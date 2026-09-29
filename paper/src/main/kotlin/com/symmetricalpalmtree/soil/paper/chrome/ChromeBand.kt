package com.symmetricalpalmtree.soil.paper.chrome

import android.view.View

/**
 * The free band between a screen's two chrome bars, in the root's coordinates — where a floating
 * bar (selection toolbar, lasso popup, eraser sub-bar, `FloatingSelectionBar`) may be placed.
 *
 * One pure rule for every paper screen (arc 33). Before it, each screen computed the band from
 * the bars' measured heights and answered null when either was 0 — which is exactly what a
 * `GONE` bar reports through `bottom`/`top` after a relayout, so **every floating bar would have
 * silently refused to show while the chrome was hidden** (`AnchoredBar.show` returns false on a
 * null band; the selection bars return early). Here a hidden bar contributes the root's own edge
 * instead, and only a *shown* bar that has not been laid out yet withholds the band.
 */
object ChromeBand {

    /**
     * One bar's contribution. [edge] is the edge that faces the paper — the top bar's `bottom`,
     * the bottom bar's `top` — in the root's coordinates; [laidOut] whether that edge is real yet.
     */
    data class Bar(val shown: Boolean, val edge: Int, val laidOut: Boolean)

    /**
     * @param rootHeight the root view's height; 0 before layout → null.
     * @param top the top bar, or null when the screen has none (the band starts at 0).
     * @param bottom the bottom bar, or null when the screen has none (the band ends at [rootHeight]).
     * @return the band, or null while it cannot be known or would be empty / inverted.
     */
    fun of(rootHeight: Int, top: Bar?, bottom: Bar?): IntRange? {
        if (rootHeight <= 0) return null
        val first = when {
            top == null || !top.shown -> 0
            !top.laidOut -> return null
            else -> top.edge
        }
        val last = when {
            bottom == null || !bottom.shown -> rootHeight
            !bottom.laidOut -> return null
            else -> bottom.edge
        }
        if (last <= first) return null
        return first..last
    }
}

/**
 * A view as a [ChromeBand.Bar]: shown iff [View.VISIBLE], laid out iff it has a height. Android-
 * typed and untested on purpose — the rule under it is what [ChromeBand] tests.
 *
 * @param edge the edge facing the paper, read by the caller (`bottom` for a top bar, `top` for a
 *   bottom bar) because a `GONE` view still reports its last laid-out edges.
 */
fun View.asBar(edge: Int): ChromeBand.Bar =
    ChromeBand.Bar(shown = visibility == View.VISIBLE, edge = edge, laidOut = height > 0)
