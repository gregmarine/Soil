package com.symmetricalpalmtree.soil.notesprout.objects

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {

    // ── The payload ──────

    @Test
    fun `the three kinds encode as SN wrote them and decode back`() {
        assertEquals("L1|1|0||p1", LinkPayload.encode(1, LinkPayload.KIND_PAGE, null, "p1"))
        assertEquals("L1|0|1|nb|", LinkPayload.encode(0, LinkPayload.KIND_ITEM, "nb", null))
        assertEquals("L1|1|2|nb|p1", LinkPayload.encode(1, LinkPayload.KIND_ITEM_PAGE, "nb", "p1"))
        assertEquals(LinkPayload.Decoded(1, 0, null, "p1"), LinkPayload.decode("L1|1|0||p1"))
        assertEquals(LinkPayload.Decoded(0, 1, "nb", null), LinkPayload.decode("L1|0|1|nb|"))
        assertEquals(LinkPayload.Decoded(1, 2, "nb", "p1"), LinkPayload.decode("L1|1|2|nb|p1"))
    }

    @Test
    fun `encode refuses a caller's mistake`() {
        assertThrows(IllegalArgumentException::class.java) { LinkPayload.encode(2, 0, null, "p") }
        assertThrows(IllegalArgumentException::class.java) { LinkPayload.encode(1, 0, "nb", "p") }
        assertThrows(IllegalArgumentException::class.java) { LinkPayload.encode(1, 1, "nb", "p") }
        assertThrows(IllegalArgumentException::class.java) { LinkPayload.encode(1, 2, null, "p") }
        assertThrows(IllegalArgumentException::class.java) { LinkPayload.encode(1, 0, null, "a|b") }
        assertThrows(IllegalArgumentException::class.java) { LinkPayload.encode(1, 9, "nb", null) }
    }

    @Test
    fun `decode never throws and answers null for what it cannot read`() {
        for (bad in listOf("", "nonsense", "L2|1|0||p", "L1|5|0||p", "L1|1|3|JHN:3:16|", "L1|1|4|x|", "L1|1|0|nb|p", "L1|1|1||", "L1|1|2|nb|", "L1|1|0||" + "x".repeat(65), "L1|x|0||p", "L1|1|0||p|extra")) {
            assertNull(bad, LinkPayload.decode(bad))
        }
        assertEquals(LinkPayload.CHROME_NONE, LinkPayload.chromeOf("nonsense"))
        assertEquals(LinkPayload.CHROME_UNDERLINE, LinkPayload.chromeOf("L1|1|0||p"))
        assertNull(LinkPayload.decode("x".repeat(LinkPayload.MAX_PAYLOAD_CHARS + 1)))
        assertEquals(LinkPayload.MAX_PAYLOAD_CHARS, LinkPayload.cap("y".repeat(5_000)).length)
    }

    // ── The target and the plan ──────

    @Test
    fun `a target is what the mirror writes down`() {
        assertEquals(LinkTarget("me", "p1"), LinkTarget.of("L1|1|0||p1", "me"))
        assertEquals(LinkTarget("nb", null), LinkTarget.of("L1|0|1|nb|", "me"))
        assertEquals(LinkTarget("nb", "p1"), LinkTarget.of("L1|1|2|nb|p1", "me"))
        assertNull(LinkTarget.of("nonsense", "me"))
    }

    @Test
    fun `a follow is planned from the payload and the notebook it is in`() {
        assertEquals(LinkNav.Follow.SamePage("p1"), LinkNav.planFollow("L1|1|0||p1", "me"))
        assertEquals(LinkNav.Follow.NoOp, LinkNav.planFollow("L1|0|1|me|", "me"))
        assertEquals(LinkNav.Follow.OtherItem("nb", null), LinkNav.planFollow("L1|0|1|nb|", "me"))
        assertEquals(LinkNav.Follow.SamePage("p1"), LinkNav.planFollow("L1|1|2|me|p1", "me"))
        assertEquals(LinkNav.Follow.OtherItem("nb", "p1"), LinkNav.planFollow("L1|1|2|nb|p1", "me"))
        assertEquals(LinkNav.Follow.Dead, LinkNav.planFollow("L1|1|3|JHN:3:16|", "me"))
        assertEquals(LinkNav.Back.SamePage("p1"), LinkNav.planBack("me", "p1", "me"))
        assertEquals(LinkNav.Back.OtherItem("nb", "p1"), LinkNav.planBack("nb", "p1", "me"))
    }

    // ── The link's box ──────

    private fun stroke(id: String, vararg xy: Float, width: Float = 4f) = Stroke(
        id = id, points = xy.toList().chunked(2).map { (x, y) -> StrokePoint(x, y) }, width = width,
    )

    @Test
    fun `the box is the union of what is wrapped, carried down to the underline band`() {
        val density = 2f
        val s = stroke("s", 10f, 10f, 50f, 30f)
        val h = Heading("h", "# A", 1, 60f, 5f, 40f, 20f, 0)
        val b = requireNotNull(PageLink.unionBounds(listOf(s), listOf(h), emptyList(), emptyList(), density))
        assertEquals(10f, b.left)
        assertEquals(5f, b.top)
        assertEquals(100f, b.right)
        // Ink: its extent (30 + half of 4) plus a heading's padding, plus the clearance; the heading's box as is.
        val pad = com.symmetricalpalmtree.soil.markdown.HeadingTypography.paddingPx(density)
        val expected = maxOf(32f + pad, 25f) + PageLink.UNDERLINE_CLEARANCE_DP * density
        assertEquals(expected, b.bottom, 0.001f)
        assertNull(PageLink.unionBounds(emptyList(), emptyList(), emptyList(), emptyList(), density))
    }

    @Test
    fun `a link moves with everything it wraps, and a remeasure only grows it`() {
        val l = PageLink("l", "L1|1|0||p", 1, 0f, 0f, 10f, 10f, 0, listOf(stroke("s", 1f, 1f, 2f, 2f)), listOf(Heading("h", "# A", 1, 0f, 0f, 5f, 5f, 0)))
        val moved = l.translated(3f, 4f)
        assertEquals(3f, moved.x)
        assertEquals(5f, moved.strokes[0].points[0].y)
        assertEquals(3f, moved.headings[0].x)
        val grown = l.remeasured({ it.copy(width = 50f, height = 30f) }, { it }, 1f)
        assertEquals(50f, grown.width)
        assertTrue(grown.height > 10f)
        val same = grown.remeasured({ it.copy(width = 5f, height = 5f) }, { it }, 1f)
        assertEquals(50f, same.width)
        assertEquals(grown.height, same.height)
        assertEquals(listOf("s", "h"), l.childIds)
    }

    @Test
    fun `a link row reads back, and a row with no box is dropped`() {
        val columns = listOf("id", "order", "text", "x", "y", "width", "height")
        val good = Row(columns, listOf(Cell.Text("l"), Cell.Integer(3), Cell.Text("L1|1|0||p"), Cell.Real(1.0), Cell.Real(2.0), Cell.Real(3.0), Cell.Real(4.0)))
        val link = requireNotNull(LinkRows.toLink(good, emptyList(), emptyList(), emptyList(), emptyList()))
        assertEquals(3, link.order)
        assertEquals(1, link.chrome)
        assertEquals(4f, link.height)
        val noBox = Row(columns, listOf(Cell.Text("l"), Cell.Integer(3), Cell.Text("x"), Cell.Null, Cell.Real(2.0), Cell.Real(3.0), Cell.Real(4.0)))
        assertNull(LinkRows.toLink(noBox, emptyList(), emptyList(), emptyList(), emptyList()))
        val nullPayload = Row(columns, listOf(Cell.Text("l"), Cell.Integer(0), Cell.Null, Cell.Real(1.0), Cell.Real(2.0), Cell.Real(3.0), Cell.Real(4.0)))
        assertEquals(0, requireNotNull(LinkRows.toLink(nullPayload, emptyList(), emptyList(), emptyList(), emptyList())).chrome)
    }

    // ── The trail ──────

    @Test
    fun `the trail is a bounded stack whose stored form is untrusted`() {
        var t = emptyList<TrailEntry>()
        for (i in 0 until TrailCodec.MAX_ENTRIES + 5) t = TrailCodec.push(t, TrailEntry("nb", "p$i"))
        assertEquals(TrailCodec.MAX_ENTRIES, t.size)
        assertEquals("p5", t.first().pageId)
        val (top, rest) = TrailCodec.pop(t)
        assertEquals("p${TrailCodec.MAX_ENTRIES + 4}", top?.pageId)
        assertEquals(TrailCodec.MAX_ENTRIES - 1, rest.size)
        assertEquals(t, TrailCodec.decode(TrailCodec.encode(t)))
        assertEquals(emptyList<TrailEntry>(), TrailCodec.decode(null))
        assertEquals(listOf(TrailEntry("a", "b")), TrailCodec.decode("garbage\na/b\n/x\ny/\n"))
        assertEquals(null to emptyList<TrailEntry>(), TrailCodec.pop(emptyList()))
    }

    @Test
    fun `a page is named by its topmost heading, bare`() {
        val headings = listOf(Heading("a", "## Lower", 2, 0f, 50f, 1f, 1f, 0), Heading("b", "# Top", 1, 5f, 10f, 1f, 1f, 1), Heading("c", "#  ", 1, 0f, 10f, 1f, 1f, 2))
        assertEquals("Top", PageLabels.titleOf(headings.drop(0).filter { it.id != "c" }))
        assertNull(PageLabels.titleOf(listOf(headings[2])))
        assertNull(PageLabels.titleOf(emptyList()))
    }
}
