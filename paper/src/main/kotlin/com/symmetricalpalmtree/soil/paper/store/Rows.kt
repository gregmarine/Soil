package com.symmetricalpalmtree.soil.paper.store

/**
 * One SQLite value as a [RowStore] takes and returns it — SQLite's five storage classes and
 * nothing else. [Blob] compares by content.
 */
sealed class Cell {
    object Null : Cell() {
        override fun toString() = "NULL"
    }

    data class Integer(val value: Long) : Cell()

    data class Real(val value: Double) : Cell()

    data class Text(val value: String) : Cell()

    class Blob(val value: ByteArray) : Cell() {
        override fun equals(other: Any?): Boolean = other is Blob && other.value.contentEquals(value)
        override fun hashCode(): Int = value.contentHashCode()
        override fun toString() = "Blob(${value.size} bytes)"
    }

    companion object {
        /** The `?` binding for a Kotlin value: `null`, `Long`/`Int`, `Double`/`Float`, `String`,
         *  `ByteArray`, `Boolean` (0/1). Anything else is refused. */
        fun of(value: Any?): Cell = when (value) {
            null -> Null
            is Cell -> value
            is Long -> Integer(value)
            is Int -> Integer(value.toLong())
            is Boolean -> Integer(if (value) 1L else 0L)
            is Double -> Real(value)
            is Float -> Real(value.toDouble())
            is String -> Text(value)
            is ByteArray -> Blob(value)
            else -> throw IllegalArgumentException("not a store value: ${value.javaClass.simpleName}")
        }
    }
}

/** One parameterized statement: the SQL with `?` / `?NNN` binds and its arguments, in bind order. */
class Statement(val sql: String, val args: List<Cell> = emptyList()) {
    /** Convenience: `Statement("… ?, ?", 1L, "x")` with [Cell.of] applied to each argument. */
    constructor(sql: String, vararg args: Any?) : this(sql, args.map { Cell.of(it) })

    override fun toString() = "Statement(${sql.length} chars, ${args.size} args)"
}

/**
 * One decoded row. Typed accessors answer the cell's value or throw `IllegalArgumentException`
 * when the column is absent or of another storage class — a reader that treats a malformed row
 * as "drop this row" catches exactly that.
 */
class Row(val columns: List<String>, val cells: List<Cell>) {
    val size: Int get() = cells.size

    operator fun get(index: Int): Cell = cells[index]
    operator fun get(column: String): Cell = cells[indexOf(column)]

    fun indexOf(column: String): Int {
        val i = columns.indexOf(column)
        require(i >= 0) { "no column '$column' in $columns" }
        return i
    }

    fun isNull(index: Int): Boolean = cells[index] is Cell.Null
    fun isNull(column: String): Boolean = isNull(indexOf(column))

    fun long(index: Int): Long = (cells[index] as? Cell.Integer)?.value
        ?: throw IllegalArgumentException("column ${name(index)} is not INTEGER (${cells[index]})")
    fun long(column: String): Long = long(indexOf(column))
    fun longOrNull(index: Int): Long? = if (isNull(index)) null else long(index)
    fun longOrNull(column: String): Long? = longOrNull(indexOf(column))

    fun real(index: Int): Double = when (val c = cells[index]) {
        is Cell.Real -> c.value
        is Cell.Integer -> c.value.toDouble()   // SQLite's own affinity: an integer is a real
        else -> throw IllegalArgumentException("column ${name(index)} is not REAL ($c)")
    }
    fun real(column: String): Double = real(indexOf(column))
    fun realOrNull(index: Int): Double? = if (isNull(index)) null else real(index)
    fun realOrNull(column: String): Double? = realOrNull(indexOf(column))

    fun text(index: Int): String = (cells[index] as? Cell.Text)?.value
        ?: throw IllegalArgumentException("column ${name(index)} is not TEXT (${cells[index]})")
    fun text(column: String): String = text(indexOf(column))
    fun textOrNull(index: Int): String? = if (isNull(index)) null else text(index)
    fun textOrNull(column: String): String? = textOrNull(indexOf(column))

    fun blob(index: Int): ByteArray = (cells[index] as? Cell.Blob)?.value
        ?: throw IllegalArgumentException("column ${name(index)} is not BLOB (${cells[index]})")
    fun blob(column: String): ByteArray = blob(indexOf(column))
    fun blobOrNull(index: Int): ByteArray? = if (isNull(index)) null else blob(index)
    fun blobOrNull(column: String): ByteArray? = blobOrNull(indexOf(column))

    private fun name(index: Int): String = columns.getOrNull(index) ?: "#$index"
}

/** The whole result of one SELECT. */
class StoreRows(val columns: List<String>, val cells: List<List<Cell>>) : Iterable<Row> {
    val size: Int get() = cells.size
    fun isEmpty(): Boolean = cells.isEmpty()
    operator fun get(index: Int): Row = Row(columns, cells[index])
    val rows: List<Row> get() = cells.map { Row(columns, it) }
    override fun iterator(): Iterator<Row> = rows.iterator()
}

