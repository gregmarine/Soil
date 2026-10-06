package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import com.symmetricalpalmtree.soil.paper.store.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [EventStore] over the applying fake: what it reads, what it writes, and that a write is one transaction. */
class EventStoreTest {

    private val sep1 = LocalDate.of(2026, 9, 1)

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK,
        width = 3f,
    )

    private fun store(fake: FakeEventStore, now: Long = 500L, calendarId: String = CalendarSchema.DEFAULT_CALENDAR) =
        EventStore(fake, calendarId, clock = { now })

    private fun text(cell: Cell) = (cell as Cell.Text).value

    // ── Reading ──────────────────────────────────────────────────────────────

    @Test
    fun aRangeIsSixQueries_andExpandsTheRecurringSetInKotlin() {
        val fake = FakeEventStore()
        fake.seed(testEvent(id = "one", title = "Dentist", start = sep1, end = sep1.plusDays(2)))
        fake.seed(
            testEvent(
                id = "rec",
                title = "Standup",
                start = sep1,
                recurrence = RecurrenceRule(Freq.DAILY, interval = 3),
                exceptions = setOf(sep1.plusDays(3)),
            ),
        )
        fake.calls.clear()

        val byDay = store(fake).eventsInRange(sep1, sep1.plusDays(6))
        assertEquals(
            listOf(
                "query(oneOffsOverlapping)", "query(remindersOverlapping)", "query(recurring)",
                "query(recurringWeekdays)", "query(recurringExceptions)", "query(recurringReminders)",
            ),
            fake.calls,
        )
        assertEquals(
            mapOf(
                sep1 to listOf("Dentist", "Standup"),
                sep1.plusDays(1) to listOf("Dentist"),
                sep1.plusDays(2) to listOf("Dentist"),
                sep1.plusDays(6) to listOf("Standup"),
            ),
            byDay.mapValues { (_, list) -> list.map { it.title } },
        )
        // Ascending, and a day with nothing on it is absent rather than empty.
        assertEquals(listOf(sep1, sep1.plusDays(1), sep1.plusDays(2), sep1.plusDays(6)), byDay.keys.toList())
    }

    @Test
    fun aSavedEventReadsBackOnItsDays_withItsChildren() {
        val fake = FakeEventStore()
        val e = testEvent(
            id = "e1",
            title = "Standup",
            start = LocalDate.of(2026, 9, 2),
            allDay = false,
            startMinute = 540,
            recurrence = RecurrenceRule(Freq.WEEKLY, weekdays = setOf(1, 3)),
            reminders = listOf(Reminder(1, ReminderUnit.DAYS)),
        )
        store(fake).save(e, isNew = true)
        // One transaction: the whole write is one exec.
        assertEquals(1, fake.execs.size)

        val onWed = store(fake).eventsOn(LocalDate.of(2026, 9, 9))
        assertEquals(listOf("Standup"), onWed.map { it.title })
        assertEquals(setOf(1, 3), onWed.single().recurrence!!.weekdays)
        assertEquals(listOf(Reminder(1, ReminderUnit.DAYS)), onWed.single().reminders)
        assertTrue(store(fake).eventsOn(LocalDate.of(2026, 9, 10)).isEmpty())   // a Thursday
    }

    @Test
    fun anotherCalendarsEventsAreNotThisOnes() {
        val fake = FakeEventStore()
        fake.seed(testEvent(id = "mine", title = "Dentist", start = sep1))
        fake.seed(testEvent(id = "work", title = "Standup", start = sep1), calendarId = "work")
        assertEquals(listOf("Dentist"), store(fake).eventsOn(sep1).map { it.title })
        assertEquals(listOf("Standup"), store(fake, calendarId = "work").eventsOn(sep1).map { it.title })
        // And a save names the calendar it was made for.
        store(fake, calendarId = "work").save(testEvent(id = "w2", title = "Review", start = sep1), isNew = true)
        assertEquals("work", text(fake.events["w2"]!![FakeEventStore.EVENT_COLUMNS.indexOf("calendarId")]))
    }

    @Test
    fun theDayOrderIsAllDayFirstThenByStartMinute() {
        val fake = FakeEventStore()
        fake.seed(testEvent(id = "b", title = "Nine", start = sep1, allDay = false, startMinute = 540))
        fake.seed(testEvent(id = "c", title = "Eight", start = sep1, allDay = false, startMinute = 480))
        fake.seed(testEvent(id = "a", title = "Holiday", start = sep1, allDay = true))
        assertEquals(listOf("Holiday", "Eight", "Nine"), store(fake).eventsOn(sep1).map { it.title })
    }

    @Test
    fun marksAreTheDayListNarrowedToWhatTheGridDraws() {
        val fake = FakeEventStore()
        fake.seed(testEvent(id = "a", type = EventType.BIRTHDAY, title = "Ann", start = sep1, allDay = true))
        fake.seed(
            testEvent(
                id = "b", type = EventType.MEETING, title = "Standup",
                start = sep1, allDay = false, startMinute = 540,
            ),
        )
        fake.calls.clear()

        val marks = store(fake).marksFor(sep1, sep1.plusDays(2))
        // The same six queries a day list costs — nothing extra for the grid.
        assertEquals(
            listOf(
                "query(oneOffsOverlapping)", "query(remindersOverlapping)", "query(recurring)",
                "query(recurringWeekdays)", "query(recurringExceptions)", "query(recurringReminders)",
            ),
            fake.calls,
        )
        // One day, in DAY order: the all-day birthday, then the 9:00 meeting.
        assertEquals(listOf(sep1), marks.keys.toList())
        assertEquals(
            listOf(
                DayMark("Ann", allDay = true, startMinute = null, glyph = Glyph.CAKE),
                DayMark("Standup", allDay = false, startMinute = 540, glyph = Glyph.PEOPLE),
            ),
            marks.getValue(sep1),
        )
    }

    @Test
    fun marksOfAnEmptyRangeAreEmpty() {
        val fake = FakeEventStore()
        fake.seed(testEvent(id = "a", title = "Ann", start = sep1))
        assertTrue(store(fake).marksFor(sep1.plusDays(1), sep1.plusDays(5)).isEmpty())
    }

    @Test
    fun upcomingIsItsOwnSixQueries() {
        val fake = FakeEventStore()
        fake.seed(testEvent(id = "soon", title = "Trip", start = sep1.plusDays(3), reminders = listOf(Reminder(1, ReminderUnit.WEEKS))))
        fake.seed(testEvent(id = "quiet", title = "Nothing", start = sep1.plusDays(3)))
        fake.calls.clear()

        val out = store(fake).upcomingOn(sep1)
        assertEquals(
            listOf(
                "query(oneOffsStartingIn)", "query(remindersStartingIn)", "query(recurring)",
                "query(recurringWeekdays)", "query(recurringExceptions)", "query(recurringReminders)",
            ),
            fake.calls,
        )
        assertEquals(listOf("Trip"), out.map { it.event.title })
        assertEquals(3, out.single().daysUntil)
    }

    /** The events screen wants both answers, and the recurring set — four of the six queries each
     *  read costs — is read and decoded ONCE for the pair. */
    @Test
    fun dayAndUpcomingReadTheRecurringSetOnce() {
        val fake = FakeEventStore()
        fake.seed(testEvent(id = "today", title = "Dentist", start = sep1))
        fake.seed(testEvent(id = "soon", title = "Trip", start = sep1.plusDays(3), reminders = listOf(Reminder(1, ReminderUnit.WEEKS))))
        fake.seed(
            testEvent(
                id = "weekly", title = "Standup", start = sep1,
                recurrence = RecurrenceRule(Freq.WEEKLY, weekdays = setOf(sep1.dayOfWeek.value % 7)),
            )
        )
        fake.calls.clear()

        val out = store(fake).dayAndUpcoming(sep1)

        assertEquals(
            listOf(
                "query(oneOffsOverlapping)", "query(remindersOverlapping)",
                "query(oneOffsStartingIn)", "query(remindersStartingIn)",
                "query(recurring)", "query(recurringWeekdays)",
                "query(recurringExceptions)", "query(recurringReminders)",
            ),
            fake.calls,
        )
        assertEquals(store(fake).eventsOn(sep1).map { it.title }, out.today.map { it.title })
        assertEquals(listOf("Dentist", "Standup"), out.today.map { it.title })
        assertEquals(listOf("Trip"), out.upcoming.map { it.event.title })
    }

    @Test
    fun getReadsTheRowAndItsThreeChildSets() {
        val fake = FakeEventStore()
        val e = testEvent(
            id = "e1",
            title = "Standup",
            start = sep1,
            recurrence = RecurrenceRule(Freq.WEEKLY, weekdays = setOf(3)),
            exceptions = setOf(sep1.plusDays(7)),
            reminders = listOf(Reminder(2, ReminderUnit.DAYS)),
        )
        fake.seed(e)
        fake.calls.clear()

        assertEquals(e, store(fake).get("e1"))
        assertEquals(listOf("query(event)", "query(weekdays)", "query(exceptions)", "query(reminders)"), fake.calls)
        assertNull(store(fake).get("nobody"))
    }

    @Test
    fun theNoteIsOneRead() {
        val fake = FakeEventStore()
        fake.seed(testEvent(id = "e1"))
        fake.seedNote("e1", 0L, stroke("s1"))
        fake.seedNote("e1", 4L, stroke("s2", 9))
        fake.calls.clear()

        val ink = store(fake).readNote("e1")
        assertEquals(listOf(0L to "s1", 4L to "s2"), ink.map { it.first to it.second.id })
        assertEquals(listOf("query(noteStrokes)"), fake.calls)
        assertEquals(4L, store(fake).noteMaxOrder("e1"))
        assertEquals(-1L, store(fake).noteMaxOrder("empty"))
    }

    @Test
    fun aBadRowIsDroppedAndTheDayStillLists() {
        val fake = FakeEventStore()
        fake.seed(testEvent(id = "good", title = "Dentist", start = sep1))
        fake.seed(testEvent(id = "bad", title = "Broken", start = sep1))
        // A type this build does not know: the row is dropped, not relabelled.
        fake.events["bad"]!![FakeEventStore.EVENT_COLUMNS.indexOf("type")] = Cell.Text("HOLIDAY")

        assertEquals(listOf("Dentist"), store(fake).eventsOn(sep1).map { it.title })
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    @Test
    fun deleteAnswersFalseWhenThereIsNothingToDo() {
        val fake = FakeEventStore()
        val series = testEvent(id = "e1", start = sep1, recurrence = RecurrenceRule(Freq.DAILY, interval = 7))
        fake.seed(series)
        assertFalse(store(fake).delete(Scope.THIS, series, sep1.plusDays(3)))
        assertTrue(fake.execs.isEmpty())

        assertTrue(store(fake).delete(Scope.ALL, series, sep1))
        assertTrue(fake.events.isEmpty())
    }

    @Test
    fun deletingAnEventTakesItsChildrenAndItsNote() {
        val fake = FakeEventStore()
        val e = testEvent(id = "e1", start = sep1, reminders = listOf(Reminder(1, ReminderUnit.DAYS)))
        fake.seed(e)
        fake.seedNote("e1", 0L, stroke("s1"))
        assertTrue(store(fake).delete(Scope.ALL, e, sep1))
        assertTrue(fake.events.isEmpty())
        assertTrue(fake.reminders.isEmpty())
        assertTrue(fake.noteStrokes.isEmpty())
    }

    @Test
    fun editAnswersTheIdTheEditedFieldsLandedUnder() {
        val fake = FakeEventStore()
        val series = testEvent(id = "e1", title = "Standup", start = sep1, recurrence = RecurrenceRule(Freq.DAILY, interval = 7))
        fake.seed(series)
        val edited = series.copy(title = "Standup (moved)")

        val landed = store(fake).edit(Scope.THIS, series, edited, sep1.plusDays(7))!!
        assertFalse("an override lands under a fresh id", landed == "e1")
        assertEquals("Standup (moved)", fake.events[landed]!![FakeEventStore.EVENT_COLUMNS.indexOf("title")].let(::text))
        assertEquals(listOf("2026-09-08"), fake.exceptions["e1"]!!.toList())
        // The override and the original's exception are one transaction.
        assertEquals(1, fake.execs.size)

        assertEquals("e1", store(fake).edit(Scope.ALL, series, edited, sep1.plusDays(7)))
        assertNull("no occurrence covers that day", store(fake).edit(Scope.THIS, series, edited, sep1.plusDays(3)))
    }

    // ── The note's id, which only the store knows ────────────────────────────

    /** A recurring original the screens open an occurrence of; every note test below edits it. */
    private fun series() = testEvent(id = "e1", title = "Standup", start = sep1, recurrence = RecurrenceRule(Freq.DAILY, interval = 7))

    /** A note that records which id it was asked for and writes one stroke under it. */
    private fun noteAsking(asked: MutableList<String>, strokeId: String = "s1"): (String) -> NoteWrite = { id ->
        asked += id
        NoteWrite.inPlace(listOf(NoteSql.putStroke(id, 0L, stroke(strokeId))), listOf(strokeId))
    }

    @Test
    fun anOverrideAsksForTheNoteUnderTheNewId_andParentsItThere() {
        val fake = FakeEventStore()
        fake.seed(series())
        val asked = ArrayList<String>()

        val landed = store(fake).edit(
            Scope.THIS, series(), series().copy(title = "Moved"), sep1.plusDays(7),
            newId = "new1", note = noteAsking(asked),
        )
        // The caller's own id is the one used — it is minted on Main so both answers can be built
        // there, and the store must not quietly substitute one of its own.
        assertEquals("new1", landed)
        assertEquals(listOf("new1"), asked)
        assertEquals("new1", fake.noteStrokes["s1"]!!.eventId)
    }

    @Test
    fun anInPlaceEditAsksForTheNoteUnderTheOriginalsId() {
        val fake = FakeEventStore()
        fake.seed(series())
        val asked = ArrayList<String>()
        assertEquals("e1", store(fake).edit(Scope.ALL, series(), series().copy(title = "Moved"), sep1.plusDays(7), "new1", noteAsking(asked)))
        assertEquals(listOf("e1"), asked)
        assertEquals("e1", fake.noteStrokes["s1"]!!.eventId)

        // A one-off original is the same road: editWithScope routes it to the in-place editSeries.
        val oneOff = FakeEventStore()
        oneOff.seed(testEvent(id = "solo", title = "Dentist", start = sep1))
        val alone = ArrayList<String>()
        assertEquals(
            "solo",
            store(oneOff).edit(Scope.THIS, testEvent(id = "solo", title = "Dentist", start = sep1), testEvent(id = "solo", title = "Moved", start = sep1), sep1, "new1", noteAsking(alone, "s2")),
        )
        assertEquals(listOf("solo"), alone)
    }

    @Test
    fun aWriteThatFailsLandsNothing() {
        val fake = FakeEventStore()
        fake.seed(series())
        val before = fake.events["e1"]!!.toList()
        fake.failExecAt = 0

        var n = 0
        val note: (String) -> NoteWrite = { id ->
            NoteWrite.copy(listOf(0L to stroke("a"), 1L to stroke("b", 1), 2L to stroke("c", 2)), id) { "m-${n++}" }
        }
        val thrown = runCatching {
            store(fake).edit(Scope.THIS, series(), series().copy(title = "Moved"), sep1.plusDays(7), "new1", note)
        }.exceptionOrNull()
        assertTrue("was $thrown", thrown is StoreUnavailable)

        // The failed exec was one transaction: nothing of it is in the tables.
        assertNull("no exception was written on the series", fake.exceptions["e1"])
        assertEquals("the series row is byte-identical", before, fake.events["e1"]!!.toList())
        assertFalse(fake.events.containsKey("new1"))
        assertTrue(fake.noteStrokes.isEmpty())
    }

    @Test
    fun saveRefusesAProblem_andThatIsNotAStoreFailure() {
        val fake = FakeEventStore()
        val blank = testEvent(id = "e1", title = "   ")
        val thrown = runCatching { store(fake).save(blank, isNew = true) }.exceptionOrNull()
        assertTrue("was $thrown", thrown is IllegalArgumentException)
        assertTrue("nothing was sent", fake.execs.isEmpty())

        val backwards = testEvent(
            id = "e2",
            title = "Standup",
            start = sep1,
            recurrence = RecurrenceRule(Freq.DAILY, endMode = EndMode.UNTIL, untilDate = sep1.minusDays(1)),
        )
        assertTrue(runCatching { store(fake).edit(Scope.ALL, null, backwards, sep1) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun theCapsAreAppliedOnTheWayIn() {
        val fake = FakeEventStore()
        store(fake).save(testEvent(id = "e1", title = "  Two\nWords  ", end = sep1.minusDays(4)), isNew = true)
        val stored = store(fake).get("e1")!!
        assertEquals("TwoWords", stored.title)
        assertEquals(stored.startDate, stored.endDate)
    }

    @Test
    fun everyStoreFailureReadsAsUnavailable() {
        for (failure in listOf(SecurityException("revoked"), IllegalStateException("gone"), RuntimeException("binder gone"))) {
            val fake = FakeEventStore()
            fake.failWith = { failure }
            val thrown = runCatching { store(fake).eventsOn(sep1) }.exceptionOrNull()
            assertTrue("was $thrown for $failure", thrown is StoreUnavailable)
            val onWrite = runCatching { store(fake).save(testEvent(title = "Dentist"), isNew = true) }.exceptionOrNull()
            assertTrue("was $onWrite for $failure", onWrite is StoreUnavailable)
        }
    }
}
