package com.symmetricalpalmtree.soil.data.store

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows

/**
 * [RowStore] over one of Soil's own encrypted databases, in this process — the only thing between
 * `:paper`'s ink and SQLite. **Blocking**: IO only.
 */
class SqlCipherRowStore(private val db: SupportSQLiteDatabase) : RowStore {

    override fun exec(statements: List<Statement>): LongArray {
        val changes = LongArray(statements.size)
        // A batch of one page's strokes is one SQL text three thousand times: compiled once.
        val compiled = HashMap<String, SupportSQLiteStatement>()
        db.beginTransaction()
        try {
            for ((i, statement) in statements.withIndex()) {
                val prepared = compiled.getOrPut(statement.sql) { db.compileStatement(statement.sql) }
                changes[i] = run(prepared, statement)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            compiled.values.forEach { runCatching { it.close() } }
        }
        return changes
    }

    /** One write with its binds; answers the rows the statement touched. */
    private fun run(compiled: SupportSQLiteStatement, statement: Statement): Long {
        compiled.clearBindings()
        for ((i, cell) in statement.args.withIndex()) {
            val index = i + 1
            when (cell) {
                is Cell.Null -> compiled.bindNull(index)
                is Cell.Integer -> compiled.bindLong(index, cell.value)
                is Cell.Real -> compiled.bindDouble(index, cell.value)
                is Cell.Text -> compiled.bindString(index, cell.value)
                is Cell.Blob -> compiled.bindBlob(index, cell.value)
            }
        }
        return compiled.executeUpdateDelete().toLong()
    }

    override fun query(statement: Statement): StoreRows {
        var names: List<String> = emptyList()
        val rows = stream(statement, { columns -> names = columns; ArrayList<List<Cell>>() }) { list, cells -> list += cells }
        return StoreRows(names, rows)
    }

    /**
     * Run one SELECT and hand each row to [add] as it is read, so a reader that counts can stop
     * at the row that is one too many without the rest being read.
     */
    fun <B> stream(statement: Statement, builder: (columns: List<String>) -> B, add: (B, List<Cell>) -> Unit): B {
        val bound = Array<Any?>(statement.args.size) { i ->
            when (val c = statement.args[i]) {
                is Cell.Null -> null
                is Cell.Integer -> c.value
                is Cell.Real -> c.value
                is Cell.Text -> c.value
                is Cell.Blob -> c.value
            }
        }
        db.query(statement.sql, bound).use { cursor ->
            val n = cursor.columnCount
            val out = builder(cursor.columnNames.toList())
            while (cursor.moveToNext()) {
                val cells = ArrayList<Cell>(n)
                for (i in 0 until n) {
                    cells += when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> Cell.Null
                        Cursor.FIELD_TYPE_INTEGER -> Cell.Integer(cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> Cell.Real(cursor.getDouble(i))
                        Cursor.FIELD_TYPE_STRING -> Cell.Text(cursor.getString(i))
                        Cursor.FIELD_TYPE_BLOB -> Cell.Blob(cursor.getBlob(i))
                        else -> Cell.Null
                    }
                }
                add(out, cells)
            }
            return out
        }
    }
}
