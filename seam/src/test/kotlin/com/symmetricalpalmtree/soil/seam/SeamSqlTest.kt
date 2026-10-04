package com.symmetricalpalmtree.soil.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeamSqlTest {

    private fun refusedQuery(sql: String) =
        assertThrows(sql, IllegalArgumentException::class.java) { SeamSql.checkQuery(sql) }

    private fun refusedExec(sql: String) =
        assertThrows(sql, IllegalArgumentException::class.java) { SeamSql.checkExec(sql) }

    private fun refusedDdl(sql: String) =
        assertThrows(sql, IllegalArgumentException::class.java) { SeamSql.checkDdl(sql) }

    @Test
    fun `a plain query and a plain write pass`() {
        SeamSql.checkQuery("SELECT id, \"order\" FROM notebook WHERE parentId = ? ORDER BY \"order\"")
        SeamSql.checkQuery("WITH t(id) AS (SELECT id FROM notebook) SELECT id FROM t;")
        SeamSql.checkExec("INSERT OR IGNORE INTO notebook (id, type) VALUES (?, ?)")
        SeamSql.checkExec("UPDATE notebook SET deletedAt = ?2 WHERE id = ?1")
        SeamSql.checkExec("DELETE FROM notebook WHERE id = ?")
        SeamSql.checkExec(
            "WITH RECURSIVE doomed(id) AS (SELECT id FROM notebook WHERE deletedAt IS NOT NULL " +
                "UNION SELECT n.id FROM notebook n JOIN doomed d ON n.parentId = d.id) " +
                "DELETE FROM notebook WHERE id IN (SELECT id FROM doomed)",
        )
    }

    @Test
    fun `the head keyword decides the kind`() {
        refusedQuery("DELETE FROM notebook")
        refusedExec("SELECT 1")
        refusedDdl("SELECT 1")
        refusedExec("CREATE TABLE a (id TEXT)")
        refusedQuery("(SELECT 1)")
    }

    @Test
    fun `one statement only`() {
        refusedQuery("SELECT 1; SELECT 2")
        refusedExec("DELETE FROM a; DELETE FROM b")
        SeamSql.checkQuery("SELECT ';' FROM a")
        SeamSql.checkQuery("SELECT 1 -- ; not a second statement")
        SeamSql.checkQuery("SELECT 1 /* ; */")
    }

    @Test
    fun `a query cannot carry a write`() {
        refusedQuery("WITH t AS (SELECT 1) DELETE FROM notebook")
        refusedQuery("WITH t AS (SELECT 1) INSERT INTO notebook SELECT * FROM t")
        refusedQuery("WITH t AS (SELECT 1) REPLACE INTO notebook SELECT * FROM t")
        // The function, which a query may use.
        SeamSql.checkQuery("SELECT replace(text, 'a', 'b') FROM notebook")
    }

    @Test
    fun `denied words are refused anywhere and in any case`() {
        for (word in SeamSql.DENY) {
            refusedQuery("SELECT 1 FROM a WHERE $word")
            refusedExec("DELETE FROM a WHERE ${word.lowercase()}")
        }
        refusedExec("pragma user_version = 3")
        refusedExec("ATTACH DATABASE 'x' AS y")
        refusedExec("VACUUM")
    }

    @Test
    fun `a denied word inside a literal is only text`() {
        SeamSql.checkExec("UPDATE notebook SET text = 'PRAGMA; DROP TABLE notebook' WHERE id = ?")
        SeamSql.checkQuery("SELECT 'soil_meta'")
    }

    @Test
    fun `reserved names are refused bare and quoted`() {
        refusedQuery("SELECT * FROM soil_meta")
        refusedQuery("SELECT * FROM \"soil_meta\"")
        refusedQuery("SELECT * FROM [soil_meta]")
        refusedQuery("SELECT * FROM `SOIL_META`")
        refusedQuery("SELECT * FROM sqlite_master")
        refusedExec("UPDATE soil_link SET pageId = ? WHERE id = ?")
        refusedExec("DELETE FROM soil_meta WHERE key = ?")
    }

    @Test
    fun `the link mirror admits its two writes and nothing else`() {
        SeamSql.checkExec(SeamLinks.PUT)
        SeamSql.checkExec(SeamLinks.DROP)
        SeamSql.checkExec(SeamLinks.DROP_PAGE)
        assertTrue(SeamSql.writesLinkMirror(SeamLinks.PUT))
        assertTrue(SeamSql.writesLinkMirror(SeamLinks.DROP_PAGE))
        assertFalse(SeamSql.writesLinkMirror("DELETE FROM notebook WHERE id = ?"))
        assertFalse(SeamSql.writesLinkMirror("nonsense"))
        // The name is admitted at the table's place alone.
        refusedQuery(SeamLinks.READ)
        refusedExec("INSERT INTO notebook (id) SELECT id FROM soil_link")
        refusedExec("DELETE FROM notebook WHERE id IN (SELECT id FROM soil_link)")
        refusedExec("INSERT INTO soil_meta (key, value) VALUES (?, ?)")
        refusedExec("WITH x AS (SELECT 1) INSERT INTO soil_link (id, pageId, targetItemId) VALUES (?, ?, ?)")
    }

    @Test
    fun `the doors that are ordinary words are shut`() {
        refusedQuery("SELECT * FROM pragma_table_info('notebook')")
        refusedQuery("SELECT user_version FROM pragma_user_version")
        refusedQuery("SELECT sqlcipher_export('x')")
        refusedQuery("SELECT load_extension('x')")
    }

    @Test
    fun `only positional binds`() {
        refusedQuery("SELECT 1 FROM a WHERE id = :id")
        refusedQuery("SELECT 1 FROM a WHERE id = @id")
        refusedQuery("SELECT 1 FROM a WHERE id = \$id")
    }

    @Test
    fun `binds are counted`() {
        assertEquals(0, SeamSql.bindCount("SELECT 1"))
        assertEquals(3, SeamSql.bindCount("SELECT ?, ?, ?"))
        assertEquals(7, SeamSql.bindCount("SELECT ?7, ?2"))
        assertEquals(0, SeamSql.bindCount("SELECT '?'"))
        refusedQuery("SELECT ?1000")
    }

    @Test
    fun `a statement has a length`() {
        refusedQuery("")
        refusedQuery("   ")
        refusedQuery("SELECT '" + "a".repeat(SeamLimits.MAX_SQL_CHARS) + "'")
    }

    @Test
    fun `unterminated runs are refused`() {
        refusedQuery("SELECT 'a")
        refusedQuery("SELECT \"a")
        refusedQuery("SELECT 1 /* a")
    }

    @Test
    fun `DDL an app may declare`() {
        SeamSql.checkDdl("CREATE TABLE notebook (id TEXT PRIMARY KEY, \"order\" INTEGER NOT NULL)")
        SeamSql.checkDdl("CREATE TABLE IF NOT EXISTS notebook (id TEXT PRIMARY KEY)")
        SeamSql.checkDdl("CREATE INDEX idx_parent ON notebook (parentId, \"order\")")
        SeamSql.checkDdl("CREATE UNIQUE INDEX IF NOT EXISTS idx_u ON notebook (id)")
        SeamSql.checkDdl("ALTER TABLE notebook ADD COLUMN extra TEXT")
    }

    @Test
    fun `DDL an app may not`() {
        refusedDdl("DROP TABLE notebook")
        refusedDdl("CREATE VIEW v AS SELECT 1")
        refusedDdl("CREATE TRIGGER t AFTER INSERT ON notebook BEGIN SELECT 1; END")
        refusedDdl("CREATE VIRTUAL TABLE v USING fts5(a)")
        refusedDdl("CREATE TEMP TABLE t (id TEXT)")
        refusedDdl("CREATE TABLE soil_meta (id TEXT)")
        refusedDdl("CREATE TABLE \"notebook\" (id TEXT)")
        refusedDdl("CREATE TABLE Notebook (id TEXT)")
        refusedDdl("CREATE INDEX idx ON soil_meta (key)")
        refusedDdl("ALTER TABLE notebook RENAME TO other")
        refusedDdl("CREATE TABLE a (id TEXT); CREATE TABLE b (id TEXT)")
        refusedDdl("CREATE TABLE a AS SELECT * FROM sqlite_master")
    }

    @Test
    fun `a refusal never quotes a literal`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            SeamSql.checkExec("UPDATE a SET text = 'a private thought' WHERE PRAGMA")
        }
        assertTrue(!e.message.orEmpty().contains("private"))
    }

    @Test
    fun `names an app may use`() {
        assertTrue(SeamNames.isValid("notebook"))
        assertTrue(SeamNames.isValid("a_1"))
        assertTrue(!SeamNames.isValid("Notebook"))
        assertTrue(!SeamNames.isValid("1a"))
        assertTrue(!SeamNames.isValid(""))
        assertTrue(!SeamNames.isValid("a".repeat(64)))
        assertTrue(!SeamNames.isValid("soil_x"))
        assertTrue(SeamNames.isReserved("SQLCIPHER_export"))
    }
}
