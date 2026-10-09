package com.symmetricalpalmtree.soil.seam

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.symmetricalpalmtree.soil.data.Schema
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.store.AppStores
import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seamkit.RowCodec
import com.symmetricalpalmtree.soil.seamkit.RowsBuilder

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
    private val app: Context,
    private val name: String,
    private val schema: Schema,
    opened: Pair<SqlCipherRowStore, Long>,
    private val uid: Int,
    private val owner: IBinder,
    private val gate: () -> Unit,
) : ISeamStore.Stub(), IBinder.DeathRecipient {

    @Volatile
    private var over = false

    /** The connection, and the `AppStores.closings` count it was opened under. */
    private var rows: SqlCipherRowStore = opened.first
    private var openedAt: Long = opened.second

    /**
     * The store as it is now. `AppStores.closeAll` (a rotation, Forget, a restore) closes the
     * connection this lease was given; the next call opens the store again, under whatever key
     * it is under now, and the app goes on as if nothing had happened. The gate has already
     * refused the call while the library is not open, and the open refuses while a rotation
     * marker stands.
     *
     * Run under `AppStores`' own lock, so a close waits for the call in hand rather than closing
     * the connection under it.
     */
    private fun <T> withStore(block: (SqlCipherRowStore) -> T): T = synchronized(AppStores) {
        if (AppStores.closings() != openedAt) {
            val (fresh, at) = AppStores.lend(app, name, schema)
            rows = fresh
            openedAt = at
        }
        block(rows)
    }

    override fun exec(batch: SeamBytes): LongArray = answered {
        val statements = RowCodec.decodeStatements(SeamShared.readAndClose(batch))
        require(statements.size in 1..MAX_BATCH) { "a batch is 1..$MAX_BATCH statements" }
        for (statement in statements) {
            SeamSql.checkExec(statement.sql)
            require(SeamSql.bindCount(statement.sql) == statement.args.size) { "the binds do not match the arguments" }
        }
        withStore { it.exec(statements) }
    }

    override fun query(statement: SeamBytes): SeamBytes = answered {
        val one = RowCodec.decodeStatements(SeamShared.readAndClose(statement))
        require(one.size == 1) { "a query is one statement" }
        SeamSql.checkQuery(one[0].sql)
        require(SeamSql.bindCount(one[0].sql) == one[0].args.size) { "the binds do not match the arguments" }
        // Gathered under the seam's cap, refused at the row that would pass it — as an item's query is.
        val encoded = withStore { rows ->
            rows.stream(one[0], { columns -> RowsBuilder(columns) }) { builder, cells -> builder.add(cells) }.build()
        }
        SeamShared.write(encoded)
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
        /** Statements in one `exec`: the seam's own cap, the same as an item session's — a page of
         *  ink flushes as one transaction of as many statements as strokes (Calsprout, 2026-10-05). */
        const val MAX_BATCH = SeamLimits.MAX_BATCH_STATEMENTS

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
            val name = storeName(packageName)
            val soilSchema = Schema(schema.kind, schema.steps)
            val lease = AppStoreLease(app, name, soilSchema, AppStores.lend(app, name, soilSchema), uid, owner, gate)
            try {
                owner.linkToDeath(lease, 0)
            } catch (_: android.os.RemoteException) {
                throw IllegalStateException(SeamLimits.SESSION_ENDED)
            }
            return lease
        }
    }
}
