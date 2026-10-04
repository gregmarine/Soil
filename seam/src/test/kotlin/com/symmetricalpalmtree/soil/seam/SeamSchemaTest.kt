package com.symmetricalpalmtree.soil.seam

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamSchemaTest {

    private val table = "CREATE TABLE notebook (id TEXT PRIMARY KEY, deletedAt INTEGER)"

    private fun refused(kind: String = "notebook", steps: List<List<String>> = listOf(listOf(table)), purge: List<String> = emptyList()) =
        assertThrows(IllegalArgumentException::class.java) { SeamSchema.requireValid(kind, steps, purge) }

    @Test
    fun `a schema of steps and a purge is valid`() {
        SeamSchema.requireValid(
            "notebook",
            listOf(listOf(table), listOf("ALTER TABLE notebook ADD COLUMN extra TEXT")),
            listOf("DELETE FROM notebook WHERE deletedAt IS NOT NULL"),
        )
    }

    @Test
    fun `a kind is a short lowercase word`() {
        assertTrue(SeamSchema.isValidKind("notebook"))
        refused(kind = "")
        refused(kind = "Notebook")
        refused(kind = "note book")
        refused(kind = "../x")
    }

    @Test
    fun `steps are counted and never empty`() {
        refused(steps = emptyList())
        refused(steps = listOf(emptyList()))
        refused(steps = List(SeamLimits.MAX_SCHEMA_STEPS + 1) { listOf(table) })
    }

    @Test
    fun `a step holds DDL only, and the refusal names the step`() {
        val e = refused(steps = listOf(listOf(table), listOf(table, "DELETE FROM notebook")))
        assertTrue(e.message.orEmpty().startsWith("step 2 statement 2"))
    }

    @Test
    fun `a purge is a write with no binds`() {
        refused(purge = listOf("SELECT 1"))
        refused(purge = listOf("DELETE FROM notebook WHERE id = ?"))
        refused(purge = listOf("DROP TABLE notebook"))
        refused(purge = List(SeamLimits.MAX_PURGE_STATEMENTS + 1) { "DELETE FROM notebook" })
    }
}
