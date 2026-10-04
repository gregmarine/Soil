package com.symmetricalpalmtree.soil.ext.cloud

import com.symmetricalpalmtree.soil.ext.IExtStore
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.store.StoreRows
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.RowCodec

/** The store Soil lends, as rows: the seam's encoding over the lease's two calls. */
class ExtStoreRows(private val store: IExtStore) : RowStore {

    override fun exec(statements: List<Statement>): LongArray {
        if (statements.isEmpty()) return LongArray(0)
        val batch = SeamShared.write(RowCodec.encodeStatements(statements))
        try {
            return store.exec(batch)
        } finally {
            batch.memory.close()
        }
    }

    override fun query(statement: Statement): StoreRows {
        val sent = SeamShared.write(RowCodec.encodeStatements(listOf(statement)))
        val answer = try {
            store.query(sent)
        } finally {
            sent.memory.close()
        }
        return RowCodec.decodeRows(SeamShared.readAndClose(answer))
    }
}
