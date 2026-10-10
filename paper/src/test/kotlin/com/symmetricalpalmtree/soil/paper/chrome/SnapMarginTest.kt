package com.symmetricalpalmtree.soil.paper.chrome

import com.symmetricalpalmtree.gpaper.core.geometry.SnapEngine
import com.symmetricalpalmtree.gpaper.core.geometry.SnapGuide
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host's half of snap to guides: the margin is the top bar's laid-out height. The guides
 * themselves are g-paper's `SnapEngine` (tested there); these pin what Soil's margin buys through
 * it — SN's arc 9 rules, as the notebook and the sticky editor arm them.
 */
class SnapMarginTest {

    private val page = 1404f to 1872f
    private val threshold = 20f * 2f // 20 dp at density 2

    @Test
    fun theMarginIsTheTopBarsLaidOutHeight() {
        assertEquals(113f, SnapMargin.fromTopBar(113))
    }

    @Test
    fun beforeTheFirstLayoutTheSeedStands() {
        assertNull(SnapMargin.fromTopBar(0))
        assertNull(SnapMargin.fromTopBar(-1))
    }

    @Test
    fun aSelectionDroppedNearTheTopMarginLandsFlushUnderTheBar() {
        val bar = SnapMargin.fromTopBar(113)!!
        val box = Bounds(300f, 400f, 500f, 480f)
        // Dragged to a top of 125: twelve px under the bar, inside the threshold.
        val r = SnapEngine.computeSnap(box, 7f, 125f - 400f, page.first, page.second, bar, threshold)
        assertEquals(bar, box.top + r.dy)
        assertTrue(r.guides.contains(SnapGuide.Horizontal(bar)))
    }

    @Test
    fun belowAnotherObjectItCatchesOneMarginFromItsEdge() {
        val bar = SnapMargin.fromTopBar(113)!!
        val heading = Bounds(200f, 600f, 900f, 660f)
        val box = Bounds(220f, 1000f, 600f, 1060f)
        val wantTop = heading.bottom + bar
        val r = SnapEngine.computeSnap(box, 0f, (wantTop + 9f) - box.top, page.first, page.second, bar, threshold, listOf(heading))
        assertEquals(wantTop, box.top + r.dy)
    }

    @Test
    fun draggingOnPastTheThresholdLetsGo() {
        val bar = SnapMargin.fromTopBar(113)!!
        val box = Bounds(300f, 400f, 500f, 480f)
        val rawDy = (bar + threshold + 30f) - box.top
        val r = SnapEngine.computeSnap(box, 0f, rawDy, page.first, page.second, bar, threshold)
        assertEquals(rawDy, r.dy)
        assertTrue(r.guides.none { it is SnapGuide.Horizontal })
    }
}
