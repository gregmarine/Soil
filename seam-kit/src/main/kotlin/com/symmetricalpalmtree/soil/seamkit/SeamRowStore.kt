package com.symmetricalpalmtree.soil.seamkit

import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows
import com.symmetricalpalmtree.soil.seam.ISeamItem
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seam.SeamSql

/**
 * [RowStore] over an item that Soil holds open: what `:paper`'s ink writes to in a Sprout app.
 *
 * Every statement is checked here before it is sent, so a refusal is raised in the app, where the
 * statement was written, and names its rule. Soil checks again.
 *
 * **Blocking.** Each call crosses to Soil and waits. `Dispatchers.IO` only, never Main.
 */
class SeamRowStore(private val item: ISeamItem) : RowStore {

    override fun exec(statements: List<Statement>): LongArray {
        if (statements.isEmpty()) return LongArray(0)
        statements.forEach { SeamSql.checkExec(it.sql) }
        val batch = SeamShared.write(RowCodec.encodeStatements(statements))
        try {
            return item.exec(batch)
        } finally {
            batch.memory.close()
        }
    }

    override fun query(statement: Statement): StoreRows {
        SeamSql.checkQuery(statement.sql)
        val sent = SeamShared.write(RowCodec.encodeStatements(listOf(statement)))
        val answer = try {
            item.query(sent)
        } finally {
            sent.memory.close()
        }
        return RowCodec.decodeRows(SeamShared.readAndClose(answer))
    }
}
