package com.symmetricalpalmtree.soil.pad

import android.util.Log
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.ink.InkStore
import com.symmetricalpalmtree.soil.paper.ink.PageInk
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import java.util.UUID

/**
 * The Scratch Pad's tables over its [RowStore], on `:paper`'s [InkStore] base. **Blocking** —
 * every call runs on `Dispatchers.IO`, never Main.
 *
 * The schema is [ScratchSchema]'s and is in place before this is handed a store: the database is
 * brought to it as it is opened. Every SQL string lives in [ScratchSql]; every write goes through
 * [execAll], one transaction.
 *
 * **There is no page ceiling.** A page is rows, and the only failure left is the store being
 * gone — any exception at all becomes `StoreUnavailable`, which is what the screen answers to.
 */
class ScratchStore(store: RowStore) : InkStore(store, TAG) {

    class Loaded(val ids: List<String>, val currentId: String)

    // ── Loading ──────────────────────────────────────────────────────────────

    /**
     * Read the page list and the current page. First run creates one blank page and names it
     * current, in one transaction. A `current` that is not in the list is clamped and the row
     * corrected, so the disagreement never survives a second open.
     */
    fun load(): Loaded = guard {
        val ids = store.query(ScratchSql.selectPages()).rows.map { it.text("id") }
        if (ids.isEmpty()) {
            val id = newId()
            val now = System.currentTimeMillis()
            run(listOf(ScratchSql.insertPage(id, 0, 0f, 0f, now), ScratchSql.setCurrent(id)))
            return@guard Loaded(listOf(id), id)
        }
        val stored = store.query(ScratchSql.selectCurrent()).rows.firstOrNull()?.textOrNull("value")
        val current = ScratchPages.clampCurrent(ids, stored)
        if (current != stored) run(listOf(ScratchSql.setCurrent(current)))
        Loaded(ids, current)
    }

    /**
     * One page's size and ink. A missing page row is a page that went away underneath us: it
     * reads as empty and says so, rather than throwing.
     */
    fun readPage(id: String): PageInk = guard {
        val size = store.query(ScratchSql.selectPageSize(id)).rows.firstOrNull()
        if (size == null) Log.w(TAG, "page row is gone — reading it as empty")
        val width = size?.real("width")?.toFloat() ?: 0f
        val height = size?.real("height")?.toFloat() ?: 0f
        PageInk(width, height, readStrokes(id, ScratchSql.selectStrokes(id)))
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    fun setCurrent(id: String) = execAll(listOf(ScratchSql.setCurrent(id)))

    /** Insert a new blank page after [afterId]; returns (new id list, new id). It becomes current. */
    fun insertPage(ids: List<String>, afterId: String?): Pair<List<String>, String> {
        val id = newId()
        return insert(ScratchPages.insertAfter(ids, afterId, id), id)
    }

    /** Insert a new blank page before [beforeId]; returns (new id list, new id). It becomes current. */
    fun insertPageBefore(ids: List<String>, beforeId: String?): Pair<List<String>, String> {
        val id = newId()
        return insert(ScratchPages.insertBefore(ids, beforeId, id), id)
    }

    /**
     * A page that arrived from a notebook: inserted after [afterId] at the sender's size, its
     * strokes in their order, and made current, in one transaction. Returns (new id list, new id).
     */
    fun receivePage(ids: List<String>, afterId: String?, width: Float, height: Float, strokes: List<Stroke>): Pair<List<String>, String> {
        val id = newId()
        val next = ScratchPages.insertAfter(ids, afterId, id)
        val now = System.currentTimeMillis()
        val statements = ArrayList<Statement>(next.size + strokes.size + 2)
        statements += ScratchSql.insertPage(id, next.indexOf(id), width, height, now)
        statements += renumber(next)
        strokes.forEachIndexed { i, st -> statements += ScratchSql.putStroke(id, i.toLong(), st) }
        statements += ScratchSql.setCurrent(id)
        execAll(statements)
        return next to id
    }

    private fun insert(next: List<String>, id: String): Pair<List<String>, String> {
        val now = System.currentTimeMillis()
        val statements = ArrayList<Statement>(next.size + 2)
        statements += ScratchSql.insertPage(id, next.indexOf(id), 0f, 0f, now)
        statements += renumber(next)
        statements += ScratchSql.setCurrent(id)
        execAll(statements)
        return next to id
    }

    /**
     * Delete [id] and its strokes; returns (new id list, landing id), and the landing page becomes
     * current. Never below one page: a lone page keeps its id and is emptied ([ScratchPages.delete]).
     */
    fun deletePage(ids: List<String>, id: String): Pair<List<String>, String> {
        val (rest, landing) = ScratchPages.delete(ids, id)
        val statements = ArrayList<Statement>(rest.size + 2)
        if (id in ids) {
            if (rest.size == ids.size) {
                statements += ScratchSql.clearPage(id)          // the lone page is emptied, not removed
            } else {
                statements += ScratchSql.deletePage(id)          // the declared cascade takes its strokes
                statements += renumber(rest)
            }
        }
        statements += ScratchSql.setCurrent(landing)
        execAll(statements)
        return rest to landing
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Every page's position, for the list as it now stands. Page counts are tens — renumber all. */
    private fun renumber(ids: List<String>): List<Statement> = ids.mapIndexed { i, id -> ScratchSql.position(id, i) }

    companion object {
        private const val TAG = "ScratchStore"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
