package com.symmetricalpalmtree.soil.seamkit

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows
import com.symmetricalpalmtree.soil.seam.SeamLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * The two documents that cross the seam, pure and shared by both ends. Big-endian.
 *
 * **Statements**: magic `SLST` · u8 version 1 · u32 count · per statement u32 sqlLen + UTF-8 sql ·
 * u16 argc · args as cells. **Rows**: magic `SLRW` · u8 version 1 · u16 columnCount · per column
 * u16 nameLen + UTF-8 name · u32 rowCount · per row per column a **cell**: u8 tag (`0 NULL ·
 * 1 INTEGER i64 · 2 REAL f64 · 3 TEXT u32 + UTF-8 · 4 BLOB u32 + bytes`).
 *
 * An unknown magic or version, a truncated document, a bad tag or a length past the end all throw
 * `IllegalArgumentException`: **unreadable is never empty**, which keeps a half-read value from
 * being written over what it was read from.
 */
object RowCodec {

    const val STATEMENTS_MAGIC = "SLST"
    const val ROWS_MAGIC = "SLRW"
    const val VERSION = 1

    private const val TAG_NULL = 0
    private const val TAG_INTEGER = 1
    private const val TAG_REAL = 2
    private const val TAG_TEXT = 3
    private const val TAG_BLOB = 4

    // ── Statements ──────

    fun encodeStatements(statements: List<Statement>): ByteArray {
        require(statements.size in 1..SeamLimits.MAX_BATCH_STATEMENTS) {
            "1..${SeamLimits.MAX_BATCH_STATEMENTS} statements per batch (${statements.size})"
        }
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeBytes(STATEMENTS_MAGIC)
        out.writeByte(VERSION)
        out.writeInt(statements.size)
        for (s in statements) {
            require(s.args.size <= SeamLimits.MAX_ARGS) { "${s.args.size} args, at most ${SeamLimits.MAX_ARGS}" }
            val sql = s.sql.toByteArray(Charsets.UTF_8)
            out.writeInt(sql.size)
            out.write(sql)
            out.writeShort(s.args.size)
            for (a in s.args) {
                requireStorable(a)
                writeCell(out, a)
            }
            require(out.size() <= SeamLimits.MAX_PAYLOAD_BYTES) { "the batch is too large to carry" }
        }
        out.flush()
        return bytes.toByteArray()
    }

    fun decodeStatements(bytes: ByteArray): List<Statement> = read(bytes) { input ->
        readMagic(input, STATEMENTS_MAGIC)
        val count = input.readInt()
        require(count in 1..SeamLimits.MAX_BATCH_STATEMENTS) { "statement count $count" }
        val list = ArrayList<Statement>(count)
        repeat(count) {
            val sql = String(readBytes(input, input.readInt()), Charsets.UTF_8)
            val argc = input.readUnsignedShort()
            require(argc <= SeamLimits.MAX_ARGS) { "arg count $argc" }
            val args = ArrayList<Cell>(argc)
            repeat(argc) { args += readCell(input).also(::requireStorable) }
            list += Statement(sql, args)
        }
        list
    }

    /** A value over the cap is stopped on its way in: it could be written and never read back. */
    fun requireStorable(cell: Cell) {
        val size = when (cell) {
            is Cell.Text -> utf8Length(cell.value)
            is Cell.Blob -> cell.value.size
            else -> 0
        }
        require(size <= SeamLimits.MAX_VALUE_BYTES) { SeamLimits.VALUE_TOO_LARGE }
    }

    // ── Rows ──────

    fun encodeRows(columns: List<String>, rows: List<List<Cell>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        writeRowsHeader(out, columns)
        out.writeInt(rows.size)
        for (row in rows) {
            require(row.size == columns.size) { "row has ${row.size} cells for ${columns.size} columns" }
            for (c in row) writeCell(out, c)
        }
        out.flush()
        return bytes.toByteArray()
    }

    fun decodeRows(bytes: ByteArray): StoreRows = read(bytes) { input ->
        readMagic(input, ROWS_MAGIC)
        val columnCount = input.readUnsignedShort()
        require(columnCount >= 1) { "a rows document needs at least one column" }
        val columns = ArrayList<String>(columnCount)
        repeat(columnCount) { columns += String(readBytes(input, input.readUnsignedShort()), Charsets.UTF_8) }
        val rowCount = input.readInt()
        require(rowCount >= 0) { "row count $rowCount" }
        val rows = ArrayList<List<Cell>>(minOf(rowCount, 1 shl 16))
        repeat(rowCount) {
            val cells = ArrayList<Cell>(columnCount)
            repeat(columnCount) { cells += readCell(input) }
            rows += cells
        }
        StoreRows(columns, rows)
    }

    // ── Sizes: the exact bytes the writers above produce ──────

    fun cellBytes(cell: Cell): Int = when (cell) {
        is Cell.Null -> 1
        is Cell.Integer -> 1 + 8
        is Cell.Real -> 1 + 8
        is Cell.Text -> 1 + 4 + utf8Length(cell.value)
        is Cell.Blob -> 1 + 4 + cell.value.size
    }

    fun rowBytes(cells: List<Cell>): Int = cells.sumOf { cellBytes(it) }

    /** The rows document's header for [columns]: magic, version, column names and the row count. */
    fun rowsHeaderBytes(columns: List<String>): Int =
        4 + 1 + 2 + columns.sumOf { 2 + utf8Length(it) } + 4

    // ── Internals ──────

    private fun writeRowsHeader(out: DataOutputStream, columns: List<String>) {
        require(columns.isNotEmpty()) { "a rows document needs at least one column" }
        require(columns.size <= 0xFFFF) { "${columns.size} columns" }
        out.writeBytes(ROWS_MAGIC)
        out.writeByte(VERSION)
        out.writeShort(columns.size)
        for (name in columns) {
            val b = name.toByteArray(Charsets.UTF_8)
            require(b.size <= 0xFFFF) { "column name too long" }
            out.writeShort(b.size)
            out.write(b)
        }
    }

    private fun writeCell(out: DataOutputStream, cell: Cell) {
        when (cell) {
            is Cell.Null -> out.writeByte(TAG_NULL)
            is Cell.Integer -> { out.writeByte(TAG_INTEGER); out.writeLong(cell.value) }
            is Cell.Real -> { out.writeByte(TAG_REAL); out.writeDouble(cell.value) }
            is Cell.Text -> {
                val b = cell.value.toByteArray(Charsets.UTF_8)
                out.writeByte(TAG_TEXT); out.writeInt(b.size); out.write(b)
            }
            is Cell.Blob -> { out.writeByte(TAG_BLOB); out.writeInt(cell.value.size); out.write(cell.value) }
        }
    }

    private fun readCell(input: DataInputStream): Cell = when (val tag = input.readUnsignedByte()) {
        TAG_NULL -> Cell.Null
        TAG_INTEGER -> Cell.Integer(input.readLong())
        TAG_REAL -> Cell.Real(input.readDouble())
        TAG_TEXT -> Cell.Text(String(readBytes(input, input.readInt()), Charsets.UTF_8))
        TAG_BLOB -> Cell.Blob(readBytes(input, input.readInt()))
        else -> throw IllegalArgumentException("unknown cell tag $tag")
    }

    private fun readMagic(input: DataInputStream, magic: String) {
        val m = ByteArray(4)
        input.readFully(m)
        require(String(m, Charsets.US_ASCII) == magic) { "not a seam document" }
        val v = input.readUnsignedByte()
        require(v == VERSION) { "unknown seam document version $v" }
    }

    private fun readBytes(input: DataInputStream, length: Int): ByteArray {
        require(length >= 0) { "negative length" }
        require(length <= input.available()) { "length $length past the end of the document" }
        val b = ByteArray(length)
        input.readFully(b)
        return b
    }

    private inline fun <T> read(bytes: ByteArray, block: (DataInputStream) -> T): T {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return try {
            val result = block(input)
            require(input.available() == 0) { "${input.available()} trailing byte(s)" }
            result
        } catch (e: EOFException) {
            throw IllegalArgumentException("truncated seam document", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("unreadable seam document", e)
        }
    }

    /** UTF-8 byte length without allocating the encoding. */
    fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }
}

/**
 * Gathers a query's rows as Soil reads them and answers one rows document. It counts as it goes
 * and stops at the row that would take the result past [cap], so a result too large to carry is
 * refused without being read to its end.
 */
class RowsBuilder(private val columns: List<String>, private val cap: Int = SeamLimits.MAX_PAYLOAD_BYTES) {
    private val rows = ArrayList<List<Cell>>()
    private var bytes = RowCodec.rowsHeaderBytes(columns).toLong()

    fun add(cells: List<Cell>) {
        bytes += RowCodec.rowBytes(cells)
        if (bytes > cap) throw IllegalStateException(SeamLimits.RESULT_TOO_LARGE)
        rows += cells
    }

    fun build(): ByteArray = RowCodec.encodeRows(columns, rows)
}
