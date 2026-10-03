package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows

/**
 * A statement recorder and a canned-row responder: there is no SQLite on the JVM. Writes are
 * recorded, each `exec` as its own transaction; the reads the store makes are answered from a
 * small picture of the table the test sets up. Real SQL is proved on the Nomad.
 */
class FakeNotebookRows : RowStore {

    class Page(val id: String, val width: Float, val height: Float, val templateId: String, val under: List<String> = emptyList())

    var pages: List<Page> = emptyList()
    var lastOpened: String? = null

    /** `link` rows by page: `(id, payload)`; what each wraps is answered as empty. */
    var links: Map<String, List<Pair<String, String>>> = emptyMap()

    val execs = ArrayList<List<Statement>>()
    val queries = ArrayList<Statement>()
    var failWith: (() -> Throwable)? = null

    val statements: List<Statement> get() = execs.flatten()
    fun sql(): List<String> = statements.map { it.sql }

    override fun exec(statements: List<Statement>): LongArray {
        failWith?.let { throw it() }
        execs += statements
        return LongArray(statements.size) { 1L }
    }

    override fun query(statement: Statement): StoreRows {
        failWith?.let { throw it() }
        queries += statement
        val sql = statement.sql
        return when {
            sql.contains("type = 'notebook'") -> StoreRows(listOf("refId"), listOfNotNull(lastOpened?.let { listOf<Cell>(Cell.Text(it)) }))
            sql.contains("type = 'page'") -> StoreRows(
                listOf("id", "order", "width", "height", "refId"),
                pages.mapIndexed { i, p -> listOf(Cell.Text(p.id), Cell.Integer(i.toLong()), Cell.Real(p.width.toDouble()), Cell.Real(p.height.toDouble()), Cell.Text(p.templateId)) },
            )
            sql.contains("RECURSIVE") -> {
                val pageId = (statement.args[0] as Cell.Text).value
                StoreRows(listOf("id"), pages.firstOrNull { it.id == pageId }?.under.orEmpty().map { listOf<Cell>(Cell.Text(it)) })
            }
            sql.contains("type = 'template'") -> StoreRows(listOf("id", "text", "width", "height", "blobLength"), emptyList())
            sql.contains("type = 'stroke'") -> StoreRows(listOf("id", "order", "color", "strokeWidth", "style", "blob"), emptyList())
            sql.contains("type = 'link'") -> {
                val pageId = (statement.args[0] as Cell.Text).value
                StoreRows(
                    listOf("id", "order", "text", "x", "y", "width", "height"),
                    links[pageId].orEmpty().mapIndexed { i, (id, payload) ->
                        listOf(Cell.Text(id), Cell.Integer(i.toLong()), Cell.Text(payload), Cell.Real(1.0), Cell.Real(2.0), Cell.Real(30.0), Cell.Real(40.0))
                    },
                )
            }
            sql.contains("type IN (") -> StoreRows(listOf("id", "type", "order", "text", "refId", "x", "y", "width", "height", "flags"), emptyList())
            sql.contains("MAX(\"order\")") -> StoreRows(listOf("m"), listOf(listOf<Cell>(Cell.Integer(2L))))
            sql.contains("type = ? AND deletedAt IS NULL") -> StoreRows(listOf("id"), emptyList())
            else -> throw IllegalArgumentException("unexpected query: $sql")
        }
    }
}
