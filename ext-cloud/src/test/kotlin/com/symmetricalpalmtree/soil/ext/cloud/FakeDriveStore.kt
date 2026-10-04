package com.symmetricalpalmtree.soil.ext.cloud

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows

/** The account table in memory, answering exactly the statements DriveSql writes. */
class FakeDriveStore : RowStore {

    val account = LinkedHashMap<String, String>()

    var failWith: (() -> Throwable)? = null

    override fun exec(statements: List<Statement>): LongArray {
        failWith?.let { throw it() }
        return LongArray(statements.size) { apply(statements[it]) }
    }

    override fun query(statement: Statement): StoreRows {
        failWith?.let { throw it() }
        require(statement.sql.startsWith("SELECT value FROM account WHERE key = ?")) { "unexpected read: ${statement.sql}" }
        val value = account[text(statement, 0)]
        return StoreRows(listOf("value"), if (value == null) emptyList() else listOf(listOf(Cell.Text(value))))
    }

    private fun apply(s: Statement): Long = when {
        s.sql.startsWith("INSERT OR REPLACE INTO account") -> { account[text(s, 0)] = text(s, 1); 1L }
        s.sql.startsWith("DELETE FROM account WHERE key = ?") -> if (account.remove(text(s, 0)) != null) 1L else 0L
        s.sql == "DELETE FROM account" -> { val n = account.size.toLong(); account.clear(); n }
        else -> error("unexpected write: ${s.sql}")
    }

    private fun text(s: Statement, index: Int): String = (s.args[index] as Cell.Text).value
}
