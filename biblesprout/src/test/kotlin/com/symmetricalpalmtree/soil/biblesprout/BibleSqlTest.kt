package com.symmetricalpalmtree.soil.biblesprout

import com.symmetricalpalmtree.soil.bibleref.*

import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader's SQL, pinned as exact text. `SeamSchema` validates DDL at construction, so
 * constructing [BibleSchema.SCHEMA] is itself a check that Soil's checker would accept it; the
 * statements are run through the seam's query/exec gate here so a shape it refuses fails on the
 * JVM, never at the seam.
 */
class BibleSqlTest {

    @Test
    fun selectState_isPinned() {
        assertEquals("SELECT value FROM state WHERE key = ?", BibleSql.SELECT_STATE)
    }

    @Test
    fun upsertState_isPinned() {
        assertEquals("INSERT OR REPLACE INTO state(key, value) VALUES (?, ?)", BibleSql.UPSERT_STATE)
    }

    @Test
    fun keyPosition_isPinned() {
        assertEquals("position", BibleSql.KEY_POSITION)
    }

    @Test
    fun recentStatements_arePinned() {
        assertEquals("SELECT usfm, chapter, at FROM recent ORDER BY at DESC LIMIT ?", BibleSql.SELECT_RECENTS)
        assertEquals("INSERT OR REPLACE INTO recent(usfm, chapter, at) VALUES (?, ?, ?)", BibleSql.UPSERT_RECENT)
        assertEquals(
            "DELETE FROM recent WHERE rowid NOT IN (SELECT rowid FROM recent ORDER BY at DESC LIMIT ?)",
            BibleSql.TRIM_RECENTS,
        )
    }

    @Test
    fun `reference statements are pinned`() {
        assertEquals("SELECT ref, at FROM recent_ref ORDER BY at DESC LIMIT ?", BibleSql.SELECT_RECENT_REFS)
        assertEquals("INSERT OR REPLACE INTO recent_ref(ref, at) VALUES (?, ?)", BibleSql.UPSERT_RECENT_REF)
        assertEquals(
            "DELETE FROM recent_ref WHERE rowid NOT IN " +
                "(SELECT rowid FROM recent_ref ORDER BY at DESC LIMIT ?)",
            BibleSql.TRIM_RECENT_REFS,
        )
    }

    /** Soil gates every statement by kind; each of ours must pass the gate it is sent through. */
    @Test
    fun everyStatementPassesTheSeamGate() {
        SeamSql.checkQuery(BibleSql.SELECT_STATE)
        SeamSql.checkQuery(BibleSql.SELECT_RECENTS)
        SeamSql.checkQuery(BibleSql.SELECT_RECENT_REFS)
        SeamSql.checkExec(BibleSql.UPSERT_STATE)
        SeamSql.checkExec(BibleSql.UPSERT_RECENT)
        SeamSql.checkExec(BibleSql.TRIM_RECENTS)
        SeamSql.checkExec(BibleSql.UPSERT_RECENT_REF)
        SeamSql.checkExec(BibleSql.TRIM_RECENT_REFS)
    }

    /** The three steps, as they landed in SN: a landed step is never edited. */
    @Test
    fun `the schema is three steps of one statement each`() {
        assertEquals("biblesprout", BibleSchema.KIND)
        assertEquals(3, BibleSchema.SCHEMA.version)
        assertEquals(listOf(BibleSchema.STATE_STEP, BibleSchema.RECENT_STEP, BibleSchema.RECENT_REF_STEP), BibleSchema.SCHEMA.steps)
        val state = BibleSchema.STATE_STEP.single()
        assertTrue(state.startsWith("CREATE TABLE state"))
        assertTrue("key TEXT PRIMARY KEY" in state)
        assertTrue("value TEXT NOT NULL" in state)
        val recent = BibleSchema.RECENT_STEP.single()
        assertTrue(recent.startsWith("CREATE TABLE recent "))
        assertTrue("PRIMARY KEY (usfm, chapter)" in recent)
        assertEquals("CREATE TABLE recent_ref (ref TEXT PRIMARY KEY, at INTEGER NOT NULL)", BibleSchema.RECENT_REF_STEP.single())
        assertTrue(BibleSchema.SCHEMA.purge.isEmpty())
    }
}
