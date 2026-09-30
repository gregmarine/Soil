package com.symmetricalpalmtree.soil.paper.ink

import android.util.Log
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement

/** The screen cannot reach its storage (any store exception — the one rule: treat all as unavailable). */
class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/** One page as it is stored: its size and its strokes, each with the `"order"` it holds. */
class PageInk(val width: Float, val height: Float, val strokes: List<Pair<Long, Stroke>>) {
    companion object {
        /** A page with nothing on it and no size of its own yet. */
        val EMPTY = PageInk(0f, 0f, emptyList())
    }
}

/**
 * What every ink-on-rows store over a [RowStore] shares: the one-rule error mapping and the
 * stroke read. **Blocking** — every call runs on `Dispatchers.IO`, never Main.
 *
 * The schema, every SQL string and every read that is not a stroke read are the subclass's own —
 * a consumer's table shapes stay pinned by the consumer's own test.
 *
 * **There is no page ceiling.** A page is rows, and the only failure left is the store being gone —
 * any exception at all becomes [StoreUnavailable], which is what a screen answers to.
 */
abstract class InkStore(
    protected val store: RowStore,
    private val tag: String,
) {

    /**
     * Run [statements] in order, as one transaction and therefore atomic. Every statement a
     * consumer builds is idempotent as well, so a retry after a failure converges.
     */
    fun execAll(statements: List<Statement>) = guard { run(statements) }

    protected fun run(statements: List<Statement>) {
        if (statements.isNotEmpty()) store.exec(statements)
    }

    /** Every store failure is [StoreUnavailable] — the one rule. */
    protected fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: StoreUnavailable) {
            throw e
        } catch (e: Exception) {
            throw StoreUnavailable(e)
        }

    /**
     * A page's strokes, in writing order. A row that will not decode is dropped and counted, never
     * a lost page ([StrokeRows]).
     */
    protected fun readStrokes(pageId: String, select: Statement): List<Pair<Long, Stroke>> {
        val rows = store.query(select).rows
        val strokes = ArrayList<Pair<Long, Stroke>>(rows.size)
        var dropped = 0
        for (row in rows) {
            val decoded = StrokeRows.decode(row)
            if (decoded == null) dropped++ else strokes += decoded
        }
        if (dropped > 0) Log.w(tag, "page $pageId: $dropped stroke row(s) dropped")
        return strokes
    }
}
