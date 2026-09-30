package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.soil.seam.SeamSchema
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookSchemaTest {

    @Test
    fun `Soil will take the schema`() {
        val schema = NotebookSchema.SCHEMA
        SeamSchema.requireValid(schema.kind, schema.steps, schema.purge)
        assertEquals("notebook", schema.kind)
        assertEquals(1, schema.version)
    }

    @Test
    fun `the table has SN's eighteen columns in SN's order`() {
        assertEquals(18, NotebookSchema.COLUMNS.size)
        val ddl = NotebookSchema.SCHEMA.steps[0][0]
        var at = 0
        for (column in NotebookSchema.COLUMNS) {
            val found = ddl.indexOf(column, at)
            assertTrue("$column is missing or out of order", found >= at)
            at = found + column.length
        }
    }

    @Test
    fun `a notebook holds no sketches and no documents`() {
        val types = listOf(
            NotebookSchema.TYPE_NOTEBOOK, NotebookSchema.TYPE_PAGE, NotebookSchema.TYPE_TEMPLATE,
            NotebookSchema.TYPE_STROKE, NotebookSchema.TYPE_HEADING, NotebookSchema.TYPE_LINK,
            NotebookSchema.TYPE_TEXT, NotebookSchema.TYPE_SHAPE, NotebookSchema.TYPE_STICKY,
        )
        assertEquals(types.size, types.toSet().size)
        assertFalse(types.any { it.startsWith("sketch") || it.startsWith("guide") || it == "document" })
    }

    @Test
    fun `the purge is one write that spares templates and takes no binds`() {
        SeamSql.checkExec(NotebookSchema.PURGE)
        assertEquals(0, SeamSql.bindCount(NotebookSchema.PURGE))
        assertEquals(2, Regex("type != 'template'").findAll(NotebookSchema.PURGE).count())
        assertFalse(NotebookSchema.PURGE.contains("updatedAt"))
    }
}
