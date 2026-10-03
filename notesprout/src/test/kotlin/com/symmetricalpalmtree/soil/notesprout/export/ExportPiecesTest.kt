package com.symmetricalpalmtree.soil.notesprout.export

import com.symmetricalpalmtree.soil.notesprout.data.PageRef
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPiecesTest {

    private fun ref(id: String, w: Float = 100f, h: Float = 200f) = PageRef(id, w, h, "")

    @Test fun `a plan keeps order and numbers and narrows to the asked pages`() {
        val all = listOf(ref("a"), ref("b"), ref("c"))
        val whole = RenderPlan.of(all, emptyList()) as RenderPlan.Outcome.Ready
        assertEquals(listOf(1, 2, 3), whole.pages.map { it.number })
        val one = RenderPlan.of(all, listOf("c")) as RenderPlan.Outcome.Ready
        assertEquals(1, one.pages.size); assertEquals(3, one.pages[0].number); assertEquals(200, one.pages[0].heightPx)
        assertTrue(RenderPlan.of(all, listOf("zzz")) is RenderPlan.Outcome.Empty)
        assertTrue(RenderPlan.of(listOf(ref("a", 0f)), emptyList()) is RenderPlan.Outcome.Damaged)
        assertTrue(RenderPlan.of(emptyList(), emptyList()) is RenderPlan.Outcome.Empty)
    }

    @Test fun `endnotes number from one after the pages and link both ways`() {
        val s = Endnotes.Source("st", fromPage = 2, fromPageLabel = 5, iconL = 10f, iconT = 20f, iconR = 50f, iconB = 60f, contentW = 300, contentH = 400, pageW = 1000, pageH = 2000)
        val plan = Endnotes.plan(listOf(s), pageCount = 3)
        assertEquals(1, plan.notes.size)
        val note = plan.notes[0]
        assertEquals(4, note.page); assertEquals(300, note.widthPx); assertEquals(400 + Endnotes.CAPTION_PX, note.heightPx)
        assertEquals(2, plan.links.size)
        assertEquals(4, plan.links[0].toPage); assertEquals(2, plan.links[1].toPage)
        assertEquals("Note 1 — from page 5", Endnotes.caption(1, 5))
        assertThrows(IllegalArgumentException::class.java) { Endnotes.plan(listOf(s), pageCount = 1) }
        val fallback = Endnotes.contentSize(Endnotes.Source("x", 1, 1, 0f, 0f, 0f, 0f, 0, 0, 1000, 2000))
        assertEquals(1000 to 2000, fallback)
    }

    @Test fun `a page's title is its topmost heading, prefix stripped`() {
        val top = Heading(id = "1", text = "# Plans", level = 1, x = 50f, y = 10f, width = 10f, height = 10f, order = 0)
        val lower = Heading(id = "2", text = "Later", level = 1, x = 0f, y = 100f, width = 10f, height = 10f, order = 1)
        assertEquals("Plans", PageLabels.titleOf(listOf(lower, top)))
        assertNull(PageLabels.titleOf(emptyList()))
    }

    @Test fun `relabel statements name every row that carries the id`() {
        val s = Relabel.statements("old-id", "new-id")
        assertEquals(3, s.size)
        assertTrue(s[0].contains("SET id = 'new-id' WHERE id = 'old-id'"))
        assertTrue(s[1].contains("parentId"))
        assertTrue(s[2].contains("replace(text, 'old-id', 'new-id')"))
        assertThrows(IllegalArgumentException::class.java) { Relabel.statements("a", "a") }
        assertThrows(IllegalArgumentException::class.java) { Relabel.statements("a'b", "c") }
    }
}
