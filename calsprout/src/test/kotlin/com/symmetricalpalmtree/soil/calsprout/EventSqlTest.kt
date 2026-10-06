package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Every events statement, pinned: the schema step's shape, exact text, exact arguments, and the
 *  rules the text has to carry (`event` is never REPLACE'd; no read carries an `IN (…)` list;
 *  every read of the event table names its calendar). */
class EventSqlTest {

    private val cal = "default"
    private val sep1 = LocalDate.of(2026, 9, 1)
    private val sep2 = LocalDate.of(2026, 9, 2)

    private val series = testEvent(
        id = "e1",
        type = EventType.MEETING,
        title = "Standup",
        start = sep1,
        end = sep2,
        allDay = false,
        startMinute = 540,
        endMinute = 570,
        recurrence = RecurrenceRule(
            freq = Freq.WEEKLY,
            interval = 2,
            weekdays = setOf(1, 3),
            monthlyMode = MonthlyMode.ORDINAL_WEEKDAY,
            endMode = EndMode.COUNT,
            endCount = 5,
        ),
        noteText = "hi",
        noteWidth = 800f,
        noteHeight = 600f,
        createdAt = 5L,
    )

    private val writes: List<Statement> get() = listOf(
        EventSql.insertEvent(cal, series, 99L),
        EventSql.updateEvent(series, 99L),
        EventSql.touchEvent("e1", 99L),
        EventSql.truncateEvent("e1", sep1, 99L),
        EventSql.deleteEvent("e1"),
        EventSql.clearWeekdays("e1"),
        EventSql.insertWeekday("e1", 3),
        EventSql.clearExceptions("e1"),
        EventSql.insertException("e1", sep1),
        EventSql.clearReminders("e1"),
        EventSql.insertReminder("e1", 2, ReminderUnit.WEEKS),
    )

    private val reads: List<Statement> get() = listOf(
        EventSql.selectEvent("e1"),
        EventSql.selectOneOffsOverlapping(cal, sep1, sep2),
        EventSql.selectOneOffsStartingIn(cal, sep1, sep2),
        EventSql.selectRecurring(cal),
        EventSql.selectWeekdays("e1"),
        EventSql.selectExceptions("e1"),
        EventSql.selectReminders("e1"),
        EventSql.selectRecurringWeekdays(cal),
        EventSql.selectRecurringExceptions(cal),
        EventSql.selectRecurringReminders(cal),
        EventSql.selectRemindersOverlapping(cal, sep1, sep2),
        EventSql.selectRemindersStartingIn(cal, sep1, sep2),
    )

    // ── The schema step ──────────────────────────────────────────────────────

    @Test
    fun theEventsStepsShape() {
        val step = CalendarSchema.SCHEMA.steps[1]
        assertEquals(CalendarSchema.EVENTS_STEP, step)
        assertEquals(8, step.size)
        assertEquals(5, step.count { it.trimStart().startsWith("CREATE TABLE") })
        assertEquals(4, step.count { it.contains("REFERENCES event(id) ON DELETE CASCADE") })
        assertTrue(step[0].contains("calendarId TEXT NOT NULL REFERENCES calendar(id) ON DELETE CASCADE"))
        assertEquals("""CREATE INDEX note_stroke_event_order ON note_stroke(eventId, "order")""", step.last())
        // Soil applies the declaration, so its own DDL checker is the assertion that matters.
        for (ddl in step) SeamSql.checkDdl(ddl)
        // And the landed first step is untouched.
        assertEquals(CalendarSchema.PAGES_STEP, CalendarSchema.SCHEMA.steps[0])
    }

    // ── Every statement through the seam's gate ──────────────────────────────

    @Test
    fun everyStatementPassesTheSeamGate() {
        for (s in writes) { SeamSql.checkExec(s.sql); assertEquals(s.sql, SeamSql.bindCount(s.sql), s.args.size) }
        for (s in reads) { SeamSql.checkQuery(s.sql); assertEquals(s.sql, SeamSql.bindCount(s.sql), s.args.size) }
    }

    @Test
    fun theEventRowIsNeverReplaced() {
        for (s in writes) assertFalse(s.sql, s.sql.contains("REPLACE"))
    }

    @Test
    fun noReadCarriesAnInList() {
        for (s in reads) assertFalse(s.sql, s.sql.contains(" IN ("))
    }

    @Test
    fun everyReadOfASetNamesItsCalendar() {
        // The by-id reads name a row; every read of a set is for one calendar, bound first.
        for (s in reads.drop(1).filter { it.sql.contains(" FROM event ") || it.sql.contains("JOIN event") }) {
            assertTrue(s.sql, s.sql.contains("calendarId = ?"))
            assertEquals(s.sql, Cell.Text(cal), s.args[0])
        }
    }

    // ── The upsert ───────────────────────────────────────────────────────────

    @Test
    fun theColumnListIsOnePlace() {
        assertEquals(
            "id, calendarId, type, title, startDate, endDate, allDay, startMinute, endMinute, recurring, freq, " +
                "interval, monthlyMode, endMode, untilDate, endCount, noteText, noteWidth, noteHeight, createdAt, updatedAt",
            EventSql.COLUMNS,
        )
        assertEquals(21, EventSql.COLUMNS.split(", ").size)
    }

    @Test
    fun insertEvent() {
        val s = EventSql.insertEvent(cal, series, 99L)
        assertEquals(
            "INSERT OR IGNORE INTO event (id, calendarId, type, title, startDate, endDate, allDay, startMinute, endMinute, " +
                "recurring, freq, interval, monthlyMode, endMode, untilDate, endCount, noteText, noteWidth, noteHeight, " +
                "createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            s.sql,
        )
        assertEquals(
            listOf<Cell>(
                Cell.Text("e1"), Cell.Text("default"), Cell.Text("MEETING"), Cell.Text("Standup"), Cell.Text("2026-09-01"), Cell.Text("2026-09-02"),
                Cell.Integer(0), Cell.Integer(540), Cell.Integer(570), Cell.Integer(1), Cell.Text("WEEKLY"),
                Cell.Integer(2), Cell.Text("ORDINAL_WEEKDAY"), Cell.Text("COUNT"), Cell.Null, Cell.Integer(5),
                Cell.Text("hi"), Cell.Real(800.0), Cell.Real(600.0), Cell.Integer(5), Cell.Integer(99),
            ),
            s.args,
        )
    }

    @Test
    fun updateEventWritesEveryColumnButIdCalendarAndCreatedAt() {
        val s = EventSql.updateEvent(series, 99L)
        assertEquals(
            "UPDATE event SET type = ?, title = ?, startDate = ?, endDate = ?, allDay = ?, startMinute = ?, " +
                "endMinute = ?, recurring = ?, freq = ?, interval = ?, monthlyMode = ?, endMode = ?, untilDate = ?, " +
                "endCount = ?, noteText = ?, noteWidth = ?, noteHeight = ?, updatedAt = ? WHERE id = ?",
            s.sql,
        )
        assertEquals(19, s.args.size)
        assertEquals(EventSql.insertEvent(cal, series, 99L).args.drop(2).dropLast(2), s.args.dropLast(2))
        assertEquals(listOf<Cell>(Cell.Integer(99), Cell.Text("e1")), s.args.takeLast(2))
    }

    @Test
    fun aOneOffCarriesTheNotNullDefaults() {
        val args = EventSql.insertEvent(cal, testEvent(id = "one"), 99L).args
        val columns = EventSql.COLUMNS.split(", ")
        assertEquals(Cell.Integer(0), args[columns.indexOf("recurring")])
        assertEquals(Cell.Null, args[columns.indexOf("freq")])
        assertEquals(Cell.Integer(1), args[columns.indexOf("interval")])
        assertEquals(Cell.Text("DAY_OF_MONTH"), args[columns.indexOf("monthlyMode")])
        assertEquals(Cell.Text("NEVER"), args[columns.indexOf("endMode")])
        assertEquals(Cell.Null, args[columns.indexOf("untilDate")])
        assertEquals(Cell.Null, args[columns.indexOf("endCount")])
        assertEquals(Cell.Integer(1), args[columns.indexOf("allDay")])
    }

    @Test
    fun theTwoStamps() {
        val touch = EventSql.touchEvent("e1", 99L)
        assertEquals("UPDATE event SET updatedAt = ? WHERE id = ?", touch.sql)
        assertEquals(listOf<Cell>(Cell.Integer(99), Cell.Text("e1")), touch.args)

        val truncate = EventSql.truncateEvent("e1", sep1, 99L)
        assertEquals("UPDATE event SET endMode = ?, untilDate = ?, endCount = NULL, updatedAt = ? WHERE id = ?", truncate.sql)
        assertEquals(
            listOf<Cell>(Cell.Text("UNTIL"), Cell.Text("2026-09-01"), Cell.Integer(99), Cell.Text("e1")),
            truncate.args,
        )
    }

    @Test
    fun theDelete() {
        val s = EventSql.deleteEvent("e1")
        assertEquals("DELETE FROM event WHERE id = ?", s.sql)
        assertEquals(listOf<Cell>(Cell.Text("e1")), s.args)
    }

    // ── The child sets ───────────────────────────────────────────────────────

    @Test
    fun childSetsAreClearedThenInsertedOrIgnored() {
        assertEquals("DELETE FROM event_weekday WHERE eventId = ?", EventSql.clearWeekdays("e1").sql)
        val weekday = EventSql.insertWeekday("e1", 3)
        assertEquals("INSERT OR IGNORE INTO event_weekday (eventId, weekday) VALUES (?, ?)", weekday.sql)
        assertEquals(listOf<Cell>(Cell.Text("e1"), Cell.Integer(3)), weekday.args)

        assertEquals("DELETE FROM event_exception WHERE eventId = ?", EventSql.clearExceptions("e1").sql)
        val exception = EventSql.insertException("e1", sep1)
        assertEquals("INSERT OR IGNORE INTO event_exception (eventId, date) VALUES (?, ?)", exception.sql)
        assertEquals(listOf<Cell>(Cell.Text("e1"), Cell.Text("2026-09-01")), exception.args)

        assertEquals("DELETE FROM event_reminder WHERE eventId = ?", EventSql.clearReminders("e1").sql)
        val reminder = EventSql.insertReminder("e1", 2, ReminderUnit.WEEKS)
        assertEquals("INSERT OR IGNORE INTO event_reminder (eventId, amount, unit) VALUES (?, ?, ?)", reminder.sql)
        assertEquals(listOf<Cell>(Cell.Text("e1"), Cell.Integer(2), Cell.Text("WEEKS")), reminder.args)
    }

    // ── The reads ────────────────────────────────────────────────────────────

    @Test
    fun theEventReads() {
        assertEquals("SELECT ${EventSql.COLUMNS} FROM event WHERE id = ?", EventSql.selectEvent("e1").sql)

        val overlapping = EventSql.selectOneOffsOverlapping(cal, sep1, sep2)
        assertEquals(
            "SELECT ${EventSql.COLUMNS} FROM event WHERE calendarId = ? AND recurring = 0 AND startDate <= ? AND endDate >= ?",
            overlapping.sql,
        )
        // Bound the other way round: `startDate <= to AND endDate >= from` is what "overlaps" means.
        assertEquals(listOf<Cell>(Cell.Text("default"), Cell.Text("2026-09-02"), Cell.Text("2026-09-01")), overlapping.args)

        val starting = EventSql.selectOneOffsStartingIn(cal, sep1, sep2)
        assertEquals(
            "SELECT ${EventSql.COLUMNS} FROM event WHERE calendarId = ? AND recurring = 0 AND startDate > ? AND startDate <= ?",
            starting.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text("default"), Cell.Text("2026-09-01"), Cell.Text("2026-09-02")), starting.args)

        assertEquals("SELECT ${EventSql.COLUMNS} FROM event WHERE calendarId = ? AND recurring = 1", EventSql.selectRecurring(cal).sql)
        assertEquals(listOf<Cell>(Cell.Text("default")), EventSql.selectRecurring(cal).args)
    }

    @Test
    fun oneEventsChildReads() {
        assertEquals("SELECT weekday FROM event_weekday WHERE eventId = ?", EventSql.selectWeekdays("e1").sql)
        assertEquals("SELECT date FROM event_exception WHERE eventId = ?", EventSql.selectExceptions("e1").sql)
        assertEquals("SELECT amount, unit FROM event_reminder WHERE eventId = ?", EventSql.selectReminders("e1").sql)
        assertEquals(listOf<Cell>(Cell.Text("e1")), EventSql.selectWeekdays("e1").args)
    }

    @Test
    fun aWholeSetsChildReadsAreOneJoinEach_everyRowNamingItsParent() {
        assertEquals(
            "SELECT w.eventId AS eventId, w.weekday AS weekday FROM event_weekday w JOIN event e ON e.id = w.eventId WHERE e.calendarId = ? AND e.recurring = 1",
            EventSql.selectRecurringWeekdays(cal).sql,
        )
        assertEquals(
            "SELECT x.eventId AS eventId, x.date AS date FROM event_exception x JOIN event e ON e.id = x.eventId WHERE e.calendarId = ? AND e.recurring = 1",
            EventSql.selectRecurringExceptions(cal).sql,
        )
        assertEquals(
            "SELECT r.eventId AS eventId, r.amount AS amount, r.unit AS unit FROM event_reminder r JOIN event e ON e.id = r.eventId WHERE e.calendarId = ? AND e.recurring = 1",
            EventSql.selectRecurringReminders(cal).sql,
        )
    }

    @Test
    fun aOneOffSetsRemindersCarryTheParentReadsOwnPredicate() {
        val overlapping = EventSql.selectRemindersOverlapping(cal, sep1, sep2)
        assertEquals(
            "SELECT r.eventId AS eventId, r.amount AS amount, r.unit AS unit FROM event_reminder r " +
                "JOIN event e ON e.id = r.eventId WHERE e.calendarId = ? AND e.recurring = 0 AND e.startDate <= ? AND e.endDate >= ?",
            overlapping.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text("default"), Cell.Text("2026-09-02"), Cell.Text("2026-09-01")), overlapping.args)

        val starting = EventSql.selectRemindersStartingIn(cal, sep1, sep2)
        assertEquals(
            "SELECT r.eventId AS eventId, r.amount AS amount, r.unit AS unit FROM event_reminder r " +
                "JOIN event e ON e.id = r.eventId WHERE e.calendarId = ? AND e.recurring = 0 AND e.startDate > ? AND e.startDate <= ?",
            starting.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text("default"), Cell.Text("2026-09-01"), Cell.Text("2026-09-02")), starting.args)
    }
}
