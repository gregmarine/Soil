package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.notesprout.data.NotebookRow
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import com.symmetricalpalmtree.soil.notesprout.objects.StickyFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capture → envelope → plan for a lasso selection: fresh ids, parent rewiring, the order rebase, the stroke round trip. */
class ObjectClipTest {

    private val notebookId = "nb-src"
    private val srcPage = "page-1"
    private val dstPage = "page-2"
    private val now = 5_000L

    private fun stroke(id: String, x: Float, y: Float, width: Float = 4f) = Stroke(
        id = id,
        points = listOf(StrokePoint(x, y, 0.5f, 0.1f, 0L), StrokePoint(x + 10f, y + 20f, 1f, 0f, 0L)),
        color = 0xFF000000.toInt(), width = width, style = StrokeStyle.PEN,
    )

    private fun strokeRow(id: String, parentId: String, order: Int, x: Float, y: Float) = NotebookRow.ofStroke(parentId, order, stroke(id, x, y))

    private fun headingRow(id: String, parentId: String, order: Int, x: Float, y: Float) =
        NotebookRow(id = id, parentId = parentId, type = NotebookSchema.TYPE_HEADING, order = order, text = "## Title", flags = 2L, x = x, y = y, width = 120f, height = 40f)

    private fun linkRow(id: String, parentId: String, order: Int, x: Float, y: Float, payload: String = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "page-9")) =
        NotebookRow(id = id, parentId = parentId, type = NotebookSchema.TYPE_LINK, order = order, text = payload, x = x, y = y, width = 200f, height = 90f)

    private fun textRow(id: String, parentId: String, order: Int, x: Float, y: Float) =
        NotebookRow(id = id, parentId = parentId, type = NotebookSchema.TYPE_TEXT, order = order, text = "an **object**", x = x, y = y, width = 80f, height = 30f)

    private fun stickyRow(id: String, parentId: String, order: Int, x: Float, y: Float) =
        NotebookRow(id = id, parentId = parentId, type = NotebookSchema.TYPE_STICKY, order = order, x = x, y = y, width = 72f, height = 72f, flags = StickyFlags.pack(1404, 1800))

    /** Loose ink + a heading + a link wrapping ink and a heading of its own. */
    private fun top() = listOf(strokeRow("s-a", srcPage, 3, 100f, 100f), strokeRow("s-b", srcPage, 7, 140f, 100f), headingRow("h-1", srcPage, 2, 100f, 200f), linkRow("lnk-1", srcPage, 1, 300f, 300f))
    private fun children() = listOf(strokeRow("s-wrapped", "lnk-1", 5, 310f, 310f), headingRow("h-wrapped", "lnk-1", 4, 320f, 340f))
    private fun envelope() = ObjectClip.capture(top(), children(), notebookId, now)!!
    private fun ids(): () -> String { var n = 0; return { "new-${n++}" } }
    private val noMove: (Bounds) -> ObjectPlacement.Offset = { ObjectPlacement.Offset.NONE }

    private fun plan(env: ClipEnvelope = envelope(), bases: Map<String, Int> = emptyMap(), place: (Bounds) -> ObjectPlacement.Offset = noMove, into: String = notebookId) =
        ObjectClip.plan(env, into, dstPage, { bases[it] ?: -1 }, ids(), place)

    @Test
    fun `ink the Scratch Pad copied pastes as a lasso's ink does`() {
        val fromPad = com.symmetricalpalmtree.soil.seamkit.clip.InkClip.envelopeOf(listOf(stroke("p-1", 10f, 10f), stroke("p-2", 60f, 10f)), now)!!
        val read = ClipEnvelope.decode(ClipEnvelope.encode(fromPad))!!
        val plan = plan(read, bases = mapOf(NotebookSchema.TYPE_STROKE to 4))!!
        assertEquals(2, plan.strokes.size)
        assertEquals(listOf(5, 6), plan.rows.map { it.order })
        assertTrue(plan.rows.all { it.parentId == dstPage && it.type == NotebookSchema.TYPE_STROKE })
        assertTrue(plan.rows.none { it.id == "p-1" || it.id == "p-2" })
    }

    @Test
    fun `capture writes an objects envelope carrying every row, and nothing is no clipboard`() {
        val env = envelope()
        assertEquals(ClipEnvelope.KIND_OBJECTS, env.kind)
        assertEquals(6, env.rows.size)
        assertTrue(env.rows.any { it.id == "s-wrapped" && it.parentId == "lnk-1" })
        assertNull(ObjectClip.capture(emptyList(), emptyList(), notebookId, now))
    }

    @Test
    fun `every pasted row gets a fresh id, top-level rows parent onto the page, wrapped children onto the copied link`() {
        val p = plan()!!
        val old = (top() + children()).map { it.id }.toSet()
        for (row in p.rows) assertTrue("${row.id} kept a source id", row.id !in old)
        assertEquals(6, p.contentIds.size)
        val link = p.links.single()
        assertNotEquals("lnk-1", link.id)
        for (row in p.rows) assertTrue(row.parentId == dstPage || row.parentId == link.id)
        assertEquals(1, link.strokes.size)
        assertEquals(1, link.headings.size)
        assertEquals(2, p.strokes.size)
        assertEquals(1, p.headings.size)
    }

    @Test
    fun `an orphan is dropped, orphans cannot outvote the page, a link names the page outright, a nested link is refused`() {
        val orphan = strokeRow("s-orphan", "lnk-gone", 0, 10f, 10f)
        assertEquals(3, plan(ObjectClip.capture(top(), children() + orphan, notebookId, now)!!)!!.rows.count { it.type == NotebookSchema.TYPE_STROKE })

        val outvoted = ObjectClip.capture(listOf(strokeRow("s-a", srcPage, 3, 100f, 100f)), listOf(strokeRow("orphan-a", "lnk-gone", 0, 10f, 10f), strokeRow("orphan-b", "lnk-gone", 1, 20f, 20f)), notebookId, now)!!
        val p = plan(outvoted)!!
        assertEquals(1, p.strokes.size)
        assertEquals(listOf(dstPage), p.rows.map { it.parentId })

        val orphans = (0 until 4).map { strokeRow("orphan-$it", "lnk-gone", it, 10f, 10f) }
        val linkFirst = plan(ObjectClip.capture(orphans + linkRow("lnk-1", srcPage, 9, 300f, 300f), emptyList(), notebookId, now)!!)!!
        assertEquals(1, linkFirst.links.size)
        assertTrue(linkFirst.strokes.isEmpty())

        val nested = plan(ObjectClip.capture(top(), children() + linkRow("lnk-2", "lnk-1", 0, 400f, 400f), notebookId, now)!!)!!
        assertEquals(1, nested.links.size)
    }

    @Test
    fun `order is rebased per type keeping the sequence, and wrapped children keep theirs verbatim`() {
        val p = plan(bases = mapOf(NotebookSchema.TYPE_STROKE to 11, NotebookSchema.TYPE_HEADING to 4, NotebookSchema.TYPE_LINK to 0))!!
        assertEquals(listOf(12, 13), p.rows.filter { it.type == NotebookSchema.TYPE_STROKE && it.parentId == dstPage }.map { it.order })
        assertEquals(5, p.rows.single { it.type == NotebookSchema.TYPE_HEADING && it.parentId == dstPage }.order)
        assertEquals(1, p.rows.single { it.type == NotebookSchema.TYPE_LINK }.order)
        val link = p.links.single()
        assertEquals(5, p.rows.single { it.parentId == link.id && it.type == NotebookSchema.TYPE_STROKE }.order)
        assertEquals(4, p.rows.single { it.parentId == link.id && it.type == NotebookSchema.TYPE_HEADING }.order)
    }

    @Test
    fun `a stroke survives the decode-translate-re-encode trip, boxes translate by their columns, the placement box is the ink extent`() {
        val p = plan(place = { ObjectPlacement.Offset(25f, -10f) })!!
        val moved = p.strokes.sortedBy { it.bounds.left }
        assertEquals(125f, moved[0].bounds.left, 0.01f)
        assertEquals(90f, moved[0].bounds.top, 0.01f)
        assertEquals(2, moved[0].points.size)
        assertEquals(0.5f, moved[0].points[0].pressure, 0.01f)
        assertEquals(4f, moved[0].width, 0.01f)
        assertEquals(125f, p.headings.single().x, 0.01f)
        assertEquals(190f, p.headings.single().y, 0.01f)
        val link = p.links.single()
        assertEquals(325f, link.x, 0.01f)
        assertEquals(290f, link.y, 0.01f)
        assertEquals(345f, link.headings.single().x, 0.01f)
        assertEquals(335f, link.strokes.single().bounds.left, 0.01f)

        var seen: Bounds? = null
        plan(place = { seen = it; ObjectPlacement.Offset.NONE })
        assertEquals(98f, seen!!.left, 0.01f)
        assertEquals(98f, seen!!.top, 0.01f)
        assertEquals(500f, seen!!.right, 0.01f)
    }

    private fun pastedLinkPayload(payload: String, into: String): String =
        plan(ObjectClip.capture(listOf(linkRow("lnk-1", srcPage, 1, 300f, 300f, payload)), emptyList(), notebookId, now)!!, into = into)!!.links.single().payload

    @Test
    fun `an own-page link is re-pointed at the source notebook when it crosses, verbatim at home, and others cross unchanged`() {
        val own = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "page-9")
        val out = LinkPayload.decode(pastedLinkPayload(own, "nb-dst"))!!
        assertEquals(LinkPayload.KIND_ITEM_PAGE, out.kind)
        assertEquals(notebookId, out.itemId)
        assertEquals("page-9", out.pageId)
        assertEquals(own, pastedLinkPayload(own, notebookId))
        // The source page itself too: no page travels in an objects payload.
        val self = LinkPayload.decode(pastedLinkPayload(LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_PAGE, null, srcPage), "nb-dst"))!!
        assertEquals(LinkPayload.KIND_ITEM_PAGE, self.kind)
        assertEquals(srcPage, self.pageId)
        for (payload in listOf(
            LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_ITEM, "nb-far", null),
            LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_ITEM_PAGE, "nb-far", "page-9"),
            "L9|1|0||page-9", "", "not a payload at all",
        )) assertEquals(payload, pastedLinkPayload(payload, "nb-dst"))
        val blank = ObjectClip.capture(listOf(linkRow("lnk-1", srcPage, 1, 300f, 300f, own)), emptyList(), "", now)!!
        assertEquals(own, plan(blank, into = "nb-dst")!!.links.single().payload)
        assertEquals("## Title", plan(into = "nb-dst")!!.headings.single().text)
    }

    @Test
    fun `a payload with only a page row plans nothing, and an unusable stroke blob costs that stroke`() {
        val pageRow = NotebookRow(id = srcPage, parentId = notebookId, type = NotebookSchema.TYPE_PAGE)
        assertNull(plan(ObjectClip.capture(listOf(pageRow), emptyList(), notebookId, now)!!))
        val bad = strokeRow("s-bad", srcPage, 9, 500f, 500f).copy(blob = byteArrayOf(1, 2))
        val p = plan(ObjectClip.capture(top() + bad, children(), notebookId, now)!!)!!
        assertEquals(2, p.strokes.size)
        assertEquals(1, p.headings.size)
        assertEquals(1, p.links.size)
    }

    private fun objectEnvelope() = ObjectClip.capture(
        listOf(textRow("t-1", srcPage, 2, 100f, 100f), stickyRow("n-1", srcPage, 6, 500f, 100f)),
        listOf(strokeRow("n-ink", "n-1", 0, 5f, 5f)),
        notebookId, now,
    )!!

    @Test
    fun `a text and a sticky travel with fresh ids and a per-type rebase, a note's content is un-shifted and re-parented onto the copy`() {
        val p = plan(objectEnvelope(), bases = mapOf(NotebookSchema.TYPE_TEXT to 3, NotebookSchema.TYPE_STICKY to 11), place = { ObjectPlacement.Offset(25f, -10f) })!!
        assertEquals(1, p.texts.size)
        assertEquals(1, p.stickies.size)
        assertEquals(3, p.contentIds.size)
        assertEquals(4, p.rows.single { it.type == NotebookSchema.TYPE_TEXT }.order)
        assertEquals(12, p.rows.single { it.type == NotebookSchema.TYPE_STICKY }.order)
        val note = p.stickies.single()
        assertEquals(525f, note.x, 0.01f)
        assertEquals(90f, note.y, 0.01f)
        assertEquals(1404, note.contentW)
        val ink = note.strokes.single()
        assertNotEquals("n-ink", ink.id)
        assertEquals(5f, ink.bounds.left, 0.01f)
        assertEquals(note.id, p.rows.single { it.type == NotebookSchema.TYPE_STROKE }.parentId)
        assertTrue(ink.id in p.contentIds)
    }

    @Test
    fun `a sticky inside a link travels three levels deep, and a note's orphaned ink or a wrong-kind child is refused`() {
        val env = ObjectClip.capture(
            listOf(linkRow("lnk-1", srcPage, 1, 300f, 300f)),
            listOf(stickyRow("n-1", "lnk-1", 0, 320f, 320f), textRow("t-1", "lnk-1", 0, 330f, 330f), strokeRow("n-ink", "n-1", 0, 7f, 7f)),
            notebookId, now,
        )!!
        val p = plan(env, place = { ObjectPlacement.Offset(10f, 10f) })!!
        val link = p.links.single()
        assertEquals(1, link.stickies.size)
        assertEquals(1, link.texts.size)
        assertTrue(p.stickies.isEmpty())
        val note = link.stickies.single()
        assertEquals(330f, note.x, 0.01f)
        assertEquals(340f, link.texts.single().x, 0.01f)
        assertEquals(7f, note.strokes.single().bounds.left, 0.01f)

        val orphaned = plan(ObjectClip.capture(listOf(strokeRow("s-a", srcPage, 0, 100f, 100f)), listOf(strokeRow("orphan", "sticky-gone", 0, 10f, 10f)), notebookId, now)!!)!!
        assertEquals(1, orphaned.strokes.size)
        val wrongKind = plan(ObjectClip.capture(listOf(stickyRow("n-1", srcPage, 0, 500f, 100f)), listOf(headingRow("h-inside", "n-1", 0, 10f, 10f)), notebookId, now)!!)!!
        assertEquals(1, wrongKind.stickies.size)
        assertTrue(wrongKind.stickies.single().strokes.isEmpty())
        assertEquals(1, wrongKind.rows.size)
    }

    @Test
    fun `the envelope round-trips through the codec unchanged`() {
        val back = ClipEnvelope.decode(ClipEnvelope.encode(envelope())!!)!!
        val p = ObjectClip.plan(back, notebookId, dstPage, { -1 }, ids(), noMove)!!
        assertEquals(2, p.strokes.size)
        assertEquals(1, p.headings.size)
        assertEquals(1, p.links.single().strokes.size)
    }
}
