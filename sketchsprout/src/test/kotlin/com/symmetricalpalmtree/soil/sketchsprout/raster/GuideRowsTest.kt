package com.symmetricalpalmtree.soil.sketchsprout.raster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideRowsTest {

    @Test fun `a grid round-trips through its row text`() {
        val grid = GuideGrid(GuideRows.KIND_DOTS, 12, false)
        val text = GuideRows.encodeGrid(grid)
        assertTrue(text.contains("\"kind\":\"DOTS\""))
        assertTrue("a default is written out", text.contains("\"visible\":false"))
        assertEquals(grid, GuideRows.decodeGrid(text))
    }

    @Test fun `an image's settings round-trip`() {
        val image = GuideImage(50)
        assertEquals(image, GuideRows.decodeImage(GuideRows.encodeImage(image)))
        assertTrue(GuideRows.encodeImage(image).contains("\"visible\":true"))
    }

    @Test fun `a stored row never throws, it reads as no guide`() {
        assertNull(GuideRows.decodeGrid(null))
        assertNull(GuideRows.decodeGrid("not json"))
        assertNull(GuideRows.decodeGrid("""{"kind":"OFF","count":4}"""))
        assertNull(GuideRows.decodeGrid("""{"kind":"LINES","count":0}"""))
        assertNull(GuideRows.decodeGrid("""{"kind":"LINES","count":999}"""))
        assertNull(GuideRows.decodeImage("""{"opacity":101}"""))
        assertNull(GuideRows.decodeImage("{}"))
    }

    @Test fun `a field this build does not know is skipped`() {
        assertEquals(GuideGrid(GuideRows.KIND_LINES, 8, true), GuideRows.decodeGrid("""{"kind":"LINES","count":8,"colour":"red"}"""))
    }
}
