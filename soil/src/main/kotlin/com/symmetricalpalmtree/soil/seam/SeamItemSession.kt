package com.symmetricalpalmtree.soil.seam

import android.content.Context
import android.os.Binder
import android.os.IBinder
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seamkit.RowCodec

/**
 * **One app's hold on one open item.** It answers the app that opened it and no other, and it
 * ends when that app closes it or dies.
 *
 * Every statement is checked before it runs, whatever the app checked itself.
 */
class SeamItemSession(
    context: Context,
    private val itemId: String,
    private val holder: Long,
    private val ownerUid: Int,
    private val owner: IBinder,
    private val gate: () -> Unit,
) : ISeamItem.Stub(), IBinder.DeathRecipient {

    private val app = context.applicationContext

    override fun exec(batch: SeamBytes): LongArray = answered {
        val statements = RowCodec.decodeStatements(SeamShared.readAndClose(batch))
        for (statement in statements) {
            SeamSql.checkExec(statement.sql)
            require(SeamSql.bindCount(statement.sql) == statement.args.size) { "the binds do not match the arguments" }
        }
        ItemSessions.exec(itemId, holder, statements)
    }

    override fun query(statement: SeamBytes): SeamBytes = answered {
        val one = RowCodec.decodeStatements(SeamShared.readAndClose(statement))
        require(one.size == 1) { "a query is one statement" }
        SeamSql.checkQuery(one[0].sql)
        require(SeamSql.bindCount(one[0].sql) == one[0].args.size) { "the binds do not match the arguments" }
        SeamShared.write(ItemSessions.query(itemId, holder, one[0]))
    }

    override fun park() = answered { ItemSessions.park(app, itemId, holder) }

    override fun resume() = answered { ItemSessions.resume(app, itemId, holder) }

    override fun close(tidy: Boolean) {
        guard()
        end(tidy)
    }

    /** The app's process died. What it could undo died with it, so the file is tidied. */
    override fun binderDied() {
        Slog.d(TAG) { "an app died holding an item" }
        end(tidy = true)
    }

    /** Hand the region back once the reply that carries it has been written. */
    override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean =
        try {
            super.onTransact(code, data, reply, flags)
        } finally {
            sent.get()?.let { runCatching { it.memory.close() } }
            sent.remove()
        }

    private val sent = ThreadLocal<SeamBytes?>()

    private fun end(tidy: Boolean) {
        runCatching { owner.unlinkToDeath(this, 0) }
        ItemSessions.leave(app, itemId, holder, tidy)
    }

    private fun guard() {
        SeamCallerCheck.enforce(app)
        if (Binder.getCallingUid() != ownerUid) throw SecurityException("the session is another app's")
    }

    /**
     * Guard, gate, run, and let nothing cross that Binder cannot carry. A failure of any other
     * kind becomes an `IllegalStateException` that names its class and nothing else: a message
     * can hold a path, or what a person wrote.
     */
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
            Slog.d(TAG) { "a call failed: ${t.javaClass.simpleName}" }
            throw IllegalStateException("the call failed: ${t.javaClass.simpleName}")
        }
    }

    private companion object { const val TAG = "SeamItemSession" }
}
