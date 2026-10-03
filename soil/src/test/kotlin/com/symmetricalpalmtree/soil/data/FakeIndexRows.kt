package com.symmetricalpalmtree.soil.data

import com.symmetricalpalmtree.soil.data.index.TagSql
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows
import com.symmetricalpalmtree.soil.seam.TagRules

/**
 * The index's test double for the tag tables: there is no SQLite on the JVM. A statement
 * recorder and a canned-row responder over a tiny picture of `tag` and `tag_assignment`, with
 * the **writes applied**, minimally and literally: `OR IGNORE`, the identity resolution inside
 * [TagSql.insertAssignment], and both `COUNT` caps. `assign`'s shape rests on a post-write
 * re-read seeing the write, so a fake that only recorded could not exercise it. Real SQL is
 * proved on the Nomad.
 */
class FakeIndexRows : RowStore {

    class Tag(val id: String, val display: String)

    /** `tag` rows by identity: first writer wins, which is the UNIQUE index. */
    val tags = LinkedHashMap<String, Tag>()

    /** `tag_assignment` rows as their key: (tagId, itemId, pageId). */
    val assignments = LinkedHashSet<Triple<String, String, String>>()

    val execs = ArrayList<List<Statement>>()
    val queries = ArrayList<Statement>()
    var failWith: (() -> Throwable)? = null

    fun tag(id: String, display: String): Tag = Tag(id, display).also { tags[TagRules.identityKey(display)] = it }
    fun assign(tagId: String, itemId: String, pageId: String = "") { assignments += Triple(tagId, itemId, pageId) }

    private fun text(c: Cell) = (c as Cell.Text).value
    private fun long(c: Cell) = (c as Cell.Integer).value

    override fun exec(statements: List<Statement>): LongArray {
        failWith?.let { throw it() }
        execs += statements
        return LongArray(statements.size) { i -> apply(statements[i]) }
    }

    private fun apply(s: Statement): Long {
        val sql = s.sql
        return when {
            sql.startsWith("INSERT OR IGNORE INTO tag (") -> {
                val id = s.args[0]; val display = s.args[1]; val key = s.args[2]; val maxTags = s.args[4]; val maxAssignments = s.args[5]
                if (tags.size >= long(maxTags) || assignments.size >= long(maxAssignments)) return 0
                if (text(key) in tags) return 0
                tags[text(key)] = Tag(text(id), text(display))
                1
            }
            sql.startsWith("INSERT OR IGNORE INTO tag_assignment (") -> {
                val (itemId, pageId, _, key, maxAssignments) = s.args
                val tag = tags[text(key)] ?: return 0
                if (assignments.size >= long(maxAssignments)) return 0
                if (assignments.add(Triple(tag.id, text(itemId), text(pageId)))) 1 else 0
            }
            sql.startsWith("DELETE FROM tag_assignment WHERE tagId = ? AND itemId") -> {
                if (assignments.remove(Triple(text(s.args[0]), text(s.args[1]), text(s.args[2])))) 1 else 0
            }
            sql.startsWith("DELETE FROM tag_assignment WHERE tagId = ?") -> {
                val id = text(s.args[0])
                val before = assignments.size
                assignments.removeAll { it.first == id }
                (before - assignments.size).toLong()
            }
            sql.startsWith("DELETE FROM tag WHERE id = ?") -> {
                val id = text(s.args[0])
                val key = tags.entries.firstOrNull { it.value.id == id }?.key ?: return 0
                tags.remove(key)
                1
            }
            else -> 1
        }
    }

    override fun query(statement: Statement): StoreRows {
        failWith?.let { throw it() }
        queries += statement
        val sql = statement.sql
        return when {
            sql.startsWith("SELECT id, display FROM tag") -> StoreRows(
                listOf("id", "display"),
                tags.values.sortedWith(compareBy({ TagRules.identityKey(it.display) }, { it.display })).map { listOf<Cell>(Cell.Text(it.id), Cell.Text(it.display)) },
            )
            sql.startsWith("SELECT t.id, t.display, EXISTS") -> {
                val itemId = text(statement.args[0]); val pageId = text(statement.args[1]); val key = text(statement.args[2])
                val tag = tags[key]
                StoreRows(
                    listOf("id", "display", "attached"),
                    listOfNotNull(tag?.let { listOf<Cell>(Cell.Text(it.id), Cell.Text(it.display), Cell.Integer(if (Triple(it.id, itemId, pageId) in assignments) 1L else 0L)) }),
                )
            }
            sql.startsWith("SELECT SUM(pageId = '')") -> {
                val id = text(statement.args[0])
                val mine = assignments.filter { it.first == id }
                val items = mine.count { it.third.isEmpty() }.toLong(); val pages = mine.count { it.third.isNotEmpty() }.toLong()
                StoreRows(listOf("items", "pages"), listOf(listOf(if (mine.isEmpty()) Cell.Null else Cell.Integer(items), if (mine.isEmpty()) Cell.Null else Cell.Integer(pages))))
            }
            sql.startsWith("SELECT tagId, itemId, pageId FROM tag_assignment WHERE itemId = ?") -> {
                val itemId = text(statement.args[0])
                rows(assignments.filter { it.second == itemId }.sortedWith(compareBy({ it.first }, { it.third })))
            }
            sql.startsWith("SELECT tagId, itemId, pageId FROM tag_assignment WHERE tagId IN") -> {
                val ids = statement.args.map { text(it) }.toSet()
                rows(assignments.filter { it.first in ids }.sortedWith(compareBy({ it.first }, { it.second }, { it.third })))
            }
            else -> throw IllegalArgumentException("unexpected query: $sql")
        }
    }

    private fun rows(list: List<Triple<String, String, String>>) = StoreRows(
        listOf("tagId", "itemId", "pageId"),
        list.map { listOf<Cell>(Cell.Text(it.first), Cell.Text(it.second), Cell.Text(it.third)) },
    )
}
