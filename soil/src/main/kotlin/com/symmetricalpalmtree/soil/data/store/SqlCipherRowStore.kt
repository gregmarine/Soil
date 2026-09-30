package com.symmetricalpalmtree.soil.data.store

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
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
        db.beginTransaction()
        try {
            for ((i, statement) in statements.withIndex()) changes[i] = run(statement)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changes
    }

    /** One write with its binds; answers the rows the statement touched. */
    private fun run(statement: Statement): Long {
        val compiled = db.compileStatement(statement.sql)
        try {
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
        } finally {
            compiled.close()
        }
    }

    override fun query(statement: Statement): StoreRows {
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
            val names = cursor.columnNames.toList()
            val n = names.size
            val rows = ArrayList<List<Cell>>(cursor.count.coerceAtLeast(0))
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
                rows += cells
            }
            return StoreRows(names, rows)
        }
    }
}
