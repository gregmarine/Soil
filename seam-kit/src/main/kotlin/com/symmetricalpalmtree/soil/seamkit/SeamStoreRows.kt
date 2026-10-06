package com.symmetricalpalmtree.soil.seamkit

import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows
import com.symmetricalpalmtree.soil.seam.ISeamStore
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seam.SeamSql

/**
 * [RowStore] over an app's own store that Soil holds open (`ISoilSeam.openAppStore`):
 * [SeamRowStore]'s twin for an app with no item files.
 *
 * Every statement is checked here before it is sent, so a refusal is raised in the app, where the
 * statement was written, and names its rule. Soil checks again.
 *
 * **Blocking.** Each call crosses to Soil and waits. `Dispatchers.IO` only, never Main.
 */
class SeamStoreRows(private val store: ISeamStore) : RowStore {

    override fun exec(statements: List<Statement>): LongArray {
        if (statements.isEmpty()) return LongArray(0)
        statements.forEach { SeamSql.checkExec(it.sql) }
        val batch = SeamShared.write(RowCodec.encodeStatements(statements))
        try {
            return store.exec(batch)
        } finally {
            batch.memory.close()
        }
    }

    override fun query(statement: Statement): StoreRows {
        SeamSql.checkQuery(statement.sql)
        val sent = SeamShared.write(RowCodec.encodeStatements(listOf(statement)))
        val answer = try {
            store.query(sent)
        } finally {
            sent.memory.close()
        }
        return RowCodec.decodeRows(SeamShared.readAndClose(answer))
    }
}
