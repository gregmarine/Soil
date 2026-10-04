package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.soil.notesprout.data.NotebookRow
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capture → envelope → plan: which id becomes which, what re-parents onto what, whether order survives. */
class PageClipTest {

    private val notebookId = "nb-src"
    private val pageId = "page-1"
    private val templateId = "tpl-1"
    private val now = 5_000L

    private fun row(
        id: String, parentId: String, type: String, order: Int = 0, refId: String? = null, text: String? = null,
        blob: ByteArray? = null, width: Float? = null, height: Float? = null, flags: Long? = null,
    ) = NotebookRow(id = id, parentId = parentId, type = type, order = order, text = text, refId = refId, width = width, height = height, flags = flags, blob = blob)

    private val templateRow = row(templateId, notebookId, NotebookSchema.TYPE_TEMPLATE, text = "LINED", width = 1404f, height = 1872f, blob = byteArrayOf(1, 2, 3, 4))
    private val pageRow = row(pageId, notebookId, NotebookSchema.TYPE_PAGE, order = 2, refId = templateId, width = 1404f, height = 1872f)

    /** Loose ink, a heading, a link wrapping a stroke, a text, a sticky with ink of its own. */
    private fun content() = listOf(
        row("s-loose", pageId, NotebookSchema.TYPE_STROKE, order = 0, blob = byteArrayOf(9, 8, 7)),
        row("h-1", pageId, NotebookSchema.TYPE_HEADING, order = 1, text = "## Title", flags = 2L),
        row("lnk-1", pageId, NotebookSchema.TYPE_LINK, order = 0, text = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_PAGE, null, "page-9")),
        row("s-wrapped", "lnk-1", NotebookSchema.TYPE_STROKE, order = 5, blob = byteArrayOf(4, 5)),
        row("t-1", pageId, NotebookSchema.TYPE_TEXT, order = 3, text = "an **object**"),
        row("n-1", pageId, NotebookSchema.TYPE_STICKY, order = 5, width = 72f, height = 72f, flags = 7L),
        row("n-ink", "n-1", NotebookSchema.TYPE_STROKE, order = 0, blob = byteArrayOf(3, 3)),
    )

    private fun envelope() = PageClip.capture(pageRow, templateRow, content(), notebookId, now)

    private fun ids(): () -> String { var n = 0; return { "new-${n++}" } }

    @Test
    fun `capture carries the template, the page and every descendant, and round-trips`() {
        val env = envelope()
        assertEquals(ClipEnvelope.KIND_PAGE, env.kind)
        assertEquals(notebookId, env.sourceNotebookId)
        assertEquals(listOf(templateId, pageId, "s-loose", "h-1", "lnk-1", "s-wrapped", "t-1", "n-1", "n-ink"), env.rows.map { it.id })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), env.rows.first { it.type == "template" }.blobBytes())
        assertEquals(env, ClipEnvelope.decode(ClipEnvelope.encode(env)))
        assertEquals(listOf(templateId, pageId), PageClip.capture(pageRow, templateRow, emptyList(), notebookId, now).rows.map { it.id })
        assertEquals(listOf("page-b"), PageClip.capture(row("page-b", notebookId, NotebookSchema.TYPE_PAGE, refId = ""), null, emptyList(), notebookId, now).rows.map { it.id })
    }

    @Test
    fun `every row gets a fresh id, content re-parents onto the copies, three levels deep`() {
        val plan = PageClip.plan(envelope(), "nb-dest", 4, PageClip.Template.Reuse(templateId), ids())!!
        val sourceIds = content().map { it.id }.toSet() + pageId
        for (r in plan.rows) assertTrue("${r.id} kept a source id", r.id !in sourceIds)
        assertNotEquals(pageId, plan.pageId)
        assertEquals(7, plan.contentIds.size)
        val byType = plan.rows.groupBy { it.type }
        val newPage = byType.getValue(NotebookSchema.TYPE_PAGE).single()
        val newLink = byType.getValue(NotebookSchema.TYPE_LINK).single()
        val newNote = byType.getValue(NotebookSchema.TYPE_STICKY).single()
        assertEquals("nb-dest", newPage.parentId)
        assertEquals(newPage.id, newLink.parentId)
        assertEquals(setOf(newPage.id, newLink.id, newNote.id), byType.getValue(NotebookSchema.TYPE_STROKE).map { it.parentId }.toSet())
        assertEquals(newPage.id, byType.getValue(NotebookSchema.TYPE_HEADING).single().parentId)
        val ink = plan.rows.single { it.type == NotebookSchema.TYPE_STROKE && it.parentId == newNote.id }
        assertArrayEquals(byteArrayOf(3, 3), ink.blob)
        assertEquals(7L, newNote.flags)
    }

    @Test
    fun `order is preserved on content and rewritten only on the page, columns verbatim`() {
        val plan = PageClip.plan(envelope(), "nb-dest", 7, PageClip.Template.Reuse(templateId), ids())!!
        assertEquals(7, plan.rows.first { it.type == NotebookSchema.TYPE_PAGE }.order)
        assertEquals(listOf(0, 1, 0, 5, 3, 5, 0), plan.rows.filter { it.type != NotebookSchema.TYPE_PAGE && it.type != NotebookSchema.TYPE_TEMPLATE }.map { it.order })
        val heading = plan.rows.first { it.type == NotebookSchema.TYPE_HEADING }
        assertEquals("## Title", heading.text)
        assertEquals(2L, heading.flags)
        assertArrayEquals(byteArrayOf(4, 5), plan.rows.first { it.type == NotebookSchema.TYPE_STROKE && it.order == 5 }.blob)
        assertEquals(1404f, plan.rows.first { it.type == NotebookSchema.TYPE_PAGE }.width!!, 0f)
        assertTrue(plan.pageId !in plan.contentIds)
        assertTrue(templateId !in plan.contentIds)
    }

    @Test
    fun `Reuse points at the existing template, Insert brings the carried row in first, None and a missing row paste blank`() {
        val reuse = PageClip.plan(envelope(), "nb-dest", 0, PageClip.Template.Reuse("tpl-existing"), ids())!!
        assertTrue(reuse.rows.none { it.type == NotebookSchema.TYPE_TEMPLATE })
        assertEquals("tpl-existing", reuse.rows.first { it.type == NotebookSchema.TYPE_PAGE }.refId)

        val insert = PageClip.plan(envelope(), "nb-dest", 0, PageClip.Template.Insert(templateId), ids())!!
        val tpl = insert.rows.first { it.type == NotebookSchema.TYPE_TEMPLATE }
        assertEquals(templateId, tpl.id)
        assertEquals("nb-dest", tpl.parentId)
        assertEquals("LINED", tpl.text)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), tpl.blob)
        assertEquals(NotebookSchema.TYPE_TEMPLATE, insert.rows.first().type)
        assertEquals(templateId, insert.rows.first { it.type == NotebookSchema.TYPE_PAGE }.refId)

        val none = PageClip.plan(envelope(), "nb-dest", 0, PageClip.Template.None, ids())!!
        assertEquals("", none.rows.first { it.type == NotebookSchema.TYPE_PAGE }.refId)
        val missing = PageClip.plan(PageClip.capture(pageRow, null, emptyList(), notebookId, now), "nb-dest", 0, PageClip.Template.Insert(templateId), ids())!!
        assertTrue(missing.rows.none { it.type == NotebookSchema.TYPE_TEMPLATE })
        assertEquals("", missing.rows.first { it.type == NotebookSchema.TYPE_PAGE }.refId)
    }

    @Test
    fun `a payload with no page row plans nothing, and an orphan is dropped`() {
        val noPage = ClipEnvelope(ClipEnvelope.VERSION, ClipEnvelope.KIND_PAGE, notebookId, now, envelope().rows.filter { it.type != NotebookSchema.TYPE_PAGE })
        assertNull(PageClip.plan(noPage, "nb-dest", 0, PageClip.Template.None, ids()))
        val orphan = row("s-orphan", "lnk-gone", NotebookSchema.TYPE_STROKE, blob = byteArrayOf(1))
        val plan = PageClip.plan(PageClip.capture(pageRow, null, content() + orphan, notebookId, now), "nb-dest", 0, PageClip.Template.None, ids())!!
        assertEquals(7, plan.contentIds.size)
    }

    // ── Links across notebooks ──────

    private fun pastedLink(text: String?, dest: String): String? {
        val link = row("lnk-x", pageId, NotebookSchema.TYPE_LINK, text = text)
        val plan = PageClip.plan(PageClip.capture(pageRow, null, listOf(link), notebookId, now), dest, 0, PageClip.Template.None, ids())!!
        return plan.rows.first { it.type == NotebookSchema.TYPE_LINK }.text
    }

    private fun ownPage(pageId: String, chrome: Int = LinkPayload.CHROME_UNDERLINE) = LinkPayload.encode(chrome, LinkPayload.KIND_PAGE, null, pageId)

    @Test
    fun `a same-notebook paste leaves every link verbatim, across notebooks an own-page link is re-pointed at the source`() {
        assertEquals(ownPage("page-9"), pastedLink(ownPage("page-9"), notebookId))
        assertEquals(ownPage(pageId), pastedLink(ownPage(pageId), notebookId))
        val out = LinkPayload.decode(pastedLink(ownPage("page-9", LinkPayload.CHROME_NONE), "nb-dest")!!)!!
        assertEquals(LinkPayload.KIND_ITEM_PAGE, out.kind)
        assertEquals(notebookId, out.itemId)
        assertEquals("page-9", out.pageId)
        assertEquals(LinkPayload.CHROME_NONE, out.chrome)
    }

    @Test
    fun `a link to the page being pasted follows the copy`() {
        val link = row("lnk-self", pageId, NotebookSchema.TYPE_LINK, text = ownPage(pageId))
        val plan = PageClip.plan(PageClip.capture(pageRow, null, listOf(link), notebookId, now), "nb-dest", 0, PageClip.Template.None, ids())!!
        val out = LinkPayload.decode(plan.rows.first { it.type == NotebookSchema.TYPE_LINK }.text!!)!!
        assertEquals(LinkPayload.KIND_PAGE, out.kind)
        assertNull(out.itemId)
        assertEquals(plan.pageId, out.pageId)
    }

    @Test
    fun `item and item-page links travel unchanged, an unreadable payload verbatim, a blank source untouched`() {
        val toItem = LinkPayload.encode(LinkPayload.CHROME_NONE, LinkPayload.KIND_ITEM, "nb-other", null)
        assertEquals(toItem, pastedLink(toItem, "nb-dest"))
        val toSource = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_ITEM_PAGE, notebookId, pageId)
        assertEquals(toSource, pastedLink(toSource, "nb-dest"))
        assertEquals("L1|u|p||page-9", pastedLink("L1|u|p||page-9", "nb-dest"))
        assertEquals("", pastedLink("", "nb-dest"))
        assertNull(pastedLink(null, "nb-dest"))
        val link = row("lnk-x", pageId, NotebookSchema.TYPE_LINK, text = ownPage("page-9"))
        val plan = PageClip.plan(PageClip.capture(pageRow, null, listOf(link), "", now), "nb-dest", 0, PageClip.Template.None, ids())!!
        assertEquals(ownPage("page-9"), plan.rows.first { it.type == NotebookSchema.TYPE_LINK }.text)
    }

    // ── Template dedupe by content ──────

    private val carried = PageClip.capture(pageRow, templateRow, emptyList(), notebookId, now).rows.first { it.type == NotebookSchema.TYPE_TEMPLATE }

    private fun candidate(id: String, text: String? = "LINED", width: Float? = 1404f, height: Float? = 1872f, blob: ByteArray? = byteArrayOf(1, 2, 3, 4)) =
        row(id, "nb-dest", NotebookSchema.TYPE_TEMPLATE, text = text, width = width, height = height, blob = blob)

    @Test
    fun `the same paper under a different id matches, a different kind, size or pixel does not`() {
        assertEquals("tpl-dest", PageClip.matchTemplate(carried, listOf(candidate("tpl-dest"))))
        assertNull(PageClip.matchTemplate(carried, listOf(candidate("a", text = "DOTTED"))))
        assertNull(PageClip.matchTemplate(carried, listOf(candidate("a", width = 1080f))))
        assertNull(PageClip.matchTemplate(carried, listOf(candidate("a", blob = byteArrayOf(1, 2, 3, 5)))))
        assertNull(PageClip.matchTemplate(carried, listOf(candidate("a", blob = null))))
        assertNull(PageClip.matchTemplate(carried, emptyList()))
        assertEquals("tpl-first", PageClip.matchTemplate(carried, listOf(candidate("tpl-first"), candidate("tpl-second"))))
        assertNull(PageClip.matchTemplate(carried.copy(blob = null), listOf(candidate("a", blob = null))))
        assertNull(PageClip.matchTemplate(null, listOf(candidate("a"))))
    }
}
