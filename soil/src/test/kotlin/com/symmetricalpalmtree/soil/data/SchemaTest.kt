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
    fun theIndexIsAtVersionSeven_withSoftDeletesStableIdsAPageCountAnOpenedStampLinksTemplatesFoldersClipboardTagsAndPages() {
        assertEquals(8, IndexSchema.SCHEMA.version)
        val held = IndexSchema.SCHEMA.steps[6].joinToString("\n")
        assertTrue(held.contains("CREATE TABLE clipboard ("))
        assertTrue(held.contains("kind TEXT PRIMARY KEY"))
        assertTrue(held.contains("CREATE TABLE tag ("))
        assertTrue(held.contains("identityKey TEXT NOT NULL UNIQUE"))
        assertTrue(held.contains("CREATE TABLE tag_assignment ("))
        assertTrue(held.contains("PRIMARY KEY (tagId, itemId, pageId)"))
        assertTrue(held.contains("CREATE TABLE item_page ("))
        val library = IndexSchema.SCHEMA.steps[5].joinToString("\n")
        assertTrue(library.contains("ADD COLUMN parentId TEXT NOT NULL DEFAULT ''"))
        assertTrue(library.contains("ADD COLUMN cover BLOB"))
        assertTrue(library.contains("CREATE TABLE folder ("))
        assertTrue(library.contains("CREATE TABLE item_pin ("))
        assertTrue(library.contains("CREATE TABLE folder_prefs ("))
        val templates = IndexSchema.SCHEMA.steps[4].joinToString("\n")
        assertTrue(templates.contains("CREATE TABLE template ("))
        assertTrue(templates.contains("CREATE TABLE template_folder ("))
        assertTrue(templates.contains("CREATE TABLE template_pin ("))
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
