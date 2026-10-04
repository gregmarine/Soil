package com.symmetricalpalmtree.soil.data.item

import android.content.Context
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.LinkRow
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seam.SeamLinks
import com.symmetricalpalmtree.soil.seam.SeamSchema
import com.symmetricalpalmtree.soil.seam.SeamSql
import com.symmetricalpalmtree.soil.seamkit.RowsBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB

/**
 * **The items that apps hold open.** One connection to a file however many sessions hold it, and
 * no connection at all while every one of them is parked.
 *
 * What a passphrase change, a Forget and a delete ask of it is [openItems] and [isHeld]: a file
 * that is open cannot be re-keyed, and an item that is held cannot be deleted.
 *
 * One lock over everything. The work under it is short, and one app is in front at a time.
 *
 * **Blocking.** IO or a binder thread; never Main.
 */
object ItemSessions {

    private const val TAG = "ItemSessions"

    private class Connection(val db: ZeticDB, val schema: SeamSchema) {
        val rows = SqlCipherRowStore(db)
        /** When the file was last written through this connection; 0 when it has not been. */
        var writtenAt = 0L
    }

    private val book = SessionBook()
    private val connections = HashMap<String, Connection>()
    /** The schema an item was last opened at, kept for a tidy after its file is shut. */
    private val schemas = HashMap<String, SeamSchema>()
    private var nextHolder = 1L

    private val _changes = MutableStateFlow(0)

    /** Moves whenever the library's rows may have changed: an item made, renamed, deleted or
     *  written. What it holds means nothing. */
    val changes: StateFlow<Int> get() = _changes

    fun changed() { _changes.value = _changes.value + 1 }

    /** Open [itemId] for a new session, and answer the session's holder. */
    @Synchronized
    fun join(context: Context, itemId: String, schema: SeamSchema): Long {
        val holder = nextHolder++
        schemas[itemId] = schema
        perform(context, itemId, book.join(itemId, holder)) { book.leave(itemId, holder, tidy = false) }
        return holder
    }

    @Synchronized
    fun park(context: Context, itemId: String, holder: Long) {
        requireHeld(itemId, holder)
        perform(context, itemId, book.park(itemId, holder))
    }

    @Synchronized
    fun resume(context: Context, itemId: String, holder: Long) {
        requireHeld(itemId, holder)
        perform(context, itemId, book.resume(itemId, holder)) { book.park(itemId, holder) }
    }

    /** End the session. Idempotent; never throws. */
    @Synchronized
    fun leave(context: Context, itemId: String, holder: Long, tidy: Boolean) {
        if (!book.holds(itemId, holder)) return
        runCatching { perform(context, itemId, book.leave(itemId, holder, tidy)) }
            .onFailure { Slog.d(TAG) { "leave failed: ${it.javaClass.simpleName}" } }
        if (!book.isHeld(itemId)) schemas.remove(itemId)
    }

    @Synchronized
    fun exec(itemId: String, holder: Long, statements: List<Statement>): LongArray {
        val connection = live(itemId, holder)
        val changed = connection.rows.exec(statements)
        connection.writtenAt = System.currentTimeMillis()
        if (statements.any { SeamSql.writesLinkMirror(it.sql) }) mirrorLinks(itemId, connection)
        return changed
    }

    /**
     * The file's link mirror, read whole into the index. After the batch, outside its
     * transaction: the batch has landed whatever happens here, and a mirror that could not be
     * read is a mirror read at the next batch, or at a rebuild.
     */
    private fun mirrorLinks(itemId: String, connection: Connection) {
        if (!SoilIndex.isReady()) return
        try {
            val links = connection.rows.query(Statement(SeamLinks.READ)).rows.map {
                LinkRow(it.text("id"), it.text("pageId"), it.text("targetItemId"), it.textOrNull("targetPageId"))
            }
            IndexStore().replaceLinks(itemId, links)
        } catch (t: Throwable) {
            Slog.d(TAG) { "the link mirror was not indexed: ${t.javaClass.simpleName}" }
        }
    }

    /** One SELECT, as the rows document that crosses the seam. A result too large to carry is
     *  refused at the row that makes it so. */
    @Synchronized
    fun query(itemId: String, holder: Long, statement: Statement): ByteArray =
        live(itemId, holder).rows
            .stream(statement, { columns -> RowsBuilder(columns) }) { builder, cells -> builder.add(cells) }
            .build()

    /** The item's own name for itself, when its file is open. A shut file is written at its next open. */
    @Synchronized
    fun rename(itemId: String, name: String) {
        connections[itemId]?.let { runCatching { ItemFiles.writeName(it.db, name) } }
    }

    /**
     * Give up every file without ending a session: what a Forget and a rotation do before they
     * touch the garden, so no connection is under them. Every holder is left parked; an app that
     * was in the middle of something is answered "parked" and resumes. Never throws.
     */
    @Synchronized
    fun releaseAll(context: Context) {
        for ((itemId, holder) in book.holders()) {
            runCatching { perform(context, itemId, book.park(itemId, holder)) }
                .onFailure { Slog.d(TAG) { "release failed: ${it.javaClass.simpleName}" } }
        }
    }

    @Synchronized
    fun isHeld(itemId: String): Boolean = book.isHeld(itemId)

    /** The items whose files are open right now. */
    @Synchronized
    fun openItems(): List<String> = book.openItems()

    private fun requireHeld(itemId: String, holder: Long) {
        check(book.holds(itemId, holder)) { SeamLimits.SESSION_ENDED }
    }

    private fun live(itemId: String, holder: Long): Connection {
        requireHeld(itemId, holder)
        check(!book.isParked(itemId, holder)) { "the session is parked" }
        return connections[itemId] ?: throw IllegalStateException(SeamLimits.SESSION_ENDED)
    }

    /** Do to the file what the book has already written down. When an open fails, [undo] puts
     *  the book back and the failure is thrown on. */
    private fun perform(context: Context, itemId: String, act: SessionBook.Act, undo: () -> Unit = {}) {
        val app = context.applicationContext
        when (act) {
            SessionBook.Act.NONE -> Unit
            SessionBook.Act.OPEN -> try {
                val schema = schemas.getValue(itemId)
                connections[itemId] = Connection(ItemFiles.open(app, itemId, schema), schema)
            } catch (t: Throwable) {
                // The book said the file is open. It is not: put that right before anything else.
                undo()
                unopened(itemId)
                throw t
            }
            SessionBook.Act.RELEASE, SessionBook.Act.CLOSE -> shut(app, itemId, tidy = false)
            SessionBook.Act.CLOSE_TIDY -> shut(app, itemId, tidy = true)
            SessionBook.Act.TIDY_COLD -> {
                val schema = schemas[itemId] ?: return
                val db = ItemFiles.open(app, itemId, schema)
                ItemFiles.tidy(db, schema)
                ItemFiles.close(app, itemId, db)
            }
        }
    }

    /** An open failed after the book had marked the file open. Every holder that is left is
     *  parked, so the book and the file agree again. */
    private fun unopened(itemId: String) {
        for ((id, holder) in book.holders()) if (id == itemId) book.park(id, holder)
    }

    private fun shut(app: Context, itemId: String, tidy: Boolean) {
        val connection = connections.remove(itemId) ?: return
        if (tidy) ItemFiles.tidy(connection.db, connection.schema)
        ItemFiles.close(app, itemId, connection.db)
        if (connection.writtenAt > 0L && SoilIndex.isReady()) {
            runCatching { IndexStore().touch(itemId, connection.writtenAt) }
            changed()
        }
    }
}
