package com.symmetricalpalmtree.soil.paper.chrome

import android.graphics.Rect
import com.symmetricalpalmtree.soil.paper.core.Slog
import android.view.MotionEvent
import android.view.View
import com.symmetricalpalmtree.gpaper.core.PaperView

/**
 * A paper-hosting screen's chrome geometry (arc 11 / J1): the exclusion rects the stylus must not
 * ink under, and the "is this touch over chrome" test the finger gestures and the EPD
 * chrome-release use.
 *
 * Every screen's `pushExclusions` also carries `paper.snapMarginPx` ([SnapMargin]): the top bar's
 * laid-out height, re-read on every chrome layout change so it can never drift from the bar it
 * names. Harmless on a screen that never arms [SnapToggle].
 *
 * Exclusion: while [blockAll] — the page not yet on the paper, or a full-height panel showing, the
 * two cases where a pen stroke would be lost or drawn under a window — the **whole paper** is one
 * rect; otherwise the top bar + bottom strip + [extraRects] (window coordinates: the host's
 * floating bars), translated into paper coordinates. Applied to the hardware pen layer and filtered
 * model-side by g-paper. [extraContains] is the matching hit test for [overChrome], in the host
 * root's view-local coordinates.
 */
class PaperChrome(
    private val paper: PaperView,
    private val topBar: View,
    /** The bottom strip, or null on a screen that has none. */
    private val bottomStrip: View?,
    private val extraRects: () -> List<Rect> = { emptyList() },
    private val extraContains: (Int, Int) -> Boolean = { _, _ -> false },
    private val blockAll: () -> Boolean = { false },
    /** Rects already in **paper** coordinates: the bands beyond a page smaller than the surface. */
    private val paperRects: () -> List<Rect> = { emptyList() },
) {
    fun pushExclusions() {
        val view = paper.asView()
        SnapMargin.fromTopBar(topBar.height)?.let { paper.snapMarginPx = it }
        if (blockAll()) {
            paper.setExclusionRects(listOf(Rect(0, 0, maxOf(view.width, 1), maxOf(view.height, 1))))
            return
        }
        val paperLoc = IntArray(2).also { view.getLocationInWindow(it) }
        val rects = (listOfNotNull(PaperToolbar.rectOf(topBar), bottomStrip?.let { PaperToolbar.rectOf(it) }) + extraRects())
            .map { Rect(it.left - paperLoc[0], it.top - paperLoc[1], it.right - paperLoc[0], it.bottom - paperLoc[1]) }
        paper.setExclusionRects(rects + paperRects())
    }

    fun overChrome(ev: MotionEvent): Boolean {
        val top = PaperToolbar.rectOf(topBar)
        val bottom = bottomStrip?.let { PaperToolbar.rectOf(it) }
        val x = ev.x.toInt(); val y = ev.y.toInt()
        return (top?.contains(x, y) == true) || (bottom?.contains(x, y) == true) || extraContains(x, y)
    }
}
