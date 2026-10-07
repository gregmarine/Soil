package com.symmetricalpalmtree.soil.paper.ink

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where pasted ink lands: centred, at its source, clamped onto the page, under fresh ids. */
class InkPlacementTest {

    private fun stroke(id: String, vararg xy: Float): Stroke =
        Stroke(id = id, points = xy.toList().chunked(2).map { (x, y) -> StrokePoint(x, y) })

    private fun ids(): () -> String { var n = 0; return { "id-${n++}" } }

    @Test
    fun `centred lands the box in the middle of the page under fresh ids, the shape kept`() {
        val placed = InkPlacement.centred(listOf(stroke("a", 0f, 0f, 10f, 10f), stroke("b", 10f, 0f, 20f, 40f)), 100f, 200f, ids())
        assertEquals(listOf("id-0", "id-1"), placed.map { it.id })
        // The box 0..20 × 0..40 lands at 40..60 × 80..120.
        assertEquals(40f, placed[0].points[0].x)
        assertEquals(80f, placed[0].points[0].y)
        assertEquals(60f, placed[1].points[1].x)
        assertEquals(120f, placed[1].points[1].y)
    }

    @Test
    fun `at source keeps the layout, and pulls ink that hangs off the page back onto it`() {
        val kept = InkPlacement.atSource(listOf(stroke("a", 5f, 7f, 15f, 17f)), 100f, 200f, ids())
        assertEquals("id-0", kept[0].id)
        assertEquals(5f, kept[0].points[0].x)
        assertEquals(7f, kept[0].points[0].y)
        val pulled = InkPlacement.atSource(listOf(stroke("a", 95f, 195f, 105f, 205f)), 100f, 200f, ids())
        assertEquals(90f, pulled[0].points[0].x)
        assertEquals(190f, pulled[0].points[0].y)
        val negative = InkPlacement.atSource(listOf(stroke("a", -5f, -5f, 5f, 5f)), 100f, 200f, ids())
        assertEquals(0f, negative[0].points[0].x)
        assertEquals(0f, negative[0].points[0].y)
    }

    @Test
    fun `wider than the page lands from the near edge, an unknown page size does not clamp, nothing is nothing`() {
        val wide = InkPlacement.centred(listOf(stroke("a", 50f, 50f, 250f, 60f)), 100f, 200f, ids())
        assertEquals(0f, wide[0].points[0].x)
        assertEquals(95f, wide[0].points[0].y)
        val unknown = InkPlacement.centred(listOf(stroke("a", 0f, 0f, 10f, 10f)), 0f, 0f, ids())
        assertEquals(-5f, unknown[0].points[0].x)
        val unknownSource = InkPlacement.atSource(listOf(stroke("a", -5f, -5f, 5f, 5f)), 0f, 0f, ids())
        assertEquals(-5f, unknownSource[0].points[0].x)
        assertTrue(InkPlacement.centred(emptyList(), 100f, 100f, ids()).isEmpty())
        assertTrue(InkPlacement.atSource(listOf(stroke("e")), 100f, 100f, ids()).isEmpty())
    }
}
