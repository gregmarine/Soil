package com.symmetricalpalmtree.soil.paper.chrome

import com.symmetricalpalmtree.soil.paper.chrome.ChromeBand.Bar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The band rule behind every floating bar's placement (arc 33 / F1). The case that matters most is
 * the one the old per-screen `chromeBand()`s got wrong: a hidden bar must widen the band to the root
 * edge, never collapse it to null.
 */
class ChromeBandTest {

    private val shownTop = Bar(shown = true, edge = 100, laidOut = true)
    private val shownBottom = Bar(shown = true, edge = 1800, laidOut = true)

    @Test fun `both bars shown and laid out - the band is between their facing edges`() {
        assertEquals(100..1800, ChromeBand.of(2000, shownTop, shownBottom))
    }

    @Test fun `a hidden top bar contributes the root top`() {
        assertEquals(0..1800, ChromeBand.of(2000, shownTop.copy(shown = false), shownBottom))
    }

    @Test fun `a hidden bottom bar contributes the root bottom`() {
        assertEquals(100..2000, ChromeBand.of(2000, shownTop, shownBottom.copy(shown = false)))
    }

    @Test fun `both hidden - the whole root is the band`() {
        assertEquals(0..2000, ChromeBand.of(2000, shownTop.copy(shown = false), shownBottom.copy(shown = false)))
    }

    @Test fun `a hidden bar's stale edge is ignored even when laid out`() {
        // A GONE view keeps its last edges — they must never leak into the band.
        assertEquals(0..2000, ChromeBand.of(2000, Bar(false, 100, true), Bar(false, 1800, true)))
    }

    @Test fun `a shown bar that is not laid out withholds the band`() {
        assertNull(ChromeBand.of(2000, shownTop.copy(laidOut = false), shownBottom))
        assertNull(ChromeBand.of(2000, shownTop, shownBottom.copy(laidOut = false)))
    }

    @Test fun `a hidden bar that is not laid out does not withhold it`() {
        assertEquals(0..1800, ChromeBand.of(2000, Bar(false, 0, false), shownBottom))
    }

    @Test fun `no top bar - the band starts at the root top`() {
        assertEquals(0..1800, ChromeBand.of(2000, null, shownBottom))
    }

    @Test fun `no bottom bar - the band ends at the root bottom`() {
        assertEquals(100..2000, ChromeBand.of(2000, shownTop, null))
    }

    @Test fun `no bars at all - the whole root`() {
        assertEquals(0..2000, ChromeBand.of(2000, null, null))
    }

    @Test fun `root not laid out - null whatever the bars say`() {
        assertNull(ChromeBand.of(0, shownTop, shownBottom))
        assertNull(ChromeBand.of(0, null, null))
    }

    @Test fun `an empty or inverted band is null`() {
        assertNull(ChromeBand.of(2000, Bar(true, 1000, true), Bar(true, 1000, true)))
        assertNull(ChromeBand.of(2000, Bar(true, 1200, true), Bar(true, 1000, true)))
    }
}
