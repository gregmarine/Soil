package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.soil.paper.store.Statement
import java.time.LocalDate

/**
 * One event write in three parts, in the order they are sent:
 *
 * - [additions] — statements that only **add rows this write minted**: the event row's
 *   `INSERT OR IGNORE` (a no-op on an existing event; the children's foreign key needs it first),
 *   then the note's additions ([NoteWrite.additions]);
 * - [noteMutations] — the note's op-log statements over strokes that existed before (drops,
 *   re-puts of moved strokes);
 * - [rewrites] — everything that changes a **pre-existing event row**: the row's field update
 *   and its three child sets, and at THIS / FOLLOWING scope the original's exception or truncation.
 *
 * The seam carries the whole write as one transaction, so the three parts are one `exec`: it
 * lands or none of it does. The order is kept from Notesprout SN all the same — the parent row
 * ahead of its children is what the foreign keys need.
 */
class EventWrite(val additions: List<Statement>, val noteMutations: List<Statement>, val rewrites: List<Statement>) {

    /** Every statement in send order. */
    val statements: List<Statement> get() = additions + noteMutations + rewrites

    /** The same write with [more] appended to the rewrites — an original's exception or truncation. */
    fun rewriting(more: List<Statement>): EventWrite = EventWrite(additions, noteMutations, rewrites + more)
}

/**
 * What a save, a delete and the three recurring **scopes** come to, as statement lists — pure, so
 * the shape of every write is pinned by `EventWritesTest` without a store. Notesprout SN's.
 *
 * **Order inside a write is load-bearing** ([EventWrite]): the event row's `INSERT OR IGNORE`
 * leads (the children's foreign key needs its parent) with the note's additions behind it, then
 * the note's mutations, then the row's own rewrite — its fields and each child set emptied and
 * rewritten — and, at THIS / FOLLOWING scope, the original's exception or truncation **last of
 * all**. The whole write is ONE transaction, which is what makes "Cancel wrote nothing" and
 * "Save wrote everything" both true.
 *
 * **Every occurrence is computed on the ORIGINAL event's rule**: the person tapped an occurrence
 * of the series as it *is*, and an edit that moved the date must still remove the instance they
 * were looking at.
 *
 * `now` is a parameter everywhere so a test can pin it, and nothing here reads a clock.
 */
class EventWrites(private val calendarId: String) {

    /**
     * A whole event as it should now read, as an [EventWrite]: the insert and [note]'s additions,
     * [note]'s mutations, then the row's update and its three child sets rewritten. Idempotent
     * end to end, so a retried batch converges on the same rows.
     */
    fun save(e: Event, now: Long, note: NoteWrite = NoteWrite.NONE): EventWrite {
        val rewrites = ArrayList<Statement>(8)
        rewrites += EventSql.updateEvent(e, now)
        rewrites += EventSql.clearWeekdays(e.id)
        for (d in e.recurrence?.weekdays.orEmpty().sorted()) rewrites += EventSql.insertWeekday(e.id, d)
        rewrites += EventSql.clearExceptions(e.id)
        for (d in e.exceptions.sorted()) rewrites += EventSql.insertException(e.id, d)
        rewrites += EventSql.clearReminders(e.id)
        for (r in e.reminders) rewrites += EventSql.insertReminder(e.id, r.amount, r.unit)
        return EventWrite(listOf(EventSql.insertEvent(calendarId, e, now)) + note.additions, note.mutations, rewrites)
    }

    /** The whole event, cascade and all. */
    fun delete(id: String): List<Statement> = listOf(EventSql.deleteEvent(id))

    /**
     * Deleting as seen on [viewedDay], at [scope]. Null means **nothing to do**: a recurring event
     * the viewed day maps to no occurrence of — which the screens can only reach by racing an
     * edit, and which must not be answered by deleting the series.
     */
    fun deleteWithScope(scope: Scope, event: Event, viewedDay: LocalDate, now: Long): List<Statement>? {
        if (!event.recurring || scope == Scope.ALL) return delete(event.id)
        val occurrence = Recurrence.occurrenceStartCovering(event, viewedDay) ?: return null
        return when (scope) {
            Scope.THIS -> exceptionOn(event.id, occurrence, now)
            // A split at (or before) the first occurrence leaves nothing behind — that is a whole delete.
            Scope.FOLLOWING ->
                if (!occurrence.isAfter(event.startDate)) delete(event.id)
                else listOf(EventSql.truncateEvent(event.id, occurrence.minusDays(1), now))
            Scope.ALL -> delete(event.id)   // unreachable; the guard above answered it
        }
    }

    /**
     * Editing as seen on [viewedDay], at [scope]. Null means nothing to do, as in [deleteWithScope].
     *
     * - **[Scope.THIS]** — the occurrence leaves the series (an exception at its *original* start,
     *   written last) and comes back as a standalone one-off under [newId], carrying the edited
     *   fields, the reminders **and the note**. Changing the date in the editor therefore *moves*
     *   just that occurrence;
     * - **[Scope.FOLLOWING]** — the original ends the day before the occurrence (written last)
     *   and a fresh series starts under [newId] carrying the exceptions dated **at or after** the
     *   split. A COUNT rule the editor handed back **unchanged** carries the *remaining* count
     *   (the original's minus the starts ahead of the split, [Recurrence.countBefore]), so "10
     *   times" split at #5 is 4 + 6, not 4 + 10; a rule the person changed is theirs, count included;
     * - **[Scope.ALL]**, a non-recurring original, or a brand-new event — [editSeries], in place.
     */
    fun editWithScope(
        scope: Scope,
        original: Event?,
        edited: Event,
        viewedDay: LocalDate,
        newId: String,
        now: Long,
        note: NoteWrite = NoteWrite.NONE,
    ): EventWrite? {
        if (original == null || !original.recurring || scope == Scope.ALL) {
            return editSeries(original, edited, viewedDay, now, note)
        }
        val occurrence = Recurrence.occurrenceStartCovering(original, viewedDay) ?: return null
        return when (scope) {
            Scope.THIS ->
                save(edited.copy(id = newId, recurrence = null, exceptions = emptySet(), createdAt = now), now, note)
                    .rewriting(exceptionOn(original.id, occurrence, now))

            Scope.FOLLOWING ->
                if (!occurrence.isAfter(original.startDate)) editSeries(original, edited, viewedDay, now, note)
                else save(
                    edited.copy(
                        id = newId,
                        recurrence = remainingRule(original, edited, occurrence),
                        exceptions = original.exceptions.filterTo(HashSet()) { !it.isBefore(occurrence) },
                        createdAt = now,
                    ),
                    now, note,
                ).rewriting(listOf(EventSql.truncateEvent(original.id, occurrence.minusDays(1), now)))

            Scope.ALL -> editSeries(original, edited, viewedDay, now, note)   // unreachable
        }
    }

    /**
     * The whole series, edited in place.
     *
     * Two things carry forward from [original]: the **exceptions** (occurrences already removed
     * stay removed), and the **anchor**. The editor pre-fills its dates from the *tapped
     * occurrence*, so saving those dates back unchanged would silently re-anchor the series — a
     * birthday would forget the year it started. When the dates come back exactly as the prefill
     * left them the stored anchor is kept; a deliberately changed date re-anchors, which is what
     * moving a series means.
     */
    fun editSeries(
        original: Event?,
        edited: Event,
        viewedDay: LocalDate,
        now: Long,
        note: NoteWrite = NoteWrite.NONE,
    ): EventWrite {
        val exceptions = if (edited.recurrence != null) original?.exceptions.orEmpty() else emptySet()
        val prefillStart = original?.takeIf { it.recurring }?.let { Recurrence.occurrenceStartCovering(it, viewedDay) }
        val untouched = original != null && prefillStart != null &&
            edited.startDate == prefillStart && edited.endDate == prefillStart.plusDays(original.spanDays)
        val anchored =
            if (untouched) edited.copy(startDate = original.startDate, endDate = original.endDate) else edited
        return save(anchored.copy(exceptions = exceptions), now, note)
    }

    /**
     * The successor's rule for a FOLLOWING split: [edited]'s own, except that a COUNT rule handed
     * back exactly as the editor prefilled it (the same rule object as [original]'s — a moved
     * date is not a changed rule) keeps only the occurrences the split left, never fewer than one.
     */
    private fun remainingRule(original: Event, edited: Event, occurrence: LocalDate): RecurrenceRule? {
        val rule = edited.recurrence ?: return null
        val count = rule.endCount
        if (rule != original.recurrence || rule.endMode != EndMode.COUNT || count == null) return rule
        return rule.copy(endCount = (count - Recurrence.countBefore(rule, original.startDate, occurrence)).coerceAtLeast(1))
    }

    /** One occurrence out of a series: the exception row, and the parent stamped so a reader can
     *  see the series changed even though none of its own columns did. */
    private fun exceptionOn(eventId: String, occurrenceStart: LocalDate, now: Long): List<Statement> =
        listOf(EventSql.insertException(eventId, occurrenceStart), EventSql.touchEvent(eventId, now))

    companion object {
        /**
         * Which id an [editWithScope] lands the edited fields under — [edited]'s own for an
         * in-place series edit, [newId] for an override or a new series; null exactly when
         * [editWithScope] is. The store needs the answer to know whether it minted a row, and one
         * function deciding it is what keeps the two from disagreeing.
         */
        fun editLandsUnder(scope: Scope, original: Event?, edited: Event, viewedDay: LocalDate, newId: String): String? {
            if (original == null || !original.recurring || scope == Scope.ALL) return edited.id
            val occurrence = Recurrence.occurrenceStartCovering(original, viewedDay) ?: return null
            if (scope == Scope.FOLLOWING && !occurrence.isAfter(original.startDate)) return edited.id
            return newId
        }
    }
}
