package com.symmetricalpalmtree.soil.sketchsprout.raster

import org.junit.Assert.assertEquals
import org.junit.Test

class RasterEffortTest {

    @Test
    fun `a sparse page gets the full search`() {
        assertEquals(RasterEffort.FULL, RasterEffort.choose(0.0))
        assertEquals(RasterEffort.FULL, RasterEffort.choose(0.02))
        assertEquals(RasterEffort.FULL, RasterEffort.choose(RasterEffort.DENSE_FRACTION))
    }

    @Test
    fun `a dense page gets the fast encode`() {
        assertEquals(RasterEffort.FAST, RasterEffort.choose(RasterEffort.DENSE_FRACTION + 0.001))
        assertEquals(RasterEffort.FAST, RasterEffort.choose(1.0))
    }

    @Test
    fun `the two efforts are the ends of the dial`() {
        assertEquals(100, RasterEffort.FULL)
        assertEquals(0, RasterEffort.FAST)
    }
}
