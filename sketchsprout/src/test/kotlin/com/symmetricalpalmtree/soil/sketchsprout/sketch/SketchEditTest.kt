package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchPage
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What one history entry costs: the only thing standing between a sitting of sketching and a
 *  dead process, since the stack's byte budget evicts against it. */
class SketchEditTest {

    private fun tile(w: Int, h: Int) = RasterTile(0, 0, w, h, IntArray(w * h))
    private fun page(id: String) = SketchPage(id, 10f, 10f, "")

    @Test
    fun `an entry costs four bytes a pixel, summed over its tiles`() {
        val edit = SketchEdit.RasterChanged("p1", 0, RasterLayer.GRAPHITE, listOf(tile(64, 64), tile(64, 64), tile(10, 4)))
        assertEquals((64 * 64 + 64 * 64 + 10 * 4) * 4L, edit.bytes)
        assertEquals(0L, SketchEdit.RasterChanged("p1", 0, RasterLayer.GRAPHITE, emptyList()).bytes)
    }

    @Test
    fun `the budget holds several page-wide entries on a real page`() {
        val page = 1404L * 1872L * 4L
        assertTrue(SketchEdit.UNDO_BUDGET_BYTES > page * 4)
    }

    @Test
    fun `an entry re-indexed keeps its key, its raster, its pixels and its cost`() {
        val tiles = listOf(tile(64, 64), tile(8, 8))
        val edit = SketchEdit.RasterChanged("p1", 3, RasterLayer.INK, tiles)
        val moved = edit.withIndex(4)
        assertEquals(4, moved.pageIndex)
        assertEquals("p1", moved.pageKey)
        assertEquals(RasterLayer.INK, moved.layer)
        assertEquals(edit.bytes, moved.bytes)
        assertSame(tiles, moved.tiles)
        assertSame(edit, edit.withIndex(3))
    }

    @Test
    fun `an entry on one raster costs what the same entry on the other does`() {
        val tiles = listOf(tile(64, 64), tile(64, 64))
        assertEquals(
            SketchEdit.RasterChanged("p1", 0, RasterLayer.GRAPHITE, tiles).bytes,
            SketchEdit.RasterChanged("p1", 0, RasterLayer.INK, tiles).bytes,
        )
    }

    @Test
    fun `a page entry holds both sides of the file and costs nothing`() {
        val before = listOf(page("a"), page("b"))
        val after = listOf(page("a"), page("b"), page("c"))
        val e = SketchEdit.PagesChanged(SketchEdit.PagesChanged.Kind.INSERTED, "c", 2, before, after, emptyList(), "b", "c")
        assertEquals(0L, e.bytes)
        assertEquals("c", e.pageKey)
        assertEquals(2, e.pageIndex)
        val d = SketchEdit.PagesChanged(SketchEdit.PagesChanged.Kind.DELETED, "b", 1, after, before, listOf("r1", "r2"), "b", "a")
        assertEquals(listOf("r1", "r2"), d.takenIds)
        assertEquals(0L, d.bytes)
    }

    @Test
    fun `a re-papering costs nothing and keeps both rows through a re-index`() {
        val e = SketchEdit.TemplateChanged("p1", 2, "", "t9")
        assertEquals(0L, e.bytes)
        assertSame(e, e.withIndex(2))
        assertEquals("t9", e.withIndex(3).to)
        assertEquals("", e.withIndex(3).from)
    }

    @Test
    fun `a page entry re-indexed keeps everything but the index`() {
        val before = listOf(page("a"))
        val after = listOf(page("a"), page("c"))
        val e: SketchEdit = SketchEdit.PagesChanged(SketchEdit.PagesChanged.Kind.INSERTED, "c", 1, before, after, emptyList(), "a", "c")
        val moved = e.withIndex(2)
        assertTrue(moved is SketchEdit.PagesChanged)
        assertEquals(2, moved.pageIndex)
        assertEquals(after, (moved as SketchEdit.PagesChanged).after)
        assertSame(e, e.withIndex(1))
    }
}
