package com.symmetricalpalmtree.soil.paper.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ink wire between the pad and a notebook: a round trip, fresh ids, and every refusal. */
class InkWireTest {

    private fun stroke(id: String, n: Int, width: Float = 3f, style: StrokeStyle = StrokeStyle.PEN) =
        Stroke(id, List(n) { StrokePoint(it.toFloat(), it * 2f, 0.5f, 0.25f, 77L) }, 0xFF808080.toInt(), width, style)

    @Test
    fun `a round trip keeps geometry, width, colour and style, and mints fresh ids with zero time`() {
        val bytes = InkWire.encode(listOf(stroke("a", 3), stroke("b", 2)), 1404f, 1872f)
        val back = InkWire.decode(bytes)!!
        assertEquals(1404f, back.pageWidth, 0f)
        assertEquals(1872f, back.pageHeight, 0f)
        assertEquals(2, back.strokes.size)
        assertNotEquals("a", back.strokes[0].id)
        assertNotEquals(back.strokes[0].id, back.strokes[1].id)
        assertEquals(3, back.strokes[0].points.size)
        assertEquals(2f, back.strokes[0].points[1].y, 0.001f)
        assertEquals(0.5f, back.strokes[0].points[0].pressure, 0.01f)
        assertEquals(0L, back.strokes[0].points[0].timeMillis)
        assertEquals(0xFF808080.toInt(), back.strokes[0].color)
        assertEquals(3f, back.strokes[0].width, 0f)
        assertEquals(StrokeStyle.PEN, back.strokes[0].style)
    }

    @Test
    fun `a point-less stroke is skipped, the width is clamped, and the ids are the caller's`() {
        val bytes = InkWire.encode(listOf(stroke("empty", 0), stroke("wide", 1, width = 999f), stroke("thin", 1, width = 0.01f)), 100f, 100f)
        var n = 0
        val back = InkWire.decode(bytes) { "new-${n++}" }!!
        assertEquals(listOf("new-0", "new-1"), back.strokes.map { it.id })
        assertEquals(InkWire.MAX_WIDTH, back.strokes[0].width, 0f)
        assertEquals(InkWire.MIN_WIDTH, back.strokes[1].width, 0f)
    }

    @Test
    fun `absent, garbage, truncated and trailing bytes read as nothing`() {
        assertNull(InkWire.decode(null))
        assertNull(InkWire.decode(ByteArray(0)))
        assertNull(InkWire.decode("not ink".toByteArray()))
        val bytes = InkWire.encode(listOf(stroke("a", 5)), 100f, 100f)
        assertNull(InkWire.decode(bytes.copyOf(bytes.size - 3)))
        assertNull(InkWire.decode(bytes + byteArrayOf(0)))
    }

    @Test
    fun `the caps refuse a send and refuse a read`() {
        assertTrue(InkWire.withinLimits(listOf(stroke("a", 10))))
        assertFalse(InkWire.withinLimits(List(InkWire.MAX_STROKES + 1) { stroke("s$it", 1) }))
        assertFalse(InkWire.withinLimits(listOf(stroke("a", InkWire.MAX_POINTS + 1))))
        val over = InkWire.encode(listOf(stroke("a", InkWire.MAX_POINTS + 1)), 100f, 100f)
        assertNull(InkWire.decode(over))
    }
}
