package com.symmetricalpalmtree.soil.docsprout.data

import com.symmetricalpalmtree.soil.seam.SeamSchema
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSchemaTest {

    @Test
    fun `Soil will take the schema`() {
        val schema = DocumentSchema.SCHEMA
        SeamSchema.requireValid(schema.kind, schema.steps, schema.purge)
        assertEquals("document", schema.kind)
        assertEquals(1, schema.version)
    }

    @Test
    fun `the table has its columns in order`() {
        val ddl = DocumentSchema.SCHEMA.steps[0][0]
        var at = 0
        for (column in DocumentSchema.COLUMNS) {
            val found = ddl.indexOf(column, at)
            assertTrue("$column is missing or out of order", found >= at)
            at = found + column.length
        }
    }

    @Test
    fun `the purge is one write that takes no binds and rewrites nothing`() {
        SeamSql.checkExec(DocumentSchema.PURGE)
        assertEquals(0, SeamSql.bindCount(DocumentSchema.PURGE))
        assertFalse(DocumentSchema.PURGE.contains("updatedAt"))
    }
}
