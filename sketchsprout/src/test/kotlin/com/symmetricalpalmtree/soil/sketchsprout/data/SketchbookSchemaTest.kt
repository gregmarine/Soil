package com.symmetricalpalmtree.soil.sketchsprout.data

import com.symmetricalpalmtree.soil.seam.SeamSchema
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchbookSchemaTest {

    @Test
    fun `Soil will take the schema`() {
        val schema = SketchbookSchema.SCHEMA
        SeamSchema.requireValid(schema.kind, schema.steps, schema.purge)
        assertEquals("sketchbook", schema.kind)
        assertEquals(1, schema.version)
    }

    @Test
    fun `the table has the notebook table's eighteen columns in its order`() {
        assertEquals(18, SketchbookSchema.COLUMNS.size)
        val ddl = SketchbookSchema.SCHEMA.steps[0][0]
        var at = 0
        for (column in SketchbookSchema.COLUMNS) {
            val found = ddl.indexOf(column, at)
            assertTrue("$column is missing or out of order", found >= at)
            at = found + column.length
        }
    }

    @Test
    fun `a sketchbook holds rasters and guides, and no strokes`() {
        val types = listOf(
            SketchbookSchema.TYPE_SKETCHBOOK, SketchbookSchema.TYPE_PAGE, SketchbookSchema.TYPE_TEMPLATE,
            SketchbookSchema.TYPE_SKETCH_GRAPHITE, SketchbookSchema.TYPE_SKETCH_INK,
            SketchbookSchema.TYPE_GUIDE_GRID, SketchbookSchema.TYPE_GUIDE_IMAGE,
        )
        assertEquals(types.size, types.toSet().size)
        assertFalse(types.any { it == "stroke" || it == "sketch" || it == "document" })
        assertEquals(-1, SketchbookSchema.SKETCH_ORDER)
    }

    @Test
    fun `the purge is one write that spares templates and takes no binds`() {
        SeamSql.checkExec(SketchbookSchema.PURGE)
        assertEquals(0, SeamSql.bindCount(SketchbookSchema.PURGE))
        assertEquals(2, Regex("type != 'template'").findAll(SketchbookSchema.PURGE).count())
        assertFalse(SketchbookSchema.PURGE.contains("updatedAt"))
    }
}
