package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.core.CalendarDates
import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.ink.InkStore
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import java.time.LocalDate
import java.util.UUID

/**
 * The calendar's tables over the app store Soil lends ([CalendarSchema]), on `:paper`'s [InkStore]
 * base, **for one calendar** ([calendarId]). **Blocking** — every call runs on `Dispatchers.IO`,
 * never Main. Calsprout writes nothing to disk itself: the store is Soil's.
 *
 * Every SQL string lives in [CalendarSql]; every write goes through `execAll`, one transaction
 * (the seam carries a page whole — there is no batch to split, as there was over SN's binder).
 *
 * **Reading a page writes nothing.** [readPage] answers what is there — a period row or not, a
 * page row or not, its strokes — and the document mints the missing rows only in the flush that
 * carries the first stroke. Browsing empty months leaves the row counts exactly where they were,
 * which is what [counts] is for: the walk's proof is that log line.
 */
class CalendarStore(
    store: RowStore,
    val calendarId: String = CalendarSchema.DEFAULT_CALENDAR,
) : InkStore(store, TAG) {

    /** The bookmark: the page the organizer was left open at. */
    class Position(val kind: Int, val date: LocalDate, val half: Int) {
        val target: CalendarTarget get() = CalendarTarget.of(kind, date, half)
    }

    /**
     * One page as it is stored — or is not. [periodId] is null when no period row exists for the
     * target's `(kind, date)`, [pageId] null when no page row exists under it; a null page carries
     * no size and no strokes. The document mints what is missing on the first stroke.
     */
    class StoredPage(val periodId: String?, val pageId: String?, val width: Float, val height: Float, val strokes: List<Pair<Long, Stroke>>)

    class Counts(val periods: Long, val pages: Long, val strokes: Long, val events: Long)

    // ── Opening ──────────────────────────────────────────────────────────────

    /** Mint the calendar's own row if it is missing (`INSERT OR IGNORE`): the first open of all. */
    fun ensureCalendar() = guard {
        val exists = store.query(CalendarSql.selectCalendar(calendarId)).rows.isNotEmpty()
        if (!exists) run(listOf(CalendarSql.insertCalendar(calendarId, DEFAULT_NAME, System.currentTimeMillis())))
    }

    /**
     * The bookmark. A bookmark that does not parse — a row missing, a kind out of range, a date
     * that is not an ISO day or not normalized for its kind, a half that is not legal — reads as
     * **no bookmark** (null) rather than throwing: the screen opens on today's Month, which is the
     * first-run answer anyway.
     */
    fun readBookmark(): Position? = guard {
        val rows = store.query(CalendarSql.selectState(calendarId)).rows
        val state = HashMap<String, String>(rows.size)
        for (r in rows) state[r.text("key")] = r.text("value")
        val kind = state[CalendarSql.KEY_LAST_VIEW]?.toIntOrNull() ?: return@guard null
        val date = state[CalendarSql.KEY_LAST_DATE]?.let { CalendarDates.parse(it) } ?: return@guard null
        val half = state[CalendarSql.KEY_LAST_HALF]?.toIntOrNull() ?: return@guard null
        val valid = runCatching { CalendarTarget.requireValid(kind, CalendarDates.format(date), half) }.isSuccess
        if (!valid) return@guard null
        Position(kind, date, half)
    }

    /** The row counts — logged at open, never used for anything else. */
    fun counts(): Counts = guard {
        val row = store.query(CalendarSql.selectCounts()).rows.first()
        Counts(row.long("periods"), row.long("pages"), row.long("strokes"), row.long("events"))
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * The page for [target] as stored. Two reads when the page exists (the join, then the
     * strokes), two when it does not (the join comes back empty; the period is looked up on its
     * own so the document knows whether the day's other half already minted it). Writes nothing.
     */
    fun readPage(target: CalendarTarget): StoredPage = guard {
        val header = readHeader(target)
        val id = header.pageId ?: return@guard header
        StoredPage(
            periodId = header.periodId,
            pageId = id,
            width = header.width,
            height = header.height,
            strokes = readStrokes(id, CalendarSql.selectStrokes(id)),
        )
    }

    /** The page's rows without its ink — which rows exist and the page's size. */
    fun readHeader(target: CalendarTarget): StoredPage = guard {
        val page = store.query(CalendarSql.selectPage(calendarId, target.kind, target.date, target.half)).rows.firstOrNull()
        if (page == null) {
            val period = store.query(CalendarSql.selectPeriod(calendarId, target.kind, target.date)).rows.firstOrNull()?.text("id")
            return@guard StoredPage(period, null, 0f, 0f, emptyList())
        }
        StoredPage(
            periodId = page.text("periodId"),
            pageId = page.text("id"),
            width = page.real("width").toFloat(),
            height = page.real("height").toFloat(),
            strokes = emptyList(),
        )
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /** The bookmark — written on every navigation, one batch of three rows. */
    fun saveBookmark(target: CalendarTarget) = execAll(
        listOf(
            CalendarSql.setState(calendarId, CalendarSql.KEY_LAST_VIEW, target.kind.toString()),
            CalendarSql.setState(calendarId, CalendarSql.KEY_LAST_DATE, target.date),
            CalendarSql.setState(calendarId, CalendarSql.KEY_LAST_HALF, target.half.toString()),
        ),
    )

    /**
     * The statements that mint a page's rows if they are missing — the lead of the flush that
     * carries a page's first stroke. Both are `INSERT OR IGNORE`, so a row that is already there is
     * left exactly as it is; the page's `periodId` is resolved from `(calendarId, kind, date)`
     * inside the statement, so [periodId] only has to be *a* fresh id for the case where no period
     * row exists.
     */
    fun mintRows(target: CalendarTarget, periodId: String, pageId: String, width: Float, height: Float, now: Long): List<Statement> =
        listOf(
            CalendarSql.insertPeriod(periodId, calendarId, target.kind, target.date),
            CalendarSql.insertPage(pageId, calendarId, target.kind, target.date, target.half, width, height, now),
        )

    companion object {
        private const val TAG = "CalendarStore"
        private const val DEFAULT_NAME = "Calendar"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
