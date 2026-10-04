package com.symmetricalpalmtree.soil.data

import com.symmetricalpalmtree.soil.data.index.TagSql
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every statement the tag index sends, pinned as text and binds, and run through the seam's checker. */
class TagSqlTest {

    private val t1 = "11111111-1111-4111-8111-111111111111"
    private val t2 = "22222222-2222-4222-8222-222222222222"
    private val n1 = "33333333-3333-4333-8333-333333333333"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"

    @Test
    fun `the browse order is stable`() {
        val s = TagSql.selectTags()
        assertEquals("SELECT id, display FROM tag ORDER BY identityKey, display", s.sql)
        SeamSql.checkQuery(s.sql)
    }

    @Test
    fun `one read answers existence and attachment together, binds in the SQL's order`() {
        val s = TagSql.selectTagByIdentity("reading list", n1, p1)
        assertEquals(
            "SELECT t.id, t.display, EXISTS(SELECT 1 FROM tag_assignment a WHERE a.tagId = t.id AND a.itemId = ? AND a.pageId = ?) AS attached FROM tag t WHERE t.identityKey = ?",
            s.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text(n1), Cell.Text(p1), Cell.Text("reading list")), s.args)
        SeamSql.checkQuery(s.sql)
    }

    @Test
    fun `usage counts both kinds in one row`() {
        val s = TagSql.selectUsage(t1)
        assertEquals("SELECT SUM(pageId = '') AS items, SUM(pageId <> '') AS pages FROM tag_assignment WHERE tagId = ?", s.sql)
        SeamSql.checkQuery(s.sql)
    }

    @Test
    fun `the assignments of the matched tags take one mark per id`() {
        val s = TagSql.selectAssignmentsOf(listOf(t1, t2))
        assertEquals("SELECT tagId, itemId, pageId FROM tag_assignment WHERE tagId IN (?, ?) ORDER BY tagId, itemId, pageId", s.sql)
        assertEquals(listOf<Cell>(Cell.Text(t1), Cell.Text(t2)), s.args)
        SeamSql.checkQuery(s.sql)
        assertThrows(IllegalArgumentException::class.java) { TagSql.selectAssignmentsOf(emptyList()) }
        val full = TagSql.selectAssignmentsOf(List(500) { t1 })
        assertTrue(full.args.size <= SeamLimits.MAX_ARGS)
        assertTrue(full.sql.length <= SeamLimits.MAX_SQL_CHARS)
    }

    @Test
    fun `the tag insert carries both caps inside it`() {
        val s = TagSql.insertTag(t1, "Reading List", "reading list", 99L, 5_000, 50_000)
        assertEquals(
            "INSERT OR IGNORE INTO tag (id, display, identityKey, createdAt) SELECT ?, ?, ?, ? WHERE (SELECT COUNT(*) FROM tag) < ? AND (SELECT COUNT(*) FROM tag_assignment) < ?",
            s.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text(t1), Cell.Text("Reading List"), Cell.Text("reading list"), Cell.Integer(99), Cell.Integer(5_000), Cell.Integer(50_000)), s.args)
        SeamSql.checkExec(s.sql)
    }

    @Test
    fun `the assignment resolves the tag id by identity inside the statement`() {
        val s = TagSql.insertAssignment("reading list", n1, "", 99L, 50_000)
        assertEquals(
            "INSERT OR IGNORE INTO tag_assignment (tagId, itemId, pageId, createdAt) SELECT id, ?, ?, ? FROM tag WHERE identityKey = ? AND (SELECT COUNT(*) FROM tag_assignment) < ?",
            s.sql,
        )
        // An item tag's page is the empty string, never NULL: NULL is not equal to NULL in a key.
        assertEquals(Cell.Text(""), s.args[1])
        SeamSql.checkExec(s.sql)
    }

    @Test
    fun `a detach names the whole target, and a delete takes the assignments first`() {
        val d = TagSql.deleteAssignment(t1, n1, p1)
        assertEquals("DELETE FROM tag_assignment WHERE tagId = ? AND itemId = ? AND pageId = ?", d.sql)
        SeamSql.checkExec(d.sql)
        val gone = TagSql.deleteTag(t1)
        assertEquals(2, gone.size)
        assertTrue(gone[0].sql.startsWith("DELETE FROM tag_assignment"))
        assertEquals("DELETE FROM tag WHERE id = ?", gone[1].sql)
        gone.forEach { SeamSql.checkExec(it.sql) }
    }
}
