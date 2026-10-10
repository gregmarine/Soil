package com.symmetricalpalmtree.soil.ext

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Parcel
import com.symmetricalpalmtree.soil.data.Schema
import com.symmetricalpalmtree.soil.data.SoilFiles
import com.symmetricalpalmtree.soil.data.store.AppStores
import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.SeamBytes
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seam.SeamSql
import com.symmetricalpalmtree.soil.seamkit.RowCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **The store Soil lends an extension**, for one call or one showing: an [IExtStore] over one of
 * Soil's own app stores, minted for one package and answering that package's uid alone, dead the
 * moment the lender [revoke]s it. The extension writes nothing to disk itself, ever; what it keeps
 * (a cloud provider's refresh token) lives here, under the global key, and is re-keyed with
 * everything else.
 *
 * Every statement is checked by the seam's own checker before it runs, whatever the extension
 * checked itself; the only table is the one Soil made ([CloudContract.STORE_CREATE]), because an
 * extension cannot send DDL. Only Security/IllegalArgument/IllegalState exceptions cross.
 */
class CloudStoreLease private constructor(
    private val app: Context,
    private val name: String,
    opened: Pair<SqlCipherRowStore, Long>,
    private val uid: Int,
) : IExtStore.Stub() {

    @Volatile
    private var revoked = false

    /** The connection, and the `AppStores.closings` count it was opened under. */
    private var rows: SqlCipherRowStore = opened.first
    private var openedAt: Long = opened.second

    /**
     * The store as it is now. `AppStores.closeAll` (a rotation, Forget, a restore) closes the
     * connection this lease was given; the next call opens the store again, under whatever key it
     * is under now — or fails, while the library is locked or a rotation marker stands. Run under
     * `AppStores`' own lock, so a close waits for the call in hand (`AppStoreLease`'s rule).
     */
    private fun <T> withStore(block: (SqlCipherRowStore) -> T): T = synchronized(AppStores) {
        if (AppStores.closings() != openedAt) {
            val (fresh, at) = AppStores.lend(app, name, SCHEMA)
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
        val result = withStore { it.query(one[0]) }
        SeamShared.write(RowCodec.encodeRows(result.columns, result.cells))
    }

    /** The lending is over: every later call is refused. The store itself stays open in Soil. */
    fun revoke() { revoked = true }

    /** Hand the region back once the reply that carries it has been written. */
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        try {
            super.onTransact(code, data, reply, flags)
        } finally {
            sent.get()?.let { runCatching { it.memory.close() } }
            sent.remove()
        }

    private val sent = ThreadLocal<SeamBytes?>()

    private inline fun <T> answered(block: () -> T): T {
        if (revoked) throw SecurityException("the lease is over")
        if (Binder.getCallingUid() != uid) throw SecurityException("the store is another app's")
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
        private const val TAG = "CloudStoreLease"
        const val MAX_BATCH = 64

        private val SCHEMA = Schema("ext_cloud", listOf(listOf(CloudContract.STORE_CREATE)))

        /** The store's name in the garden: the extension's package under an `ext_` prefix. */
        fun storeName(packageName: String): String = "ext_$packageName".also { require(SoilFiles.isValidStoreName(it)) { "not a store name" } }

        /**
         * Open the store for [packageName] on IO, before any bind, and wrap it for that package's
         * uid; or null, logged under [tag], when the package is gone or the library is locked.
         * The caller [revoke]s it in a `finally`, whatever the call did.
         */
        suspend fun lease(context: Context, packageName: String, tag: String): CloudStoreLease? = try {
            val app = context.applicationContext
            val name = storeName(packageName)
            val opened = withContext(Dispatchers.IO) { AppStores.lend(app, name, SCHEMA) }
            val uid = app.packageManager.getPackageUid(packageName, 0)
            CloudStoreLease(app, name, opened, uid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageManager.NameNotFoundException) {
            Slog.d(tag) { "store lease failed: the package is gone" }
            null
        } catch (e: Exception) {
            Slog.d(tag) { "store lease failed: ${e.javaClass.simpleName}" }
            null
        }
    }
}
