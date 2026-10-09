package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.InkDocument
import com.symmetricalpalmtree.soil.paper.ink.InkPage
import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.SeamLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The calendar's page — in memory, over [CalendarStore]. The screen owns the paper and the
 * chrome; this owns *which* page is showing (a [CalendarTarget]), whether its rows exist yet, and
 * its size; **what is on the page** — the strokes, the op log, the re-flush rule and the four
 * stroke-level replays — is `:paper`'s [InkDocument], shared with the Scratch Pad so the two
 * never drift.
 *
 * **Rows are minted on the first stroke, never on open.** [show] reads what is there and writes
 * nothing but the bookmark (and not that for a screen a link opened, [writesBookmark]); a page with no row is shown blank at the surface's size with ids
 * minted in memory. The flush that carries the page's first `Put` leads with the two
 * `INSERT OR IGNORE`s ([CalendarStore.mintRows]) — a flush that is nothing but `DELETE`s (a stroke
 * drawn and undone before the debounce) mints nothing, because there is nothing to keep. A page
 * that exists at `0 × 0` (a paste minted it before any screen saw it) learns the surface's size
 * the way the pad's does: one `UPDATE`, ahead of the strokes, put back if that write fails.
 *
 * [show] reads the target page **first** and flushes the departing one **second**, so the swap
 * itself has no suspension point for a commit to fall into (the pad's rule).
 *
 * **A page this showing minted is named by `(period, half)`, not by the id it minted.** Two
 * screens can each show one empty page (a `cal:` link starts a second calendar screen) and each
 * mint an id for it; `UNIQUE(periodId, half)` keeps the first row, so the other screen's strokes
 * and `updatedAt` are written against the page row resolved in the statement
 * ([CalendarSql.putStrokeOnPage]). A page read with its row keeps the row's id. [reload] picks up
 * the row's real id (and another screen's ink) when the screen comes back to the front. Every page shown is
 * remembered by its id, so an undo action recorded on another page can navigate back to it.
 *
 * **The split of threads is deliberate.** Mutations ([addStroke], [erase], [move]) are synchronous
 * and run on Main, straight out of the g-paper callbacks; everything that reaches the store is
 * `suspend` and hops to [Dispatchers.IO] for the store call itself. The screen serialises the
 * suspending half behind one mutex.
 */
class CalendarDocument(
    private var store: CalendarStore,
    /** Where the showing page's [DayMark]s come from — [EventStore] in the app, a fake in tests. */
    marks: MarkSource,
    /** Whether a navigation writes the bookmark. False for a screen a link opened: the calendar
     *  opens on the linked day "the bookmark untouched" (`docs/calsprout.md`). */
    private val writesBookmark: Boolean = true,
    /**
     * The store again, after a call failed with [StoreUnavailable] — Soil restarted and the lease
     * this document was handed died with it ([CalsproutApp.calendar] opens a fresh one). Null: no
     * second try. A store it answers that is the same one is not retried.
     */
    private val reacquire: (suspend () -> CalendarStore)? = null,
    /** The paper surface in px — the size a page with no recorded size of its own takes. */
    private val surfaceSize: () -> Pair<Float, Float>,
) : InkPage {

    /**
     * The stroke rows, by the page's id when it was read with its row, by `(period, half)` when this
     * showing minted it (the class doc). Read on Main when a flush snapshots its op log.
     */
    private val strokeSql = object : InkDocument.StrokeSql {
        override fun putStroke(pageId: String, order: Long, stroke: Stroke): Statement =
            if (idIsRow) CalendarSql.putStroke(pageId, order, stroke)
            else CalendarSql.putStrokeOnPage(store.calendarId, target.kind, target.date, target.half, order, stroke)

        override fun dropStroke(id: String): Statement = CalendarSql.dropStroke(id)
    }

    private val ink = InkDocument(strokeSql, TAG)

    private val markSource = marks

    /**
     * The showing page's events, by day — **empty before the first [show]**, and read in the same
     * IO hop as the page's strokes, so a page and its marks are never one navigation apart.
     * [CalendarActivity] bakes them into the page's template and compares them structurally in
     * its bake key.
     */
    var marks: Map<LocalDate, List<DayMark>> = emptyMap()
        private set

    /** The page showing. Set by the first [show]; the screen never asks before it. */
    lateinit var target: CalendarTarget
        private set

    val isOpen: Boolean get() = ::target.isInitialized

    /** The showing page's id — read from its row, or minted in memory for a page with none. */
    override val pageId: String get() = ink.pageId

    /** A fresh id for the period row, used only if no period row exists at flush time. */
    private var periodId: String = ""

    /** Whether the page row exists in the store (minted by a flush, a paste, or an earlier showing). */
    private var pageMinted = false

    /** Whether [pageId] is the page row's own id — true only for a page read with its row. */
    private var idIsRow = false

    /** The page's own width/height is unwritten (a minted `0 × 0` page just learned it). */
    private var sizeDirty = false

    override var pageWidth: Float = 0f
        private set
    override var pageHeight: Float = 0f
        private set

    /** Every page this showing has put on the paper, by id — where an undo entry's page is. */
    private val targetsByPage = HashMap<String, CalendarTarget>()

    override val strokes: List<Stroke> get() = ink.strokes
    val hasUnsavedChanges: Boolean get() = ink.hasUnsavedChanges || sizeDirty

    /** The order [id] sits at on the showing page, or null if it is not on it. */
    fun orderOf(id: String): Long? = ink.orderOf(id)

    // ── Showing ──────────────────────────────────────────────────────────────

    /**
     * Show [next]. The target's page **and its marks** are read, and the bookmark written, in one
     * IO hop **before** the departing page is flushed; the flush is the last suspension before the
     * in-memory swap, so a stroke committed during any other round-trip is in the op log the flush
     * writes, never in one the swap forgets. A show that throws leaves the document — and with it
     * the paper and the organizer — exactly where it was (the bookmark may already name [next]).
     *
     * Returns without a store round-trip when [next] is already showing — **unless**
     * [refreshMarks] says to re-read them, and then it is one hop that reads the marks alone: no
     * page read, no flush, no bookmark. That path is the way back from the **events screen**, the
     * one thing that changes what a page's marks are while the page itself has not moved.
     */
    suspend fun show(next: CalendarTarget, refreshMarks: Boolean = false) {
        if (isOpen && next == target) {
            if (!refreshMarks) return
            marks = io { readMarks(next) }
            return
        }
        val (stored, fresh) = io { s ->
            val page = s.readPage(next)
            val read = readMarks(next)
            if (writesBookmark) s.saveBookmark(next)
            page to read
        }
        if (isOpen) flushUntilClean()
        target = next
        marks = fresh
        land(stored)
    }

    /**
     * Read the showing page again — another screen may have written it while this one was behind
     * (a `cal:` link's calendar minting the row this one only had an id for, or ink of its own).
     * The page is flushed **first** and read **second**, and the read is put on the paper only if
     * nothing was committed during it; otherwise the page in memory stands. Returns whether the
     * page in memory changed — the screen then puts it on the paper again.
     */
    suspend fun reload(): Boolean {
        if (!isOpen) return false
        flushUntilClean()
        val t = target
        val stored = io { s -> s.readPage(t) }
        if (t != target || hasUnsavedChanges) return false
        val unchanged = stored.pageId != null && stored.pageId == pageId && sameInk(stored.strokes, ink.entries()) &&
            stored.width == pageWidth && stored.height == pageHeight
        if (unchanged || stored.pageId == null) {
            // A page with no row is a page nobody wrote: nothing to take.
            if (stored.pageId != null) idIsRow = true
            return false
        }
        land(stored)
        return true
    }

    /**
     * Whether a page read back from the store holds the same ink as [inMemory] — by stroke id and
     * order, and by what a row keeps of each stroke (style, colour, width, the points' x, y,
     * pressure and tilt). Not [Stroke] equality: a stroke written in this showing carries the pen's
     * timestamps and azimuth, which a row does not keep, so it would never compare equal to its
     * own read-back and every return to the front would repaint the page.
     */
    private fun sameInk(stored: List<Pair<Long, Stroke>>, inMemory: List<Pair<Long, Stroke>>): Boolean {
        if (stored.size != inMemory.size) return false
        for (i in stored.indices) {
            val (so, s) = stored[i]
            val (mo, m) = inMemory[i]
            if (so != mo || s.id != m.id || s.style != m.style || s.color != m.color || s.width != m.width) return false
            if (s.points.size != m.points.size) return false
            for (j in s.points.indices) {
                val a = s.points[j]
                val b = m.points[j]
                if (a.x != b.x || a.y != b.y || a.pressure != b.pressure || a.tilt != b.tilt) return false
            }
        }
        return true
    }

    /** Put [stored] in memory as the showing page. No suspension — the swap is one step. */
    private fun land(stored: CalendarStore.StoredPage) {
        val next = target
        periodId = stored.periodId ?: CalendarStore.newId()
        pageMinted = stored.pageId != null
        idIsRow = stored.pageId != null
        val id = stored.pageId ?: CalendarStore.newId()
        ink.reset(id, stored.strokes)
        targetsByPage[id] = next
        sizeDirty = false
        pageWidth = stored.width
        pageHeight = stored.height
        if (pageWidth <= 0f || pageHeight <= 0f) {
            val (w, h) = surfaceSize()
            if (w > 0f && h > 0f) {
                pageWidth = w
                pageHeight = h
                // An existing row at 0 × 0 owes the store its size; a page with no row yet carries
                // it in the mint that comes with its first stroke.
                sizeDirty = pageMinted
            }
        }
    }

    /**
     * Run [block] against the store on IO. A [StoreUnavailable] re-acquires the store once
     * ([reacquire]) and runs [block] again against the fresh one; every write is idempotent, so the
     * second run converges. Anything else, or a second failure, is thrown.
     */
    private suspend fun <T> io(block: (CalendarStore) -> T): T {
        val first = store
        try {
            return withContext(Dispatchers.IO) { block(first) }
        } catch (e: StoreUnavailable) {
            val again = reacquire ?: throw e
            val fresh = try { again() } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (_: Exception) { throw e }
            if (fresh === first) throw e
            store = fresh
            Slog.d(TAG) { "the store was re-acquired; retrying once" }
            return withContext(Dispatchers.IO) { block(fresh) }
        }
    }

    /** The marks a page of [t] shows — the range is [GridMarks]', never guessed here. Blocking. */
    private fun readMarks(t: CalendarTarget): Map<LocalDate, List<DayMark>> {
        val (from, to) = GridMarks.rangeOf(t)
        return markSource.marksFor(from, to)
    }

    // ── Mutations (Main, synchronous) ────────────────────────────────────────

    /** Take one committed stroke, at the end of the page's writing order. */
    override fun addStroke(stroke: Stroke) = ink.addStroke(stroke)

    /** Drop [ids]; returns the undo action, or null when nothing of ours was in the set. */
    override fun erase(ids: Collection<String>): InkAction.Erased? = ink.erase(ids)

    /** Translate [ids] by ([dx], [dy]). Returns the undo action, or null when nothing moved. */
    override fun move(ids: Collection<String>, dx: Float, dy: Float): InkAction.Moved? = ink.move(ids, dx, dy)

    // ── The clipboard ────────────────────────────────────────────────────────

    /** The showing page's ink, `(order, stroke)` in writing order, flushed first — what Copy page carries. */
    suspend fun captureInk(): List<Pair<Long, Stroke>> {
        flushUntilClean()
        return ink.entries()
    }

    /**
     * Ink that arrived from the clipboard onto the showing page, appended after everything on it
     * and written at once. The strokes were minted on arrival (`InkPlacement`); the
     * one undo step takes exactly them away. Null for nothing to place.
     */
    suspend fun paste(strokes: List<Stroke>): InkAction.Pasted? {
        if (strokes.isEmpty()) return null
        ink.addStrokes(strokes)
        val orders = strokes.map { orderOf(it.id) ?: 0L }
        flushUntilClean()
        return InkAction.Pasted(pageId, strokes, orders)
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    /**
     * Write the showing page until it stays written ([InkDocument.flushUntilClean]). The page's
     * rows are minted ahead of the first pass that puts a stroke; a `0 × 0` row's size leads when
     * it is owed; the page's `updatedAt` follows any stroke write. One batch, one transaction, in
     * that order — the stroke rows can only land under a page row that exists. A flush over the
     * seam's cap ([SeamLimits.MAX_BATCH_STATEMENTS]) goes as several batches in that same order.
     */
    override suspend fun flushUntilClean(maxPasses: Int): Boolean =
        ink.flushUntilClean(extraDirty = { sizeDirty }, maxPasses = maxPasses) { statements -> write(statements) }

    /** The statements a page's [strokes] flush needs around them — pure, so the shape is JVM-tested. */
    fun statementsFor(strokeStatements: List<Statement>, now: Long): List<Statement> {
        val puts = strokeStatements.any { it.sql.startsWith("INSERT") }
        val out = ArrayList<Statement>(strokeStatements.size + 3)
        if (!pageMinted && puts) {
            out += store.mintRows(target, periodId, pageId, pageWidth, pageHeight, now)
        } else if (pageMinted && sizeDirty) {
            out += CalendarSql.sizePage(pageId, pageWidth, pageHeight, now)
        }
        out += strokeStatements
        if (strokeStatements.isNotEmpty() && (pageMinted || puts)) {
            out += if (idIsRow) CalendarSql.touchPage(pageId, now)
            else CalendarSql.touchPageOf(store.calendarId, target.kind, target.date, target.half, now)
        }
        return out
    }

    private suspend fun write(strokeStatements: List<Statement>) {
        val mintedBefore = pageMinted
        val sizeBefore = sizeDirty
        val puts = strokeStatements.any { it.sql.startsWith("INSERT") }
        val all = statementsFor(strokeStatements, System.currentTimeMillis())
        sizeDirty = false
        if (all.isEmpty()) return
        try {
            // Over the seam's cap, several batches in order: the mint leads, the touch follows, and
            // every statement is idempotent, so a failure part-way is retried whole and converges.
            for (batch in batches(all)) io { s -> s.execAll(batch) }
        } catch (t: Throwable) {
            sizeDirty = sizeDirty || sizeBefore
            pageMinted = mintedBefore
            throw t
        }
        if (puts) pageMinted = true
    }

    // ── Undo / redo replay ───────────────────────────────────────────────────

    /**
     * Reverse [a]. A replay lands the document on the action's page first — an action names its
     * page, and this showing remembers every page it put on the paper — and leaves the store
     * written, so what the screen reloads afterwards is what a reopen would show. Returns false
     * when the page cannot be found (never for an action this showing recorded).
     */
    suspend fun revert(a: InkAction): Boolean {
        if (!landOn(a.pageId)) return false
        ink.revert(a)
        flushUntilClean()
        return true
    }

    /** Re-apply [a] — [revert]'s mirror. */
    suspend fun reapply(a: InkAction): Boolean {
        if (!landOn(a.pageId)) return false
        ink.reapply(a)
        flushUntilClean()
        return true
    }

    private suspend fun landOn(id: String): Boolean {
        if (id == pageId) return true
        val t = targetsByPage[id]
        if (t == null) {
            Slog.d(TAG) { "replay skipped: page $id was not shown this showing" }
            return false
        }
        show(t)
        // A page with no row keeps the id it was minted with only while it is showing; a second
        // showing mints another. The action's strokes are still what they are, so land the replay
        // on the page that is showing now.
        return true
    }

    companion object {
        private const val TAG = "CalendarDocument"

        /** [statements] as the seam takes them: in order, at most [cap] to a batch. Pure. */
        fun batches(statements: List<Statement>, cap: Int = SeamLimits.MAX_BATCH_STATEMENTS): List<List<Statement>> =
            if (statements.size <= cap) listOf(statements) else statements.chunked(cap)
    }
}
