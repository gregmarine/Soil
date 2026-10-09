package com.symmetricalpalmtree.soil.sketchsprout.clip

import com.symmetricalpalmtree.soil.sketchsprout.data.SketchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchPageClipTest {

    private val template = SketchRow("t1", "sb", "template", 0, text = "LINED", width = 1404f, height = 1872f, blob = byteArrayOf(9, 9))
    private val page = SketchRow("p1", "sb", "page", 2, refId = "t1", width = 1404f, height = 1872f)
    private val graphite = SketchRow("g1", "p1", "sketch_graphite", -1, blob = ByteArray(300) { it.toByte() })
    private val grid = SketchRow("q1", "p1", "guide_grid", -1, text = "{\"kind\":\"LINES\",\"count\":4}")

    @Test
    fun `a page round-trips with its paper and everything under it, in insert order`() {
        val bytes = SketchPageClip.encode(page, template, listOf(graphite, grid))
        val rows = SketchPageClip.decode(bytes)!!
        assertEquals(listOf(template, page, graphite, grid), rows)
    }

    @Test
    fun `bytes without a page row are no clipboard`() {
        assertNull(SketchPageClip.decode(null))
        assertNull(SketchPageClip.decode(byteArrayOf(1, 2, 3)))
        assertNull(SketchPageClip.decode(SketchPageClip.encode(graphite, null, emptyList())))
    }

    @Test
    fun `a paste re-ids the page and its children, reusing paper the target already holds`() {
        val rows = listOf(template, page, graphite, grid)
        var n = 0
        val plan = SketchPageClip.plan(rows, "target", 5, template = { SketchPageClip.Template.Reuse("t-mine") }, newId = { "n${n++}" })!!
        assertEquals("target", plan.page.parentId)
        assertEquals(5, plan.page.order)
        assertEquals("t-mine", plan.page.refId)
        assertTrue(plan.rows.none { it.type == "template" })
        val children = plan.rows.filter { it.parentId == plan.page.id }
        assertEquals(listOf("sketch_graphite", "guide_grid"), children.map { it.type })
        assertTrue(children.none { it.id == "g1" || it.id == "q1" })
        assertTrue(graphite.blob!!.contentEquals(children[0].blob))
    }

    @Test
    fun `a paste inserts the carried paper when the target has none like it`() {
        val plan = SketchPageClip.plan(listOf(template, page, graphite), "target", 0, template = { SketchPageClip.Template.Insert("t-new") }, newId = { "x" })!!
        val inserted = plan.rows.first { it.type == "template" }
        assertEquals("t-new", inserted.id)
        assertEquals("target", inserted.parentId)
        assertEquals("t-new", plan.page.refId)
    }

    @Test
    fun `a page with no paper pastes blank`() {
        val bare = page.copy(refId = "")
        val plan = SketchPageClip.plan(listOf(bare, graphite), "target", 0, template = { SketchPageClip.Template.None }, newId = { "x" })!!
        assertEquals("", plan.page.refId)
        assertEquals(2, plan.rows.size)
    }
}
