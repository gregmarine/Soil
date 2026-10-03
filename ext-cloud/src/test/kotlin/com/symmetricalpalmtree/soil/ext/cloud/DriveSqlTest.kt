package com.symmetricalpalmtree.soil.ext.cloud

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.ext.CloudContract
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every statement the Drive provider sends against `account`, pinned as exact text and arguments
 * — Soil's validator is run over each one, so a shape it would refuse fails here
 * rather than on the device.
 */
class DriveSqlTest {

    @Test
    fun selectValue_readsOneKey() {
        val s = DriveSql.selectValue(DriveSql.Keys.REFRESH_TOKEN)
        assertEquals("SELECT value FROM account WHERE key = ?", s.sql)
        assertEquals(listOf<Cell>(Cell.Text(DriveSql.Keys.REFRESH_TOKEN)), s.args)
        SeamSql.checkQuery(s.sql)
    }

    @Test
    fun upsertValue_replacesByKey() {
        val s = DriveSql.upsertValue(DriveSql.Keys.ACCOUNT_LABEL, "person@example.com")
        assertEquals("INSERT OR REPLACE INTO account (key, value) VALUES (?, ?)", s.sql)
        assertEquals(
            listOf<Cell>(Cell.Text(DriveSql.Keys.ACCOUNT_LABEL), Cell.Text("person@example.com")),
            s.args,
        )
        SeamSql.checkExec(s.sql)
    }

    @Test
    fun deleteValue_forgetsOneKey() {
        val s = DriveSql.deleteValue(DriveSql.Keys.ROOT_FOLDER_ID)
        assertEquals("DELETE FROM account WHERE key = ?", s.sql)
        assertEquals(listOf<Cell>(Cell.Text(DriveSql.Keys.ROOT_FOLDER_ID)), s.args)
        SeamSql.checkExec(s.sql)
    }

    @Test
    fun deleteAll_forgetsTheWholeAccount() {
        val s = DriveSql.deleteAll()
        assertEquals("DELETE FROM account", s.sql)
        assertEquals(emptyList<Cell>(), s.args)
        SeamSql.checkExec(s.sql)
    }

    @Test
    fun keys_areDistinct() {
        val keys = setOf(DriveSql.Keys.REFRESH_TOKEN, DriveSql.Keys.ACCOUNT_LABEL, DriveSql.Keys.ROOT_FOLDER_ID)
        assertEquals(3, keys.size)
    }

    // ── The table Soil makes ───────────────────────────────────────────────

    /** The contract's DDL is what the lease runs: it must pass the seam's checker and name the
     *  two columns the statements above use. */
    @Test
    fun theTableIsOneStatementSoilCanRun() {
        SeamSql.checkDdl(CloudContract.STORE_CREATE)
        assertTrue(CloudContract.STORE_CREATE.startsWith("CREATE TABLE IF NOT EXISTS account"))
        assertTrue("the key column", "key TEXT PRIMARY KEY" in CloudContract.STORE_CREATE)
        assertTrue("the value column", "value TEXT NOT NULL" in CloudContract.STORE_CREATE)
    }
}
