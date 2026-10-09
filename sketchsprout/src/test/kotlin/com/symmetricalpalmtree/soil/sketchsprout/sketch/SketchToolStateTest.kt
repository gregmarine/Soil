package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.core.InkTones
import com.symmetricalpalmtree.soil.sketchsprout.sketch.SketchToolState.Kind
import com.symmetricalpalmtree.soil.sketchsprout.sketch.SketchToolState.Setting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchToolStateTest {

    @Test
    fun `the default is the pencil at level 1 and 1 px, the others at black on their default tips`() {
        val s = SketchToolState.DEFAULT
        assertEquals(Kind.PENCIL, s.kind)
        assertEquals(StrokeStyle.PENCIL, s.penStyle)
        assertEquals(1f, s.penWidth)
        assertEquals(InkTones.tone(1, 0), s.penColor)
        val pen = s.withKind(Kind.PEN)
        assertEquals(StrokeStyle.PEN, pen.penStyle)
        assertEquals(5.9f, pen.penWidth)
        assertEquals(InkTones.tone(InkTones.BLACK, 0), pen.penColor)
        val marker = s.withKind(Kind.MARKER)
        assertEquals(StrokeStyle.MARKER, marker.penStyle)
        assertEquals(35.4f, marker.penWidth)
        assertEquals(InkTones.tone(InkTones.BLACK, 0), marker.penColor)
    }

    @Test
    fun `a shade pick and a size pick edit the armed kind only`() {
        val s = SketchToolState.DEFAULT.withShade(4).withSize(2).withKind(Kind.PEN).withShade(7).withSize(4)
        assertEquals(Setting(4, 2), s.settings.getValue(Kind.PENCIL))
        assertEquals(Setting(7, 4), s.settings.getValue(Kind.PEN))
        assertEquals(Setting(InkTones.BLACK, 1), s.settings.getValue(Kind.MARKER))
        assertEquals(7, s.armedShade)
        assertEquals(4, s.armedSize)
        assertEquals(11.8f, s.penWidth)
        // The pencil's button still reports the pencil's own shade.
        assertEquals(InkTones.tone(4, 0), s.report(Kind.PENCIL))
    }

    @Test
    fun `a kind switch keeps every kind's settings`() {
        val s = SketchToolState.DEFAULT.withKind(Kind.MARKER).withSize(0).withShade(3).withKind(Kind.PENCIL)
        assertEquals(Kind.PENCIL, s.kind)
        assertEquals(Setting(3, 0), s.settings.getValue(Kind.MARKER))
        assertEquals(11.8f, s.withKind(Kind.MARKER).penWidth)
    }

    @Test
    fun `a stored level or index this build does not offer reads as that kind's default, each on its own`() {
        val s = SketchToolState.of("MARKER", mapOf(Kind.PENCIL to Setting(200, 9), Kind.MARKER to Setting(5, -1)))
        assertEquals(Kind.MARKER, s.kind)
        assertEquals(Setting(SketchPalette.DEFAULT_SHADE, 0), s.settings.getValue(Kind.PENCIL))
        assertEquals(Setting(5, 1), s.settings.getValue(Kind.MARKER))
        assertEquals(Setting(SketchPalette.DEFAULT_PEN_SHADE, 2), s.settings.getValue(Kind.PEN))
    }

    @Test
    fun `an unknown kind name reads as the pencil`() {
        assertEquals(Kind.PENCIL, SketchToolState.of("brush", emptyMap()).kind)
        assertEquals(Kind.PENCIL, SketchToolState.of(null, emptyMap()).kind)
        assertEquals(Kind.PEN, SketchToolState.of("PEN", emptyMap()).kind)
    }

    @Test
    fun `the kinds' order is the bars' order`() {
        assertEquals(listOf(Kind.PENCIL, Kind.PEN, Kind.MARKER), Kind.entries.toList())
    }

    @Test
    fun `the ladders are the hand's pencil, the pen's tips and the marker's, at 300 ppi`() {
        assertEquals(300f, SketchPalette.PPI)
        assertEquals(5.9f, SketchPalette.mmToPx(0.5f))
        assertEquals(59.1f, SketchPalette.mmToPx(5f))
        assertEquals(listOf(1f, 2f, 4f, 59.1f), SketchPalette.PENCIL_SIZES.sizes.map { it.px })
        assertEquals(0, SketchPalette.PENCIL_SIZES.default)
        assertEquals(listOf(1.2f, 4.5f, 5.9f, 8.3f, 11.8f), SketchPalette.PEN_SIZES.sizes.map { it.px })
        assertEquals(2, SketchPalette.PEN_SIZES.default)
        assertEquals(5.9f, SketchPalette.PEN_SIZES.px(SketchPalette.PEN_SIZES.default))
        assertEquals(listOf(11.8f, 35.4f, 59.1f, 118.1f, 236.2f), SketchPalette.MARKER_SIZES.sizes.map { it.px })
        assertEquals(listOf(null, null, null, "2x", "4x"), SketchPalette.MARKER_SIZES.sizes.map { it.badge })
        assertEquals(1, SketchPalette.MARKER_SIZES.default)
        assertEquals(listOf("0.1 mm", "0.38 mm", "0.5 mm", "0.7 mm", "1.0 mm"), SketchPalette.PEN_SIZES.sizes.map { it.label })
        assertTrue(SketchPalette.isShade(0) && SketchPalette.isShade(15))
        assertFalse(SketchPalette.isShade(16))
        assertEquals(2f, SketchPalette.PENCIL_SIZES.px(1))
        assertEquals(1f, SketchPalette.PENCIL_SIZES.px(7))
    }
}
