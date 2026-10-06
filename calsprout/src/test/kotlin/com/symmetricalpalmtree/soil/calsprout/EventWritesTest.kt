package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Statement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** What a Save, a Delete and the three recurring scopes come to, as exact statement lists. */
class EventWritesTest {

    private val writes = EventWrites(CalendarSchema.DEFAULT_CALENDAR)

    private val now = 99L
    private val anchor = LocalDate.of(2026, 9, 2)
    private val viewed = LocalDate.of(2026, 9, 16)

    /** Every 7 days from Sep 2, with one occurrence already removed. */
    private val series = testEvent(
        id = "e1",
        title = "Standup",
        start = anchor,
        recurrence = RecurrenceRule(Freq.DAILY, interval = 7),
        exceptions = setOf(LocalDate.of(2026, 9, 23)),
        reminders = listOf(Reminder(1, ReminderUnit.DAYS)),
    )

    /** A note write as `NoteSurface.write` hands one in: a put of a stroke this save minted, and a
     *  drop of one that was loaded. */
    private fun notePuts(): NoteWrite = NoteWrite.inPlace(
        listOf(
            Statement("INSERT OR REPLACE INTO note_stroke (id, eventId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)", "minted"),
            Statement("DELETE FROM note_stroke WHERE id = ?", "loaded"),
        ),
        listOf("minted"),
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value

    private fun stroke(id: String) = Stroke(
        id = id,
        points = List(3) { StrokePoint(it.toFloat(), it * 2f, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK,
        width = 3f,
    )

    /** The two long statements read as their heads; every other one is short enough to pin whole. */
    private fun shapeOf(s: Statement): String = when {
        s.sql.startsWith("INSERT OR IGNORE INTO event (") -> "INSERT OR IGNORE INTO event ("
        s.sql.startsWith("UPDATE event SET type = ") -> "UPDATE event SET type = "
        else -> s.sql
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    @Test
    fun aSaveIsTheAdditionsThenTheNotesMutationsThenTheRowRewrite() {
        val e = testEvent(
            id = "e1",
            start = anchor,
            recurrence = RecurrenceRule(Freq.WEEKLY, weekdays = setOf(3, 1)),
            exceptions = setOf(LocalDate.of(2026, 9, 9)),
            reminders = listOf(Reminder(1, ReminderUnit.DAYS), Reminder(2, ReminderUnit.WEEKS)),
        )
        val batch = writes.save(e, now, notePuts()).statements
        assertEquals(
            listOf(
                // Additions: the row (a no-op on an existing event) and the minted stroke …
                "INSERT OR IGNORE INTO event (",
                "INSERT OR REPLACE INTO note_stroke (id, eventId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                // … then the note's own mutations …
                "DELETE FROM note_stroke WHERE id = ?",
                // … and the row's rewrite last, so a failed batch ahead of it leaves the event as it was.
                "UPDATE event SET type = ",
                "DELETE FROM event_weekday WHERE eventId = ?",
                "INSERT OR IGNORE INTO event_weekday (eventId, weekday) VALUES (?, ?)",
                "INSERT OR IGNORE INTO event_weekday (eventId, weekday) VALUES (?, ?)",
                "DELETE FROM event_exception WHERE eventId = ?",
                "INSERT OR IGNORE INTO event_exception (eventId, date) VALUES (?, ?)",
                "DELETE FROM event_reminder WHERE eventId = ?",
                "INSERT OR IGNORE INTO event_reminder (eventId, amount, unit) VALUES (?, ?, ?)",
                "INSERT OR IGNORE INTO event_reminder (eventId, amount, unit) VALUES (?, ?, ?)",
            ),
            batch.map(::shapeOf),
        )
        val write = writes.save(e, now, notePuts())
        assertEquals(listOf("INSERT OR IGNORE INTO event (", "INSERT OR REPLACE INTO note_stroke (id, eventId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)"), write.additions.map(::shapeOf))
        assertEquals(listOf("DELETE FROM note_stroke WHERE id = ?"), write.noteMutations.map(::shapeOf))
        assertEquals(9, write.rewrites.size)
        // Weekdays go in sorted, so a retried batch writes the same rows in the same order.
        assertEquals(listOf(1L, 3L), batch.filter { it.sql.contains("event_weekday (") }.map { (it.args[1] as Cell.Integer).value })
        assertEquals(2, batch.count { it.sql.contains("note_stroke") })
    }

    @Test
    fun aOneOffStillClearsEveryChildSet() {
        val batch = writes.save(testEvent(id = "one"), now).statements
        assertEquals(
            listOf(
                "DELETE FROM event_weekday WHERE eventId = ?",
                "DELETE FROM event_exception WHERE eventId = ?",
                "DELETE FROM event_reminder WHERE eventId = ?",
            ),
            batch.map { it.sql }.filter { it.startsWith("DELETE") },
        )
        assertEquals(5, batch.size)
    }

    // ── Delete ───────────────────────────────────────────────────────────────


    @Test
    fun aNoteWriteSplitsItsOpLogByWhatThisSaveMinted() {
        val put = { id: String -> Statement("INSERT OR REPLACE INTO note_stroke (id, eventId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)", id, "e1") }
        val drop = { id: String -> Statement("DELETE FROM note_stroke WHERE id = ?", id) }
        val write = NoteWrite.inPlace(listOf(put("a"), drop("b"), put("c"), put("d")), minted = listOf("a", "d"))
        assertEquals(listOf("a", "d"), write.additions.map { text(it.args[0]) })
        // A re-put of a loaded stroke (a lasso move) is a mutation, as a drop is.
        assertEquals(listOf("b", "c"), write.mutations.map { text(it.args[0]) })
        // Sent additions-first, whatever order the log held them in.
        assertEquals(listOf("a", "d", "b", "c"), write.statements.map { text(it.args[0]) })
        assertEquals(listOf("a", "d"), write.mintedStrokeIds)

        // A whole copy under a new id is all additions — the row's own delete is its compensation.
        val copy = NoteWrite.copy(listOf(0L to stroke("x"), 1L to stroke("y")), "new") { "m" }
        assertEquals(2, copy.additions.size)
        assertTrue(copy.mutations.isEmpty() && copy.mintedStrokeIds.isEmpty())
        assertTrue(NoteWrite.NONE.statements.isEmpty())
    }

    @Test
    fun deletingANonRecurringEventOrTheWholeSeriesIsOneStatement() {
        assertEquals(
            listOf("DELETE FROM event WHERE id = ?"),
            writes.deleteWithScope(Scope.THIS, testEvent(id = "one"), viewed, now)!!.map { it.sql },
        )
        assertEquals(
            listOf("DELETE FROM event WHERE id = ?"),
            writes.deleteWithScope(Scope.ALL, series, viewed, now)!!.map { it.sql },
        )
    }

    @Test
    fun deletingThisOccurrenceIsAnExceptionAndAStamp() {
        val batch = writes.deleteWithScope(Scope.THIS, series, viewed, now)!!
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO event_exception (eventId, date) VALUES (?, ?)",
                "UPDATE event SET updatedAt = ? WHERE id = ?",
            ),
            batch.map { it.sql },
        )
        assertEquals("2026-09-16", text(batch[0].args[1]))
    }

    @Test
    fun deletingThisAndFollowingTruncatesToTheDayBefore() {
        val batch = writes.deleteWithScope(Scope.FOLLOWING, series, viewed, now)!!
        assertEquals(listOf("UPDATE event SET endMode = ?, untilDate = ?, endCount = NULL, updatedAt = ? WHERE id = ?"), batch.map { it.sql })
        assertEquals("2026-09-15", text(batch.single().args[1]))
    }

    @Test
    fun aSplitAtTheFirstOccurrenceCollapsesToTheWholeDelete() {
        val batch = writes.deleteWithScope(Scope.FOLLOWING, series, anchor, now)!!
        assertEquals(listOf("DELETE FROM event WHERE id = ?"), batch.map { it.sql })
    }

    @Test
    fun aDayThatMapsToNoOccurrenceIsNothingToDo() {
        val notAnOccurrence = anchor.plusDays(3)
        assertNull(writes.deleteWithScope(Scope.THIS, series, notAnOccurrence, now))
        assertNull(writes.deleteWithScope(Scope.FOLLOWING, series, notAnOccurrence, now))
        assertNull(writes.editWithScope(Scope.THIS, series, series, notAnOccurrence, "new", now))
        assertNull(EventWrites.editLandsUnder(Scope.THIS, series, series, notAnOccurrence, "new"))
    }

    // ── Edit ─────────────────────────────────────────────────────────────────

    @Test
    fun editingThisOccurrenceExceptionsTheSeriesAndWritesAOneOffOverride() {
        val edited = series.copy(title = "Standup (moved)", startDate = viewed.plusDays(1), endDate = viewed.plusDays(1))
        val batch = writes.editWithScope(Scope.THIS, series, edited, viewed, "new", now, notePuts())!!.statements
        // The original's exception + stamp are the LAST thing written — behind the new row, its
        // note and its children — so a failed batch ahead of them leaves the series as it was.
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO event_exception (eventId, date) VALUES (?, ?)",
                "UPDATE event SET updatedAt = ? WHERE id = ?",
            ),
            batch.takeLast(2).map { it.sql },
        )
        assertEquals("2026-09-16", text(batch[batch.size - 2].args[1]))
        assertEquals("e1", text(batch[batch.size - 2].args[0]))

        val insert = batch[0]
        val columns = EventSql.COLUMNS.split(", ")
        assertTrue(insert.sql.startsWith("INSERT OR IGNORE INTO event ("))
        assertEquals("new", text(insert.args[columns.indexOf("id")]))
        assertEquals("Standup (moved)", text(insert.args[columns.indexOf("title")]))
        assertEquals("2026-09-17", text(insert.args[columns.indexOf("startDate")]))
        assertEquals(Cell.Integer(0), insert.args[columns.indexOf("recurring")])
        assertEquals(Cell.Null, insert.args[columns.indexOf("freq")])
        assertEquals(Cell.Integer(now), insert.args[columns.indexOf("createdAt")])
        // The override carries the reminders and the note; it inherits no exception of its own.
        assertEquals(1, batch.count { it.sql.startsWith("INSERT OR IGNORE INTO event_reminder") })
        assertTrue(batch.none { it.sql.startsWith("INSERT OR IGNORE INTO event_exception") && text(it.args[0]) == "new" })
        assertEquals(2, batch.count { it.sql.contains("note_stroke") })
        assertEquals("the override's note is the store's additions", 1, batch.take(2).count { it.sql.contains("note_stroke") })
        assertEquals("new", EventWrites.editLandsUnder(Scope.THIS, series, edited, viewed, "new"))
    }

    @Test
    fun editingThisAndFollowingTruncatesAndStartsAFreshSeries() {
        val edited = series.copy(title = "Standup v2", startDate = viewed, endDate = viewed)
        val batch = writes.editWithScope(Scope.FOLLOWING, series, edited, viewed, "new", now, notePuts())!!.statements
        // The truncation of the original is the LAST statement — behind the whole successor.
        val truncate = batch.last()
        assertEquals("UPDATE event SET endMode = ?, untilDate = ?, endCount = NULL, updatedAt = ? WHERE id = ?", truncate.sql)
        assertEquals("2026-09-15", text(truncate.args[1]))
        assertEquals("e1", text(truncate.args[3]))

        val columns = EventSql.COLUMNS.split(", ")
        assertEquals("new", text(batch[0].args[columns.indexOf("id")]))
        assertEquals("2026-09-16", text(batch[0].args[columns.indexOf("startDate")]))
        assertEquals(Cell.Integer(1), batch[0].args[columns.indexOf("recurring")])
        // The exceptions at or after the split carry over: an occurrence removed with THIS stays removed
        // (the truncated part is the head, not the tail — the fresh series is the tail).
        assertEquals(listOf("new" to "2026-09-23"), successorExceptions(batch))
        assertEquals("new", EventWrites.editLandsUnder(Scope.FOLLOWING, series, edited, viewed, "new"))
    }

    private fun successorExceptions(batch: List<Statement>): List<Pair<String, String>> =
        batch.filter { it.sql.startsWith("INSERT OR IGNORE INTO event_exception") }.map { text(it.args[0]) to text(it.args[1]) }

    @Test
    fun aFollowingEditDropsTheExceptionsBeforeTheSplit() {
        // Sep 9 was removed from the head, Sep 23 from the tail: only the tail's comes along.
        val twice = series.copy(exceptions = setOf(LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 23)))
        val edited = twice.copy(title = "Standup v2", startDate = viewed, endDate = viewed)
        val batch = writes.editWithScope(Scope.FOLLOWING, twice, edited, viewed, "new", now)!!.statements
        assertEquals(listOf("new" to "2026-09-23"), successorExceptions(batch))
    }

    @Test
    fun aReanchoredFollowingEditStillCarriesTheLaterExceptions() {
        // Moving the tail a day later: the carried date no longer lands on an occurrence, so it is
        // harmless — but it is written, so a rule change back would not resurrect the removal.
        val edited = series.copy(startDate = viewed.plusDays(1), endDate = viewed.plusDays(1))
        val batch = writes.editWithScope(Scope.FOLLOWING, series, edited, viewed, "new", now)!!.statements
        assertEquals(listOf("new" to "2026-09-23"), successorExceptions(batch))
    }

    /** Every 7 days from Sep 2, ten times: Sep 2, 9, 16, 23, 30, Oct 7, 14, 21, 28, Nov 4. */
    private val counted = series.copy(
        recurrence = RecurrenceRule(Freq.DAILY, interval = 7, endMode = EndMode.COUNT, endCount = 10),
        exceptions = emptySet(),
    )

    private fun successorRule(batch: List<Statement>): Triple<String, Cell, Cell> {
        val columns = EventSql.COLUMNS.split(", ")
        val row = batch.first { it.sql.startsWith("INSERT OR IGNORE INTO event (") }
        return Triple(text(row.args[columns.indexOf("id")]), row.args[columns.indexOf("endMode")], row.args[columns.indexOf("endCount")])
    }

    @Test
    fun editingThisAndFollowingKeepsTheRemainingCount() {
        // Split at #5 (Sep 30): the head keeps 4 (UNTIL Sep 29), the successor gets the other 6 — 4 + 6 = 10.
        val split = LocalDate.of(2026, 9, 30)
        val edited = counted.copy(title = "Standup v2", startDate = split, endDate = split)
        val batch = writes.editWithScope(Scope.FOLLOWING, counted, edited, split, "new", now)!!.statements
        assertEquals("2026-09-29", text(batch.last().args[1]))
        val (id, endMode, endCount) = successorRule(batch)
        assertEquals("new", id)
        assertEquals("COUNT", text(endMode))
        assertEquals(Cell.Integer(6), endCount)
    }

    @Test
    fun aMovedDateStillKeepsTheRemainingCount() {
        // Moving the remaining occurrences a day later changes the anchor, not the rule: still 6 left.
        val split = LocalDate.of(2026, 9, 30)
        val edited = counted.copy(startDate = split.plusDays(1), endDate = split.plusDays(1))
        val batch = writes.editWithScope(Scope.FOLLOWING, counted, edited, split, "new", now)!!.statements
        assertEquals(Cell.Integer(6), successorRule(batch).third)
    }

    @Test
    fun aChangedRuleKeepsTheCountTheUserTyped() {
        val split = LocalDate.of(2026, 9, 30)
        val retyped = counted.copy(
            startDate = split, endDate = split,
            recurrence = RecurrenceRule(Freq.DAILY, interval = 14, endMode = EndMode.COUNT, endCount = 10),
        )
        val batch = writes.editWithScope(Scope.FOLLOWING, counted, retyped, split, "new", now)!!.statements
        assertEquals(Cell.Integer(10), successorRule(batch).third)
    }

    @Test
    fun countBeforeCountsTheStartsAheadOfTheSplitIncludingRemovedOnes() {
        val rule = counted.recurrence!!
        assertEquals(0, Recurrence.countBefore(rule, anchor, anchor))
        assertEquals(4, Recurrence.countBefore(rule, anchor, LocalDate.of(2026, 9, 30)))
        // A date past the last start: all ten are behind it.
        assertEquals(10, Recurrence.countBefore(rule, anchor, LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun aFollowingSplitAtTheFirstOccurrenceCollapsesToTheWholeSeries() {
        val edited = series.copy(title = "Renamed")
        val batch = writes.editWithScope(Scope.FOLLOWING, series, edited, anchor, "new", now)!!.statements
        assertTrue(batch.none { it.sql.startsWith("UPDATE event SET endMode") })
        val columns = EventSql.COLUMNS.split(", ")
        assertEquals("e1", text(batch[0].args[columns.indexOf("id")]))
        assertEquals("e1", EventWrites.editLandsUnder(Scope.FOLLOWING, series, edited, anchor, "new"))
    }

    @Test
    fun editingAllKeepsTheAnchorWhenTheDatesComeBackAsThePrefill() {
        // The editor pre-fills from the TAPPED occurrence; saving that back must not re-anchor.
        val prefilled = series.copy(title = "Renamed", startDate = viewed, endDate = viewed)
        val batch = writes.editWithScope(Scope.ALL, series, prefilled, viewed, "new", now)!!.statements
        val columns = EventSql.COLUMNS.split(", ")
        assertEquals("e1", text(batch[0].args[columns.indexOf("id")]))
        assertEquals("2026-09-02", text(batch[0].args[columns.indexOf("startDate")]))
        // The exceptions carry forward — an occurrence already removed stays removed.
        val exceptions = batch.filter { it.sql.startsWith("INSERT OR IGNORE INTO event_exception") }
        assertEquals(listOf("2026-09-23"), exceptions.map { text(it.args[1]) })
        assertEquals("e1", EventWrites.editLandsUnder(Scope.ALL, series, prefilled, viewed, "new"))
    }

    @Test
    fun aDeliberatelyChangedDateReAnchorsTheSeries() {
        val moved = series.copy(startDate = viewed.plusDays(1), endDate = viewed.plusDays(1))
        val batch = writes.editWithScope(Scope.ALL, series, moved, viewed, "new", now)!!.statements
        val columns = EventSql.COLUMNS.split(", ")
        assertEquals("2026-09-17", text(batch[0].args[columns.indexOf("startDate")]))
    }

    @Test
    fun aNewEventIsAlwaysAPlainSeriesSave() {
        val fresh = testEvent(id = "fresh", title = "New thing")
        val batch = writes.editWithScope(Scope.THIS, null, fresh, viewed, "new", now)!!.statements
        assertEquals(writes.save(fresh, now).statements.map { it.sql }, batch.map { it.sql })
        assertEquals("fresh", EventWrites.editLandsUnder(Scope.THIS, null, fresh, viewed, "new"))
    }

    @Test
    fun editingANonRecurringEventIsAPlainSave() {
        val one = testEvent(id = "one", title = "Dentist")
        val batch = writes.editWithScope(Scope.THIS, one, one.copy(title = "Dentist 2"), viewed, "new", now)!!.statements
        assertEquals("one", text(batch[0].args[EventSql.COLUMNS.split(", ").indexOf("id")]))
        assertEquals("one", EventWrites.editLandsUnder(Scope.THIS, one, one.copy(title = "Dentist 2"), viewed, "new"))
    }
}
