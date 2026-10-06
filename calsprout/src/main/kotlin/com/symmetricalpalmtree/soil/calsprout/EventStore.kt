package com.symmetricalpalmtree.soil.calsprout

import android.util.Log
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.ink.InkStore
import com.symmetricalpalmtree.soil.paper.store.Row
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import java.time.LocalDate

/**
 * The events half of the calendar's store, on `:paper`'s [InkStore] — the same base the pages
 * half sits on — **for one calendar** ([calendarId]). Notesprout SN's, less what its binder
 * needed: the seam carries a write whole, so a save is one transaction and there is nothing to
 * compensate.
 *
 * **Blocking** — every call runs on `Dispatchers.IO`, never Main. Every failure is
 * `StoreUnavailable`; the two refusals that are *not* store failures ([EventRules.Problem]) are
 * `IllegalArgumentException`, raised before anything is sent.
 *
 * **No read carries an `IN (…)` list** ([EventSql]). A day (or a month's range) costs six queries
 * whatever it holds: the one-offs the range overlaps and their reminders, then the recurring set
 * and its three child sets, each by a JOIN. Expansion happens in Kotlin, because no `WHERE` can
 * answer "does this rule land on that day".
 *
 * **A bad row is a dropped event, never a lost day** ([EventRows]): undecodable rows are counted
 * and logged once per read, and the day still lists everything else. Logs carry counts, ids and
 * durations — **never a title or a note**: event text is user content.
 */
class EventStore(
    store: RowStore,
    val calendarId: String = CalendarSchema.DEFAULT_CALENDAR,
    private val clock: () -> Long = System::currentTimeMillis,
) : InkStore(store, TAG), MarkSource {

    private val writes = EventWrites(calendarId)

    // ── Reading ──────────────────────────────────────────────────────────────

    /**
     * The **grid's** read: every day in `[from, to]` that holds anything, mapped to its
     * [DayMark]s in [EventOrder.DAY]. Exactly [eventsInRange]'s six queries and Kotlin expansion —
     * a Month page's 42 cells cost what one day costs — narrowed to the four fields the template
     * draws.
     */
    override fun marksFor(from: LocalDate, to: LocalDate): Map<LocalDate, List<DayMark>> {
        val byDay = eventsInRange(from, to)
        val marks = LinkedHashMap<LocalDate, List<DayMark>>(byDay.size)
        for ((day, events) in byDay) marks[day] = events.map(DayMark::of)
        // Counts only — an event's title is the person's own words.
        Slog.d(TAG) { "marks $from..$to: ${marks.size} day(s), ${marks.values.sumOf { it.size }} mark(s)" }
        return marks
    }

    /**
     * Every day in `[from, to]` that holds anything, mapped to its events in [EventOrder.DAY].
     * Ascending, and **days with nothing are absent** — a month grid asks about 42 days and
     * usually cares about four of them.
     */
    fun eventsInRange(from: LocalDate, to: LocalDate): Map<LocalDate, List<Event>> = guard {
        val raw = readOneOffs(EventSql.selectOneOffsOverlapping(calendarId, from, to), EventSql.selectRemindersOverlapping(calendarId, from, to))
        val series = recurringSeries()
        val oneOffs = raw.decoded(series)
        reportDropped(series.dropped + oneOffs.dropped)
        val recurring = series.events

        // Each recurring series is expanded over the whole range ONCE: a COUNT rule enumerates
        // its own N inside `occursOn`, so asking it per day would make a Month grid's 42 cells
        // regenerate a "100 times" series 42 times.
        val recurringDays = recurring.map { it to Recurrence.coveredDays(it, from, to) }
        val out = LinkedHashMap<LocalDate, List<Event>>()
        var day = from
        while (!day.isAfter(to)) {
            val onDay = ArrayList<Event>()
            for (e in oneOffs.events) if (!e.startDate.isAfter(day) && !e.endDate.isBefore(day)) onDay += e
            for ((e, days) in recurringDays) if (day in days) onDay += e
            if (onDay.isNotEmpty()) out[day] = onDay.sortedWith(EventOrder.DAY)
            day = day.plusDays(1)
        }
        out
    }

    /** One day's events, in [EventOrder.DAY]. */
    fun eventsOn(day: LocalDate): List<Event> = eventsInRange(day, day)[day].orEmpty()

    /** The **Upcoming** look-ahead for [day] ([Upcoming]) — the one-offs starting inside the
     *  window and the whole recurring set, each probed against its own reminders. */
    fun upcomingOn(day: LocalDate): List<UpcomingEvent> = guard {
        val raw = lookAhead(day)
        val series = recurringSeries()
        val ahead = raw.decoded(series)
        reportDropped(series.dropped + ahead.dropped)
        Upcoming.forDay(day, ahead.events, series.events)
    }

    /** One day's list and its [Upcoming] look-ahead, in **one** pass: the recurring set — four of
     *  the six queries each read costs — is read and decoded once for the pair. */
    fun dayAndUpcoming(day: LocalDate): DayAndUpcoming = guard {
        val rawDay = readOneOffs(EventSql.selectOneOffsOverlapping(calendarId, day, day), EventSql.selectRemindersOverlapping(calendarId, day, day))
        val rawAhead = lookAhead(day)
        val series = recurringSeries()
        val onDay = rawDay.decoded(series)
        val ahead = rawAhead.decoded(series)
        reportDropped(series.dropped + onDay.dropped + ahead.dropped)

        val today = ArrayList<Event>()
        for (e in onDay.events) if (!e.startDate.isAfter(day) && !e.endDate.isBefore(day)) today += e
        for (e in series.events) if (Recurrence.occursOn(e, day)) today += e
        DayAndUpcoming(today.sortedWith(EventOrder.DAY), Upcoming.forDay(day, ahead.events, series.events))
    }

    /** [dayAndUpcoming]'s two answers. */
    data class DayAndUpcoming(val today: List<Event>, val upcoming: List<UpcomingEvent>)

    /** One event by id, with its children; null when there is no such row or it will not decode. */
    fun get(id: String): Event? = guard {
        val row = store.query(EventSql.selectEvent(id)).rows.firstOrNull() ?: return@guard null
        val weekdays = LinkedHashSet<Int>()
        for (r in store.query(EventSql.selectWeekdays(id)).rows) EventRows.weekday(r)?.let { weekdays += it }
        val exceptions = LinkedHashSet<LocalDate>()
        for (r in store.query(EventSql.selectExceptions(id)).rows) EventRows.exceptionDate(r)?.let { exceptions += it }
        val reminders = ArrayList<Reminder>()
        for (r in store.query(EventSql.selectReminders(id)).rows) EventRows.reminder(r)?.let { reminders += it }
        EventRows.decode(row, weekdays, exceptions, EventRules.normalize(reminders))
    }

    /** The event's note, in writing order. */
    fun readNote(eventId: String): List<Pair<Long, Stroke>> = guard {
        readStrokes(eventId, NoteSql.selectStrokes(eventId))
    }

    /** Where new ink on the note starts numbering; `-1` when it holds none. */
    fun noteMaxOrder(eventId: String): Long = guard {
        store.query(NoteSql.selectMaxOrder(eventId)).rows.firstOrNull()?.long("maxOrder") ?: -1L
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    /**
     * Save [e] — the row, its three child sets and [note]'s statements, one statement list and
     * therefore one transaction. The caps run first ([EventRules]) whatever the editor already
     * did, and a [EventRules.Problem] is an `IllegalArgumentException` rather than a silent write:
     * a row that occurs on no day is invisible to every query, which would look exactly like a
     * lost save.
     */
    fun save(e: Event, isNew: Boolean, note: NoteWrite = NoteWrite.NONE) {
        val event = refuseProblems(e)
        val now = clock()
        execAll(writes.save(event, now, note).statements)
        Slog.d(TAG) { "save ${event.id}: ${if (isNew) "new" else "existing"}, ${note.statements.size} note statement(s)" }
    }

    /** Delete at [scope] as seen on [viewedDay]; false when the day maps to no occurrence and
     *  there is nothing to do (never a whole-series delete by accident). */
    fun delete(scope: Scope, event: Event, viewedDay: LocalDate): Boolean {
        val statements = writes.deleteWithScope(scope, event, viewedDay, clock()) ?: return false
        execAll(statements)
        Slog.d(TAG) { "delete ${event.id} at $scope: ${statements.size} statement(s)" }
        return true
    }

    /**
     * Edit at [scope] as seen on [viewedDay]. Answers **the id the edited fields landed under** —
     * the original's for an in-place series edit, a freshly minted one for a "this occurrence"
     * override or a new series — or null when the day maps to no occurrence and there is nothing
     * to do. [original] is null for a brand-new event, which is always a plain save.
     *
     * [note] is asked **for that id**: the note's own op log when the fields land in place, a
     * whole copy under fresh stroke ids when they land under a new one — the screen cannot know
     * which until the scope has been resolved here, so the store asks rather than takes
     * ([NoteWrite]). [newId] is the id a new row would take; a caller passes its own so it can
     * build both answers on Main *before* the IO hop.
     */
    fun edit(
        scope: Scope,
        original: Event?,
        edited: Event,
        viewedDay: LocalDate,
        newId: String = newId(),
        note: (landedUnder: String) -> NoteWrite = { NoteWrite.NONE },
    ): String? {
        val event = refuseProblems(edited)
        val landedUnder = EventWrites.editLandsUnder(scope, original, event, viewedDay, newId) ?: return null
        val now = clock()
        val write = note(landedUnder)
        val statements = writes.editWithScope(scope, original, event, viewedDay, newId, now, write) ?: return null
        execAll(statements.statements)
        Slog.d(TAG) { "edit ${event.id} at $scope → $landedUnder: ${statements.statements.size} statement(s)" }
        return landedUnder
    }

    /** A fresh event (or note-page) id. */
    fun newId(): String = CalendarStore.newId()

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** [EventRules] applied, and its [EventRules.Problem]s refused — outside `guard`, because
     *  these are the caller's mistake and not the store being gone. */
    private fun refuseProblems(e: Event): Event {
        val event = EventRules.normalize(e)
        EventRules.problem(event)?.let { throw IllegalArgumentException("event refused: $it") }
        return event
    }

    /** A decoded set of event rows and how many of them would not decode. */
    private class Decoded(val events: List<Event>, val dropped: Int)

    /** The whole recurring set with its three child sets — the four queries the day read and the
     *  look-ahead both need, so a caller that wants both pays for them once. */
    private fun recurringSeries(): Series {
        val rows = store.query(EventSql.selectRecurring(calendarId)).rows
        val weekdays = weekdaysBy()
        val exceptions = exceptionsBy()
        val reminders = remindersBy(EventSql.selectRecurringReminders(calendarId))
        val decoded = decodeAll(rows, weekdays, exceptions, reminders)
        return Series(decoded.events, decoded.dropped, weekdays, exceptions)
    }

    private class Series(
        val events: List<Event>,
        val dropped: Int,
        val weekdays: Map<String, Set<Int>>,
        val exceptions: Map<String, Set<LocalDate>>,
    )

    /** One-off rows read but not yet decoded — a one-off carries no weekdays and no exceptions,
     *  but [decode] is one function for both kinds, so the two maps the series read produces are
     *  what it waits for. Reading first keeps the pinned query order. */
    private class RawOneOffs(val rows: List<Row>, val reminders: Map<String, List<Reminder>>)

    private fun readOneOffs(rows: Statement, reminders: Statement): RawOneOffs =
        RawOneOffs(store.query(rows).rows, remindersBy(reminders))

    private fun RawOneOffs.decoded(series: Series): Decoded =
        decodeAll(rows, series.weekdays, series.exceptions, reminders)

    private fun lookAhead(day: LocalDate): RawOneOffs {
        val horizon = day.plusDays(Upcoming.MAX_LOOKAHEAD_DAYS.toLong())
        return readOneOffs(
            EventSql.selectOneOffsStartingIn(calendarId, day, horizon),
            EventSql.selectRemindersStartingIn(calendarId, day, horizon),
        )
    }

    private fun decodeAll(
        rows: List<Row>,
        weekdays: Map<String, Set<Int>>,
        exceptions: Map<String, Set<LocalDate>>,
        reminders: Map<String, List<Reminder>>,
    ): Decoded {
        val out = ArrayList<Event>(rows.size)
        var dropped = 0
        for (row in rows) {
            val e = decode(row, weekdays, exceptions, reminders)
            if (e == null) dropped++ else out += e
        }
        return Decoded(out, dropped)
    }

    /** Counts only — an event's title is the person's own words. */
    private fun reportDropped(dropped: Int) {
        if (dropped > 0) Log.w(TAG, "$dropped event row(s) dropped")
    }

    private fun decode(
        row: Row,
        weekdays: Map<String, Set<Int>>,
        exceptions: Map<String, Set<LocalDate>>,
        reminders: Map<String, List<Reminder>>,
    ): Event? {
        val id = try {
            row.text("id")
        } catch (e: Exception) {
            return null
        }
        return EventRows.decode(row, weekdays[id].orEmpty(), exceptions[id].orEmpty(), reminders[id].orEmpty())
    }

    private fun weekdaysBy(): Map<String, Set<Int>> {
        val out = HashMap<String, MutableSet<Int>>()
        for (row in store.query(EventSql.selectRecurringWeekdays(calendarId)).rows) {
            val id = eventIdOf(row) ?: continue
            EventRows.weekday(row)?.let { out.getOrPut(id) { LinkedHashSet() } += it }
        }
        return out
    }

    private fun exceptionsBy(): Map<String, Set<LocalDate>> {
        val out = HashMap<String, MutableSet<LocalDate>>()
        for (row in store.query(EventSql.selectRecurringExceptions(calendarId)).rows) {
            val id = eventIdOf(row) ?: continue
            EventRows.exceptionDate(row)?.let { out.getOrPut(id) { LinkedHashSet() } += it }
        }
        return out
    }

    /** The reminders of whatever set [statement] selects, grouped by event and normalized — one
     *  order and one cap, wherever the rows came from. */
    private fun remindersBy(statement: Statement): Map<String, List<Reminder>> {
        val out = HashMap<String, MutableList<Reminder>>()
        for (row in store.query(statement).rows) {
            val id = eventIdOf(row) ?: continue
            EventRows.reminder(row)?.let { out.getOrPut(id) { ArrayList() } += it }
        }
        return out.mapValues { (_, list) -> EventRules.normalize(list) }
    }

    private fun eventIdOf(row: Row): String? = try {
        row.text("eventId")
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "EventStore"
    }
}
