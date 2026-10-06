package com.symmetricalpalmtree.soil.seam

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.symmetricalpalmtree.soil.data.Schema
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.store.AppStores
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.seamkit.RowCodec

/**
 * **An app's own store, lent over the seam** (`ISoilSeam.openAppStore`): an [ISeamStore] over one
 * of Soil's app stores, minted for one package and answering that package's uid alone, dead the
 * moment the app closes it or its process dies. What the app keeps here (the reader's position
 * and recents) lives under the global key, in a file named after the package, and is re-keyed
 * and backed up with everything else. The app never sees a path or a key.
 *
 * Every statement is checked by the seam's own checker before it runs, whatever the app checked
 * itself; the tables are the ones the schema the open declared made, because an app cannot send
 * DDL through the gate. Only Security/IllegalArgument/IllegalState exceptions cross.
 */
class AppStoreLease private constructor(
    private val rows: RowStore,
    private val uid: Int,
    private val owner: IBinder,
    private val gate: () -> Unit,
) : ISeamStore.Stub(), IBinder.DeathRecipient {

    @Volatile
    private var over = false

    override fun exec(batch: SeamBytes): LongArray = answered {
        val statements = RowCodec.decodeStatements(SeamShared.readAndClose(batch))
        require(statements.size in 1..MAX_BATCH) { "a batch is 1..$MAX_BATCH statements" }
        for (statement in statements) {
            SeamSql.checkExec(statement.sql)
            require(SeamSql.bindCount(statement.sql) == statement.args.size) { "the binds do not match the arguments" }
        }
        rows.exec(statements)
    }

    override fun query(statement: SeamBytes): SeamBytes = answered {
        val one = RowCodec.decodeStatements(SeamShared.readAndClose(statement))
        require(one.size == 1) { "a query is one statement" }
        SeamSql.checkQuery(one[0].sql)
        require(SeamSql.bindCount(one[0].sql) == one[0].args.size) { "the binds do not match the arguments" }
        val result = rows.query(one[0])
        SeamShared.write(RowCodec.encodeRows(result.columns, result.cells))
    }

    override fun close() {
        guard()
        end()
    }

    /** The app's process died: the lease is over. The store stays open in Soil. */
    override fun binderDied() {
        Slog.d(TAG) { "an app died holding its store" }
        end()
    }

    /** Hand the region back once the reply that carries it has been written. */
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        try {
            super.onTransact(code, data, reply, flags)
        } finally {
            sent.get()?.let { runCatching { it.memory.close() } }
            sent.remove()
        }

    private val sent = ThreadLocal<SeamBytes?>()

    private fun end() {
        over = true
        runCatching { owner.unlinkToDeath(this, 0) }
    }

    private fun guard() {
        if (over) throw SecurityException(SeamLimits.SESSION_ENDED)
        if (Binder.getCallingUid() != uid) throw SecurityException("the store is another app's")
    }

    private inline fun <T> answered(block: () -> T): T {
        guard()
        gate()
        return try {
            block().also { if (it is SeamBytes) sent.set(it) }
        } catch (e: SecurityException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (t: Throwable) {
            Slog.d(TAG) { "a store call failed: ${t.javaClass.simpleName}" }
            throw IllegalStateException("the call failed: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "AppStoreLease"
        const val MAX_BATCH = 64

        /** The store's name in the garden: the app's package under an `app_` prefix. */
        fun storeName(packageName: String): String =
            "app_$packageName".also { require(SoilFiles.isValidStoreName(it)) { "not a store name" } }

        /**
         * Open the store for [packageName] at [schema] (made on first use, the steps it is missing
         * run) and lend it to [uid], until [owner] dies. **Blocking**: the binder thread, never
         * Main. Throws what the open throws.
         */
        fun open(context: Context, packageName: String, schema: SeamSchema, uid: Int, owner: IBinder, gate: () -> Unit): AppStoreLease {
            val app = context.applicationContext
            val rows = AppStores.open(app, storeName(packageName), Schema(schema.kind, schema.steps))
            val lease = AppStoreLease(rows, uid, owner, gate)
            try {
                owner.linkToDeath(lease, 0)
            } catch (_: android.os.RemoteException) {
                throw IllegalStateException(SeamLimits.SESSION_ENDED)
            }
            return lease
        }
    }
}
