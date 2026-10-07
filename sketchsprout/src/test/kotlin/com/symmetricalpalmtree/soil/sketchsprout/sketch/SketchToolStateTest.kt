package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.core.InkTones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchToolStateTest {

    @Test
    fun `the default is the pencil at level 1 and the pen at black`() {
        val s = SketchToolState.DEFAULT
        assertEquals(SketchToolState.Kind.PENCIL, s.kind)
        assertFalse(s.isPen)
        assertEquals(StrokeStyle.PENCIL, s.penStyle)
        assertEquals(SketchPalette.PENCIL_WIDTH_PX, s.penWidth)
        assertEquals(InkTones.tone(1, 0), s.penColor)
        assertEquals(InkTones.BLACK, s.penShade)
    }

    @Test
    fun `arming the pen keeps the pencil's shade and draws the pen's`() {
        val s = SketchToolState.DEFAULT.withShade(4).withKind(SketchToolState.Kind.PEN)
        assertTrue(s.isPen)
        assertEquals(StrokeStyle.PEN, s.penStyle)
        assertEquals(SketchPalette.PEN_WIDTH_PX, s.penWidth)
        assertEquals(4, s.pencilShade)
        assertEquals(InkTones.tone(InkTones.BLACK, 0), s.penColor)
        assertEquals(InkTones.tone(4, 0), s.pencilReport)
    }

    @Test
    fun `a shade pick edits the armed kind only`() {
        val pen = SketchToolState.DEFAULT.withKind(SketchToolState.Kind.PEN).withShade(7)
        assertEquals(7, pen.penShade)
        assertEquals(SketchPalette.DEFAULT_SHADE, pen.pencilShade)
        assertEquals(7, pen.armedShade)
    }

    @Test
    fun `a stored level this build does not offer reads as that kind's default`() {
        val s = SketchToolState.of("PEN", 200, -3)
        assertTrue(s.isPen)
        assertEquals(SketchPalette.DEFAULT_SHADE, s.pencilShade)
        assertEquals(SketchPalette.DEFAULT_PEN_SHADE, s.penShade)
    }

    @Test
    fun `an unknown kind name reads as the pencil`() {
        assertEquals(SketchToolState.Kind.PENCIL, SketchToolState.of("brush", 1, 0).kind)
        assertEquals(SketchToolState.Kind.PENCIL, SketchToolState.of(null, 1, 0).kind)
    }

    @Test
    fun `the palette's widths are a real pencil's and the hand's pen`() {
        assertEquals(1f, SketchPalette.PENCIL_WIDTH_PX)
        assertEquals(5f, SketchPalette.PEN_WIDTH_PX)
        assertTrue(SketchPalette.isShade(0) && SketchPalette.isShade(15))
        assertFalse(SketchPalette.isShade(16))
    }
}
