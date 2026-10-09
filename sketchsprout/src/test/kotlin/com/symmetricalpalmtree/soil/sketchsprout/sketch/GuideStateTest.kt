package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.soil.sketchsprout.raster.GuideGrid
import com.symmetricalpalmtree.soil.sketchsprout.raster.GuideImage
import com.symmetricalpalmtree.soil.sketchsprout.raster.GuideRows
import com.symmetricalpalmtree.soil.sketchsprout.sketch.GuideState.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideStateTest {

    @Test fun aPageWithNeitherRowShowsNothing() {
        val s = GuideState.of(null, null, hasImage = false)
        assertEquals(GuideState.NONE, s)
        assertFalse(s.showsAnything)
        assertEquals(GuideSheet.DEFAULT_COUNT, s.gridCount)
        assertEquals(GuideSheet.DEFAULT_OPACITY, s.imageOpacity)
        assertNull(s.toGridRow())
    }

    @Test fun defaultsAreTheUsersWord() {
        assertEquals(4, GuideSheet.DEFAULT_COUNT)
        assertEquals(25, GuideSheet.DEFAULT_OPACITY)
        assertEquals(listOf(2, 4, 6, 8, 12, 16, 20, 24, 28, 32), GuideSheet.COUNTS)
        assertEquals(listOf(listOf(2, 4, 6, 8, 12), listOf(16, 20, 24, 28, 32)), GuideSheet.countRows())
        assertEquals(listOf(10, 25, 50, 75), GuideSheet.OPACITIES)
        assertEquals(Kind.LINES, GuideState.DEFAULT_KIND)
        assertEquals(0xFFAAAAAA.toInt(), GuideSheet.GRID_TONE)
    }

    @Test fun offLadderValuesReadAsDefaultsEachOnTheirOwn() {
        val s = GuideState.of(GuideGrid(GuideRows.KIND_DOTS, 5, false), GuideImage(33, false), hasImage = true)
        assertEquals(Kind.DOTS, s.gridKind)
        assertEquals(GuideSheet.DEFAULT_COUNT, s.gridCount)
        assertFalse(s.gridVisible)
        assertEquals(GuideSheet.DEFAULT_OPACITY, s.imageOpacity)
        assertFalse(s.imageVisible)
        assertTrue(s.hasImage)
    }

    @Test fun anUnknownKindReadsAsOff() {
        val s = GuideState.of(GuideGrid("SPIRAL", 8, true), GuideImage(50), hasImage = false)
        assertEquals(Kind.OFF, s.gridKind)
        assertEquals(8, s.gridCount)
        assertEquals(50, s.imageOpacity)
    }

    @Test fun theRowsRoundTrip() {
        val s = GuideState(Kind.DOTS, 12, false, 75, true, hasImage = true)
        assertEquals(s, GuideState.of(s.toGridRow(), s.toImageRow(), hasImage = true))
        assertEquals(GuideGrid(GuideRows.KIND_DOTS, 12, false), s.toGridRow())
        assertEquals(GuideImage(75, true), s.toImageRow())
    }

    @Test fun pickingAKindShowsTheGridAndOffKeepsTheCount() {
        val hidden = GuideState.NONE.withGrid(Kind.LINES).withCount(8).toggleGridVisible()
        assertFalse(hidden.showsGrid)
        val dots = hidden.withGrid(Kind.DOTS)
        assertTrue(dots.showsGrid)
        val off = dots.withGrid(Kind.OFF)
        assertFalse(off.gridOn)
        assertEquals(8, off.gridCount)
        assertEquals(8, off.withGrid(Kind.LINES).gridCount)
    }

    @Test fun aCountWithTheGridOffTurnsLinesOn() {
        val s = GuideState.NONE.withCount(6)
        assertEquals(Kind.LINES, s.gridKind)
        assertEquals(6, s.gridCount)
        assertTrue(s.showsGrid)
        assertEquals(GuideSheet.DEFAULT_COUNT, GuideState.NONE.withCount(5).gridCount)
    }

    @Test fun theImageVerbs() {
        val picked = GuideState.NONE.withOpacity(50).withImage()
        assertTrue(picked.showsImage)
        assertEquals(50, picked.imageOpacity)
        val hidden = picked.toggleImageVisible()
        assertFalse(hidden.showsImage)
        assertTrue(hidden.hasImage)
        assertTrue(hidden.withOpacity(10).showsImage)
        val removed = hidden.withoutImage()
        assertFalse(removed.hasImage)
        assertEquals(50, removed.imageOpacity)
        assertEquals(GuideSheet.DEFAULT_OPACITY, picked.withOpacity(40).imageOpacity)
    }
}
