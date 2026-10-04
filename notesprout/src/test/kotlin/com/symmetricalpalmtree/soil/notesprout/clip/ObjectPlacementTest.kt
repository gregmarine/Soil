package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a pasted selection lands: the arithmetic that decides whether ink ends up off the page. */
class ObjectPlacementTest {

    private val pageW = 1000f
    private val pageH = 2000f
    private fun box(l: Float, t: Float, w: Float, h: Float) = Bounds(l, t, l + w, t + h)

    @Test
    fun `a tap in open space centres the box on it`() {
        val o = ObjectPlacement.centredOn(box(100f, 100f, 200f, 100f), 500f, 700f, pageW, pageH)
        assertEquals(300f, o.dx, 0.001f)
        assertEquals(550f, o.dy, 0.001f)
    }

    @Test
    fun `a tap near a corner pulls the box back onto the page`() {
        val tl = ObjectPlacement.centredOn(box(400f, 400f, 200f, 100f), 10f, 10f, pageW, pageH)
        assertEquals(-400f, tl.dx, 0.001f)
        assertEquals(-400f, tl.dy, 0.001f)
        val br = ObjectPlacement.centredOn(box(0f, 0f, 200f, 100f), 990f, 1990f, pageW, pageH)
        assertEquals(pageW - 200f, br.dx, 0.001f)
        assertEquals(pageH - 100f, br.dy, 0.001f)
    }

    @Test
    fun `content wider than the page pastes from the left edge`() {
        val o = ObjectPlacement.centredOn(box(120f, 300f, 1400f, 100f), 500f, 500f, pageW, pageH)
        assertEquals(-120f, o.dx, 0.001f)
        assertEquals(150f, o.dy, 0.001f)
    }

    @Test
    fun `at source, a box on the page does not move and one hanging off is pulled inside`() {
        val still = ObjectPlacement.atSource(box(100f, 100f, 200f, 100f), pageW, pageH)
        assertEquals(0f, still.dx, 0.001f)
        assertEquals(0f, still.dy, 0.001f)
        val pulled = ObjectPlacement.atSource(box(900f, 1950f, 300f, 200f), pageW, pageH)
        assertEquals(-200f, pulled.dx, 0.001f)
        assertEquals(-150f, pulled.dy, 0.001f)
    }

    @Test
    fun `an unknown page size clamps nothing, and a non-finite box moves nothing on that axis`() {
        val o = ObjectPlacement.centredOn(box(0f, 0f, 200f, 100f), 50f, 50f, 0f, 0f)
        assertEquals(-50f, o.dx, 0.001f)
        assertEquals(0f, o.dy, 0.001f)
        val nan = ObjectPlacement.centredOn(Bounds(Float.NaN, 0f, Float.NaN, 100f), 500f, 500f, pageW, pageH)
        assertEquals(0f, nan.dx, 0.001f)
        assertEquals(450f, nan.dy, 0.001f)
    }
}
