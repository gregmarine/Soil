package com.symmetricalpalmtree.soil.data

import com.symmetricalpalmtree.soil.data.index.IndexSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaTest {

    private val three = Schema("three", listOf(listOf("a"), listOf("b", "c"), listOf("d")))

    @Test
    fun theVersionIsTheNumberOfSteps() {
        assertEquals(3, three.version)
    }

    @Test
    fun aNewFileRunsEveryStep_inOrder() {
        assertEquals(
            listOf(1 to listOf("a"), 2 to listOf("b", "c"), 3 to listOf("d")),
            three.pending(0),
        )
    }

    @Test
    fun anOlderFileRunsOnlyWhatItLacks() {
        assertEquals(listOf(3 to listOf("d")), three.pending(2))
    }

    @Test
    fun aCurrentFileRunsNothing() {
        assertTrue(three.pending(3).isEmpty())
    }

    @Test
    fun aFileFromALaterBuildIsRefused() {
        assertThrows(IllegalStateException::class.java) { three.pending(4) }
    }

    @Test
    fun aSchemaWithNoStepsOrAnEmptyStepIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { Schema("none", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { Schema("hollow", listOf(listOf("a"), emptyList())) }
    }

    @Test
    fun theIndexIsAtVersionFour_withSoftDeletesStableIdsAPageCountAnOpenedStampAndLinks() {
        assertEquals(4, IndexSchema.SCHEMA.version)
        val links = IndexSchema.SCHEMA.steps[3].joinToString("\n")
        assertTrue(links.contains("CREATE TABLE link"))
        assertTrue(links.contains("targetItemId TEXT NOT NULL"))
        assertTrue(links.contains("CREATE INDEX link_target ON link(targetItemId)"))
        assertTrue(IndexSchema.SCHEMA.steps[2].single().contains("ADD COLUMN openedAt INTEGER"))
        assertTrue(IndexSchema.SCHEMA.steps[1].single().contains("ADD COLUMN pageCount INTEGER NOT NULL DEFAULT 0"))
        val ddl = IndexSchema.SCHEMA.steps[0].joinToString("\n")
        assertTrue(ddl.contains("CREATE TABLE item"))
        assertTrue(ddl.contains("id TEXT PRIMARY KEY"))
        assertTrue(ddl.contains("deletedAt INTEGER"))
        assertTrue(ddl.contains("keyScope TEXT NOT NULL DEFAULT 'GLOBAL'"))
        assertTrue(ddl.contains("CREATE TABLE meta"))
    }
}
