package com.symmetricalpalmtree.soil.pad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.ink.PageInk
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows

/**
 * The pad's test double for its store. There is no SQLite on the JVM, so this is a **statement
 * recorder** (every `exec` kept as its own transaction, so the boundaries are visible) and a
 * **canned-row responder** — the reads the pad makes are answered from a tiny in-memory picture
 * of the tables that the test sets up directly. Writes are recorded, never applied: what these
 * tests pin is the statements the pad emits, and real SQL is proved on the Nomad.
 */
class FakeScratchStore : RowStore {

    // ── The canned picture of the tables ──────

    var pages: List<String> = emptyList()
    var current: String? = null
    val ink = HashMap<String, PageInk>()

    /** Add a page with its ink, in list order. */
    fun page(id: String, ink: PageInk = PageInk.EMPTY) {
        pages = pages + id
        this.ink[id] = ink
        if (current == null) current = id
    }

    // ── What was asked ──────

    val execs = ArrayList<List<Statement>>()
    val queries = ArrayList<Statement>()

    /** Call kinds in order — `exec(n)`, `query(<name>)` — for ordering assertions. */
    val calls = ArrayList<String>()

    /** The `exec` call (0-based) that throws, or −1. */
    var failExecAt: Int = -1
    private var execCount = 0

    /** Set to make every call fail — the "the store is gone" half of the one rule. */
    var failWith: (() -> Throwable)? = null

    /** Every recorded statement, transactions flattened. */
    val statements: List<Statement> get() = execs.flatten()

    fun sql(): List<String> = statements.map { it.sql }

    // ── RowStore ──────

    override fun exec(statements: List<Statement>): LongArray {
        failWith?.let { throw it() }
        calls += "exec(${statements.size})"
        if (execCount++ == failExecAt) throw IllegalStateException("exec failed")
        execs += statements
        return LongArray(statements.size) { 1L }
    }

    override fun query(statement: Statement): StoreRows {
        failWith?.let { throw it() }
        queries += statement
        calls += "query(${name(statement)})"
        val (columns, rows) = rowsFor(statement)
        return StoreRows(columns, rows)
    }

    // ── The reads ──────

    /** A short name for a read, so a test can assert on call ORDER without matching whole SQL. */
    fun name(s: Statement): String = when {
        s.sql.startsWith("SELECT id FROM page") -> "pages"
        s.sql.startsWith("SELECT value FROM state") -> "current"
        s.sql.startsWith("SELECT width, height FROM page") -> "size"
        s.sql.startsWith("SELECT id, \"order\"") -> "strokes"
        else -> error("unexpected read: ${s.sql}")
    }

    private fun rowsFor(s: Statement): Pair<List<String>, List<List<Cell>>> = when (name(s)) {
        "pages" -> listOf("id") to pages.map { listOf(Cell.Text(it)) }
        "current" -> listOf("value") to (current?.let { listOf(listOf<Cell>(Cell.Text(it))) } ?: emptyList())
        "size" -> {
            val p = ink[arg(s, 0)]
            listOf("width", "height") to
                if (p == null) emptyList() else listOf(listOf(Cell.Real(p.width.toDouble()), Cell.Real(p.height.toDouble())))
        }
        "strokes" -> listOf("id", "order", "color", "width", "style", "blob") to
            (ink[arg(s, 0)]?.strokes.orEmpty()).map { (order, stroke) -> row(order, stroke) }
        else -> error("unreachable")
    }

    /** One `stroke` row as the database would answer it. */
    fun row(order: Long, stroke: Stroke): List<Cell> = listOf(
        Cell.Text(stroke.id),
        Cell.Integer(order),
        Cell.Integer(stroke.color.toLong()),
        Cell.Real(stroke.width.toDouble()),
        Cell.Text(stroke.style.name),
        Cell.Blob(ScratchSql.geometry(stroke)),
    )

    private fun arg(s: Statement, index: Int): String = (s.args[index] as Cell.Text).value
}
