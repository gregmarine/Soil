package com.symmetricalpalmtree.soil.sketchsprout.data

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchbookSqlTest {

    private val picture = byteArrayOf(1, 2, 3)

    private val writes = listOf(
        SketchbookSql.insertRoot("sb", "Sketches", 10L),
        SketchbookSql.setLastOpened("sb", "p1", 10L),
        SketchbookSql.setTitle("sb", "Sketches", 10L),
        SketchbookSql.insertPage("p1", "sb", 0, 1404f, 1872f, "", 10L),
        SketchbookSql.setOrder("p1", 2, 10L),
        SketchbookSql.softDelete("p1", 10L),
        SketchbookSql.restore("p1"),
        SketchbookSql.insertRaster("r1", "p1", SketchbookSchema.TYPE_SKETCH_GRAPHITE, picture, 10L),
        SketchbookSql.updateRaster("r1", picture, 11L),
        SketchbookSql.insertTemplate("t1", "sb", "LINED", 1404, 1872, picture, 10L),
        SketchbookSql.setPageTemplate("p1", "t1", 10L),
        SketchbookSql.insertGuide("g1", "p1", SketchbookSchema.TYPE_GUIDE_GRID, "{}", null, 10L),
        SketchbookSql.insertGuide("g2", "p1", SketchbookSchema.TYPE_GUIDE_IMAGE, "{}", picture, 10L),
        SketchbookSql.updateGuide("g2", "{}", picture, 11L),
        SketchbookSql.updateGuideText("g1", "{}", 11L),
    )

    private val reads = listOf(
        SketchbookSql.selectRoot("sb"),
        SketchbookSql.selectPages("sb"),
        SketchbookSql.selectRaster("p1", SketchbookSchema.TYPE_SKETCH_INK),
        SketchbookSql.selectRasterId("p1", SketchbookSchema.TYPE_SKETCH_INK),
        SketchbookSql.selectLiveDescendantIds("p1"),
        SketchbookSql.selectTemplateDigests("sb"),
        SketchbookSql.selectTemplateBlob("t1"),
        SketchbookSql.selectGuide("p1", SketchbookSchema.TYPE_GUIDE_GRID),
    )

    @Test
    fun `every statement passes the seam's checker with its binds matched`() {
        for (s in writes) {
            SeamSql.checkExec(s.sql)
            assertEquals(s.sql, SeamSql.bindCount(s.sql), s.args.size)
        }
        for (s in reads) {
            SeamSql.checkQuery(s.sql)
            assertEquals(s.sql, SeamSql.bindCount(s.sql), s.args.size)
        }
    }

    @Test
    fun `order is always quoted`() {
        for (s in writes + reads) assertFalse(s.sql, Regex("[^\"]order[^\"]").containsMatchIn(s.sql.replace("\"order\"", "")))
    }

    @Test
    fun `a page row and a raster row are never replaced`() {
        assertTrue(SketchbookSql.insertPage("p", "sb", 0, 1f, 1f, "", 0L).sql.startsWith("INSERT OR IGNORE"))
        assertTrue(SketchbookSql.insertRaster("r", "p", SketchbookSchema.TYPE_SKETCH_INK, picture, 0L).sql.startsWith("INSERT OR IGNORE"))
        assertFalse(writes.any { it.sql.contains("OR REPLACE") })
    }

    @Test
    fun `a raster is inserted out of the marks' stacking space and replaced in place`() {
        val insert = SketchbookSql.insertRaster("r1", "p1", SketchbookSchema.TYPE_SKETCH_GRAPHITE, picture, 10L)
        assertEquals(Cell.Text("r1"), insert.args[0])
        assertEquals(Cell.Text("p1"), insert.args[1])
        assertEquals(Cell.Text("sketch_graphite"), insert.args[2])
        assertEquals(Cell.Integer(-1L), insert.args[3])
        assertTrue(insert.args[6] is Cell.Blob)
        val update = SketchbookSql.updateRaster("r1", picture, 11L)
        assertTrue(update.sql.startsWith("UPDATE"))
        assertTrue(update.sql.contains("blob = ?"))
        assertTrue(update.sql.contains("updatedAt = ?"))
        assertFalse(update.sql.contains("createdAt"))
        assertEquals(Cell.Text("r1"), update.args[2])
    }

    @Test
    fun `a raster read names its layer by row type and only live rows`() {
        val s = SketchbookSql.selectRaster("p1", SketchbookSchema.TYPE_SKETCH_INK)
        assertTrue(s.sql.contains("deletedAt IS NULL"))
        assertEquals(listOf<Cell>(Cell.Text("p1"), Cell.Text("sketch_ink")), s.args)
    }

    @Test
    fun `housekeeping never bumps updatedAt`() {
        assertFalse(SketchbookSql.softDelete("x", 1L).sql.contains("updatedAt"))
        assertFalse(SketchbookSql.restore("x").sql.contains("updatedAt"))
    }
}
