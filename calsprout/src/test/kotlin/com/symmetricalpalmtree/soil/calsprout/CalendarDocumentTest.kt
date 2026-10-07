package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import com.symmetricalpalmtree.soil.paper.store.Cell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The calendar's page over the statement-recording fake: **opening writes only the bookmark**,
 * the first stroke's batch is `period` + `page` + the stroke (+ `updatedAt`) in one exec, a page
 * that exists is never re-minted, an undo before the debounce mints nothing, and a replay on
 * another page navigates there first.
 */
class CalendarDocumentTest {

    private val surface = 1404f to 1872f
    private val month = CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0)
    private val nextMonth = CalendarTarget(CalendarTarget.KIND_MONTH, "2026-10-01", 0)

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK,
        width = 3f,
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value
    private fun doc(fake: FakeCalendarStore, marks: MarkSource = MarkSource { _, _ -> emptyMap() }) =
        CalendarDocument(CalendarStore(fake), marks) { surface }

    /** A [MarkSource] that counts what it was asked and answers a fixed picture — or throws. */
    private class FakeMarks(
        private val byDay: Map<LocalDate, List<DayMark>> = emptyMap(),
        private val fail: Boolean = false,
    ) : MarkSource {
        val ranges = ArrayList<Pair<LocalDate, LocalDate>>()
        val reads: Int get() = ranges.size

        override fun marksFor(from: LocalDate, to: LocalDate): Map<LocalDate, List<DayMark>> {
            ranges += from to to
            if (fail) throw StoreUnavailable(IllegalStateException("gone"))
            return byDay.filterKeys { !it.isBefore(from) && !it.isAfter(to) }
        }
    }

    private fun mark(title: String) = DayMark(title, allDay = true, startMinute = null, glyph = Glyph.CAKE)
    private fun nonState(fake: FakeCalendarStore) = fake.statements.filter { !it.sql.startsWith("INSERT OR REPLACE INTO state") }

    @Test
    fun showingAnEmptyMonthWritesOnlyTheBookmark() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        assertEquals(month, d.target)
        assertEquals(1404f, d.pageWidth, 0f)
        assertEquals(0, d.strokes.size)
        assertFalse(d.hasUnsavedChanges)
        assertEquals(listOf("query(page)", "query(period)", "exec(3)"), fake.calls)
        assertTrue(nonState(fake).isEmpty())
        // Browsing on: still only bookmarks.
        d.show(nextMonth)
        assertTrue(nonState(fake).isEmpty())
        assertEquals(2, fake.execs.size)
    }

    @Test
    fun theFirstStrokeMintsPeriodAndPageAheadOfItself_inOneBatch() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        fake.execs.clear()
        d.addStroke(stroke("a"))
        assertTrue(d.hasUnsavedChanges)
        d.flushUntilClean()
        val batch = fake.execs.single()
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO period (id, calendarId, kind, date) VALUES (?, ?, ?, ?)",
                "INSERT OR IGNORE INTO page (id, periodId, half, width, height, createdAt, updatedAt) VALUES (?, (SELECT id FROM period WHERE calendarId = ? AND kind = ? AND date = ?), ?, ?, ?, ?, ?)",
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "UPDATE page SET updatedAt = ? WHERE id = ?",
            ),
            batch.map { it.sql },
        )
        assertEquals(Cell.Text("default"), batch[0].args[1])
        assertEquals(Cell.Integer(0), batch[0].args[2])
        assertEquals(Cell.Text("2026-09-01"), batch[0].args[3])
        // The page takes the surface's size, and the stroke lands under the page id just minted.
        assertEquals(Cell.Real(1404.0), batch[1].args[5])
        assertEquals(Cell.Real(1872.0), batch[1].args[6])
        assertEquals(d.pageId, text(batch[1].args[0]))
        assertEquals(d.pageId, text(batch[2].args[1]))
        assertEquals(Cell.Integer(0), batch[2].args[2])
        assertFalse(d.hasUnsavedChanges)

        // The second stroke does not mint again.
        fake.execs.clear()
        d.addStroke(stroke("b"))
        d.flushUntilClean()
        assertEquals(
            listOf(
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "UPDATE page SET updatedAt = ? WHERE id = ?",
            ),
            fake.execs.single().map { it.sql },
        )
    }

    @Test
    fun anExistingPageIsNeverReMinted_andKeepsItsOwnSize() = runBlocking {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_MONTH, "2026-09-01")
        fake.page("pg", "per", 0, 800f, 1000f, listOf(3L to stroke("old")))
        val d = doc(fake)
        d.show(month)
        assertEquals("pg", d.pageId)
        assertEquals(800f, d.pageWidth, 0f)
        assertEquals(listOf("old"), d.strokes.map { it.id })
        fake.execs.clear()
        d.addStroke(stroke("a"))
        assertEquals(4L, d.orderOf("a"))
        d.flushUntilClean()
        assertEquals(
            listOf(
                "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "UPDATE page SET updatedAt = ? WHERE id = ?",
            ),
            fake.execs.single().map { it.sql },
        )
        assertEquals("pg", text(fake.execs.single()[0].args[1]))
    }

    @Test
    fun theOtherHalfOfADayJoinsTheExistingPeriod() = runBlocking {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_DAY, "2026-09-01")
        fake.page("am", "per", 0, 1404f, 1872f)
        val d = doc(fake)
        d.show(CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", 1))
        fake.execs.clear()
        d.addStroke(stroke("a"))
        d.flushUntilClean()
        val batch = fake.execs.single()
        // The period INSERT is still sent (OR IGNORE — it lands on the UNIQUE and does nothing); the
        // page resolves its periodId from (calendarId, kind, date) inside the statement.
        assertEquals("per", text(batch[0].args[0]))
        assertEquals(Cell.Integer(1), batch[1].args[4])
    }

    @Test
    fun aZeroSizePageLearnsTheSurfaceOnceAndOnlyOnce() = runBlocking {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_MONTH, "2026-09-01")
        fake.page("pg", "per", 0, 0f, 0f, listOf(0L to stroke("placed")))   // minted by a paste
        val d = doc(fake)
        d.show(month)
        assertEquals(1404f, d.pageWidth, 0f)
        assertTrue(d.hasUnsavedChanges)
        fake.execs.clear()
        d.flushUntilClean()
        assertEquals(listOf("UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?"), fake.sql())
        fake.execs.clear()
        d.flushUntilClean()
        assertTrue(fake.execs.isEmpty())
    }

    @Test
    fun aStrokeDrawnAndUndoneBeforeTheDebounceMintsNothing() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        fake.execs.clear()
        val s = stroke("a")
        d.addStroke(s)
        assertTrue(d.revert(InkAction.Drew(d.pageId, s)))
        // One DELETE for a row that never landed, no period, no page, no touch.
        assertEquals(listOf("DELETE FROM stroke WHERE id = ?"), fake.sql())
        // And the next real stroke still mints.
        fake.execs.clear()
        d.addStroke(stroke("b"))
        d.flushUntilClean()
        assertEquals("INSERT OR IGNORE INTO period (id, calendarId, kind, date) VALUES (?, ?, ?, ?)", fake.execs.single()[0].sql)
    }

    @Test
    fun leavingAPageFlushesItAfterReadingTheNext() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        fake.calls.clear(); fake.execs.clear()
        d.addStroke(stroke("a"))
        d.show(nextMonth)
        // The target's two reads first; then the departing page's flush (mint + stroke + touch);
        // then the bookmark.
        assertEquals(listOf("query(page)", "query(period)", "exec(4)", "exec(3)"), fake.calls)
        assertEquals(nextMonth, d.target)
        assertEquals(0, d.strokes.size)
    }

    @Test
    fun aReplayOnAnotherPageNavigatesThereFirst() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        val s = stroke("a")
        d.addStroke(s)
        val drew = InkAction.Drew(d.pageId, s)
        d.flushUntilClean()
        // The fake never applies writes, so put the page into its picture as the store now holds it.
        val minted = fake.execs.first { it[0].sql.startsWith("INSERT OR IGNORE INTO period") }
        fake.period(text(minted[0].args[0]), CalendarTarget.KIND_MONTH, "2026-09-01")
        fake.page(text(minted[1].args[0]), text(minted[0].args[0]), 0, 1404f, 1872f, listOf(0L to s))
        d.show(nextMonth)
        fake.execs.clear()

        assertTrue(d.revert(drew))
        assertEquals(month, d.target)
        assertEquals(0, d.strokes.size)
        assertTrue(fake.sql().contains("DELETE FROM stroke WHERE id = ?"))

        assertTrue(d.reapply(drew))
        assertEquals(listOf("a"), d.strokes.map { it.id })
    }

    @Test
    fun aReplayForAPageNeverShownIsSkipped() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        fake.execs.clear()
        assertFalse(d.revert(InkAction.Drew("never-shown", stroke("a"))))
        assertTrue(fake.execs.isEmpty())
        assertEquals(month, d.target)
    }

    // ── Marks ────────────────────────────────────────────────────────────────

    @Test
    fun showingAPageReadsItsMarksForTheWholeGrid() = runBlocking {
        val sep3 = LocalDate.of(2026, 9, 3)
        val marks = FakeMarks(mapOf(sep3 to listOf(mark("Ann"))))
        val d = doc(FakeCalendarStore(), marks)
        assertEquals(emptyMap<LocalDate, List<DayMark>>(), d.marks)   // empty before the first show
        d.show(month)
        // The month grid's 42 cells, out-of-month days included.
        assertEquals(listOf(LocalDate.of(2026, 8, 30) to LocalDate.of(2026, 10, 10)), marks.ranges)
        assertEquals(mapOf(sep3 to listOf(mark("Ann"))), d.marks)
    }

    @Test
    fun showingTheSamePageReadsNoMarks_unlessTheReturnPathAsks() = runBlocking {
        val fake = FakeCalendarStore()
        val marks = FakeMarks()
        val d = doc(fake, marks)
        d.show(month)
        assertEquals(1, marks.reads)
        fake.calls.clear()

        // Same target, no refresh: nothing at all.
        d.show(month)
        assertEquals(1, marks.reads)
        assertTrue(fake.calls.isEmpty())

        // Same target, refresh: exactly one marks read and NOT one store call — no page read, no
        // flush, no bookmark.
        d.show(month, refreshMarks = true)
        assertEquals(2, marks.reads)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun navigatingToAnotherPageReReadsTheMarks() = runBlocking {
        val marks = FakeMarks()
        val d = doc(FakeCalendarStore(), marks)
        d.show(month)
        d.show(nextMonth)
        assertEquals(2, marks.reads)
        assertEquals(LocalDate.of(2026, 9, 27) to LocalDate.of(2026, 11, 7), marks.ranges[1])
    }

    @Test
    fun aDayPageAsksAboutItsOneDay() = runBlocking {
        val marks = FakeMarks()
        val d = doc(FakeCalendarStore(), marks)
        d.show(CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", CalendarTarget.HALF_PM))
        assertEquals(listOf(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 1)), marks.ranges)
    }

    @Test
    fun aFailedMarksReadThrowsAndChangesNothing() = runBlocking {
        val sep3 = LocalDate.of(2026, 9, 3)
        val fake = FakeCalendarStore()
        val source = SwitchableMarks(FakeMarks(mapOf(sep3 to listOf(mark("Ann")))))
        val d = CalendarDocument(CalendarStore(fake), source) { surface }
        d.show(month)
        d.addStroke(stroke("a"))
        val marksBefore = d.marks

        source.live = FakeMarks(fail = true)
        assertTrue(runCatching { d.show(nextMonth) }.exceptionOrNull() is StoreUnavailable)
        assertEquals(month, d.target)
        assertEquals(marksBefore, d.marks)
        assertEquals(listOf("a"), d.strokes.map { it.id })

        assertTrue(runCatching { d.show(month, refreshMarks = true) }.exceptionOrNull() is StoreUnavailable)
        assertEquals(marksBefore, d.marks)
    }

    @Test
    fun aPasteMintsThePageAheadOfItsInk_andUndoTakesExactlyItAway() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        d.addStroke(stroke("own"))
        d.flushUntilClean()
        fake.execs.clear()
        val action = d.paste(listOf(stroke("p1", 10), stroke("p2", 20)))!!
        assertEquals(d.pageId, action.pageId)
        assertEquals(listOf("p1", "p2"), action.strokes.map { it.id })
        assertEquals(listOf(1L, 2L), action.orders)
        assertFalse(d.hasUnsavedChanges)
        val batch = fake.execs.single()
        assertEquals(2, batch.count { it.sql.startsWith("INSERT OR REPLACE INTO stroke") })
        assertEquals(3, d.strokes.size)
        assertEquals(listOf("own", "p1", "p2"), d.captureInk().map { it.second.id })
        assertNull(d.paste(emptyList()))

        fake.execs.clear()
        d.revert(action)
        assertEquals(listOf("own"), d.strokes.map { it.id })
        assertEquals(2, fake.execs.single().count { it.sql.startsWith("DELETE FROM stroke") })
        d.reapply(action)
        assertEquals(listOf("own", "p1", "p2"), d.captureInk().map { it.second.id })
        assertEquals(listOf(0L, 1L, 2L), d.captureInk().map { it.first })
    }

    @Test
    fun aPasteOnAPageWithNoRowMintsIt() = runBlocking {
        val fake = FakeCalendarStore()
        val d = doc(fake)
        d.show(month)
        fake.execs.clear()
        d.paste(listOf(stroke("p1")))
        val batch = fake.execs.single()
        assertTrue(batch[0].sql.startsWith("INSERT OR IGNORE INTO period"))
        assertTrue(batch[1].sql.startsWith("INSERT OR IGNORE INTO page"))
        assertTrue(batch[2].sql.startsWith("INSERT OR REPLACE INTO stroke"))
    }

    /** A [MarkSource] whose answer can be swapped mid-test. */
    private class SwitchableMarks(var live: MarkSource) : MarkSource {
        override fun marksFor(from: LocalDate, to: LocalDate) = live.marksFor(from, to)
    }
}
