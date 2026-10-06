package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows
import com.symmetricalpalmtree.soil.seam.SeamSql

/**
 * The calendar's test double for its store (the pad's recipe): a **statement recorder** (every
 * `exec` kept as its own transaction) and a **canned-row responder** for the reads the calendar
 * makes, answered from a tiny in-memory picture of the tables the test sets up directly. Writes
 * are recorded, never applied: what these tests pin is the statements the calendar emits, and
 * real SQL is proved on the Nomad.
 *
 * Every statement is run through the seam's checker on the way in, so a builder Soil would
 * refuse fails here first.
 */
class FakeCalendarStore : RowStore {

    class Period(val id: String, val calendarId: String, val kind: Int, val date: String)
    class Page(val id: String, val periodId: String, val half: Int, val width: Float, val height: Float, val strokes: List<Pair<Long, Stroke>>)

    val calendars = ArrayList<String>()
    val periods = ArrayList<Period>()
    val pages = ArrayList<Page>()
    val state = HashMap<String, String>()

    fun calendar(id: String) { calendars += id }
    fun period(id: String, kind: Int, date: String, calendarId: String = CalendarSchema.DEFAULT_CALENDAR) {
        periods += Period(id, calendarId, kind, date)
    }
    fun page(id: String, periodId: String, half: Int, width: Float, height: Float, strokes: List<Pair<Long, Stroke>> = emptyList()) {
        pages += Page(id, periodId, half, width, height, strokes)
    }

    val execs = ArrayList<List<Statement>>()
    val queries = ArrayList<Statement>()
    val calls = ArrayList<String>()
    var failExecAt: Int = -1
    private var execCount = 0
    var failWith: (() -> Throwable)? = null

    val statements: List<Statement> get() = execs.flatten()
    fun sql(): List<String> = statements.map { it.sql }

    override fun exec(statements: List<Statement>): LongArray {
        failWith?.let { throw it() }
        for (s in statements) SeamSql.checkExec(s.sql)
        calls += "exec(${statements.size})"
        if (execCount++ == failExecAt) throw IllegalStateException("exec failed")
        execs += statements
        return LongArray(statements.size) { 1L }
    }

    override fun query(statement: Statement): StoreRows {
        failWith?.let { throw it() }
        SeamSql.checkQuery(statement.sql)
        queries += statement
        calls += "query(${name(statement)})"
        val (columns, rows) = rowsFor(statement)
        return StoreRows(columns, rows)
    }

    fun name(s: Statement): String = when {
        s.sql.startsWith("SELECT id FROM calendar") -> "calendar"
        s.sql.startsWith("SELECT id FROM period") -> "period"
        s.sql.startsWith("SELECT page.id AS id") -> "page"
        s.sql.startsWith("SELECT id, \"order\"") -> "strokes"
        s.sql.startsWith("SELECT COALESCE(MAX(\"order\")") -> "maxOrder"
        s.sql.startsWith("SELECT key, value FROM state") -> "state"
        s.sql.startsWith("SELECT (SELECT COUNT(*) FROM period)") -> "counts"
        else -> error("unexpected read: ${s.sql}")
    }

    private fun periodOf(calendarId: String, kind: Long, date: String) =
        periods.firstOrNull { it.calendarId == calendarId && it.kind.toLong() == kind && it.date == date }

    private fun rowsFor(s: Statement): Pair<List<String>, List<List<Cell>>> = when (name(s)) {
        "calendar" -> listOf("id") to calendars.filter { it == text(s, 0) }.map { listOf<Cell>(Cell.Text(it)) }
        "period" -> listOf("id") to listOfNotNull(periodOf(text(s, 0), long(s, 1), text(s, 2))?.let { listOf<Cell>(Cell.Text(it.id)) })
        "page" -> {
            val p = periodOf(text(s, 0), long(s, 1), text(s, 2))
            val page = p?.let { pp -> pages.firstOrNull { it.periodId == pp.id && it.half.toLong() == long(s, 3) } }
            listOf("id", "periodId", "width", "height") to listOfNotNull(
                page?.let { listOf(Cell.Text(it.id), Cell.Text(it.periodId), Cell.Real(it.width.toDouble()), Cell.Real(it.height.toDouble())) },
            )
        }
        "strokes" -> listOf("id", "order", "color", "width", "style", "blob") to
            strokesOf(text(s, 0)).map { (order, stroke) -> row(order, stroke) }
        "maxOrder" -> listOf("maxOrder") to listOf(listOf(Cell.Integer(strokesOf(text(s, 0)).maxOfOrNull { it.first } ?: -1L)))
        "state" -> listOf("key", "value") to state.map { (k, v) -> listOf(Cell.Text(k), Cell.Text(v)) }
        "counts" -> listOf("periods", "pages", "strokes", "events") to listOf(
            listOf(
                Cell.Integer(periods.size.toLong()), Cell.Integer(pages.size.toLong()),
                Cell.Integer(pages.sumOf { it.strokes.size }.toLong()), Cell.Integer(0),
            ),
        )
        else -> error("unreachable")
    }

    private fun strokesOf(pageId: String) = pages.firstOrNull { it.id == pageId }?.strokes.orEmpty()

    fun row(order: Long, stroke: Stroke): List<Cell> = listOf(
        Cell.Text(stroke.id), Cell.Integer(order), Cell.Integer(stroke.color.toLong()),
        Cell.Real(stroke.width.toDouble()), Cell.Text(stroke.style.name), Cell.Blob(StrokeBlob.encode(stroke)),
    )

    private fun text(s: Statement, i: Int): String = (s.args[i] as Cell.Text).value
    private fun long(s: Statement, i: Int): Long = (s.args[i] as Cell.Integer).value
}
