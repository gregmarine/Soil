package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import com.symmetricalpalmtree.soil.paper.store.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [CalendarStore] over the statement-recording fake: the calendar's row, the bookmark, the page read. */
class CalendarStoreTest {

    private val month = CalendarTarget(CalendarTarget.KIND_MONTH, "2026-09-01", 0)
    private val dayPm = CalendarTarget(CalendarTarget.KIND_DAY, "2026-09-01", 1)

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK,
        width = 3f,
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value

    @Test
    fun ensureCalendarMintsTheRowOnceAndNeverAgain() {
        val fake = FakeCalendarStore()
        CalendarStore(fake).ensureCalendar()
        assertEquals(listOf("query(calendar)", "exec(1)"), fake.calls)
        assertEquals("INSERT OR IGNORE INTO calendar (id, name, createdAt) VALUES (?, ?, ?)", fake.sql().single())
        assertEquals("default", text(fake.statements.single().args[0]))

        val second = FakeCalendarStore()
        second.calendar("default")
        CalendarStore(second).ensureCalendar()
        assertEquals(listOf("query(calendar)"), second.calls)
        assertTrue(second.execs.isEmpty())
    }

    @Test
    fun readingTheBookmarkWritesNothing() {
        val fake = FakeCalendarStore()
        assertNull(CalendarStore(fake).readBookmark())
        assertEquals(listOf("query(state)"), fake.calls)
        assertTrue(fake.execs.isEmpty())

        fake.state["lastView"] = "2"; fake.state["lastDate"] = "2026-09-01"; fake.state["lastHalf"] = "1"
        val at = CalendarStore(fake).readBookmark()!!
        assertEquals(2, at.kind)
        assertEquals(LocalDate.of(2026, 9, 1), at.date)
        assertEquals(1, at.half)
        assertEquals(dayPm, at.target)
    }

    @Test
    fun aBadBookmarkReadsAsNone() {
        for (bad in listOf(
            mapOf("lastView" to "0", "lastDate" to "2026-09-15", "lastHalf" to "0"),   // not a month start
            mapOf("lastView" to "1", "lastDate" to "2026-09-01", "lastHalf" to "0"),   // not a Sunday
            mapOf("lastView" to "0", "lastDate" to "2026-09-01", "lastHalf" to "1"),   // a half on a month
            mapOf("lastView" to "7", "lastDate" to "2026-09-01", "lastHalf" to "0"),   // unknown kind
            mapOf("lastView" to "0", "lastDate" to "yesterday", "lastHalf" to "0"),
            mapOf("lastView" to "0", "lastDate" to "2026-09-01"),                      // a row missing
        )) {
            val fake = FakeCalendarStore()
            fake.state.putAll(bad)
            assertNull("$bad", CalendarStore(fake).readBookmark())
        }
    }

    @Test
    fun readingAPageThatDoesNotExistWritesNothing_andSaysSo() {
        val fake = FakeCalendarStore()
        val stored = CalendarStore(fake).readPage(month)
        assertNull(stored.periodId)
        assertNull(stored.pageId)
        assertEquals(0f, stored.width, 0f)
        assertTrue(stored.strokes.isEmpty())
        assertEquals(listOf("query(page)", "query(period)"), fake.calls)
        assertTrue(fake.execs.isEmpty())
    }

    @Test
    fun readingTheOtherHalfOfADayFindsThePeriodButNoPage() {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_DAY, "2026-09-01")
        fake.page("am", "per", 0, 1404f, 1872f)
        val stored = CalendarStore(fake).readPage(dayPm)
        assertEquals("per", stored.periodId)
        assertNull(stored.pageId)
    }

    @Test
    fun readingAnExistingPageIsTheJoinThenTheStrokes() {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_MONTH, "2026-09-01")
        fake.page("pg", "per", 0, 800f, 1000f, listOf(0L to stroke("a"), 5L to stroke("b", 9)))
        val stored = CalendarStore(fake).readPage(month)
        assertEquals("per", stored.periodId)
        assertEquals("pg", stored.pageId)
        assertEquals(800f, stored.width, 0f)
        assertEquals(listOf(0L to "a", 5L to "b"), stored.strokes.map { it.first to it.second.id })
        assertEquals(listOf("query(page)", "query(strokes)"), fake.calls)
    }

    @Test
    fun anotherCalendarsPageIsNotThisOnes() {
        val fake = FakeCalendarStore()
        fake.period("per", CalendarTarget.KIND_MONTH, "2026-09-01", calendarId = "work")
        fake.page("pg", "per", 0, 800f, 1000f)
        assertNull(CalendarStore(fake).readPage(month).pageId)
        assertEquals("pg", CalendarStore(fake, "work").readPage(month).pageId)
    }

    @Test
    fun saveBookmarkIsOneBatchOfThreeRows() {
        val fake = FakeCalendarStore()
        CalendarStore(fake).saveBookmark(dayPm)
        val batch = fake.execs.single()
        assertEquals(3, batch.size)
        assertEquals(listOf("lastView" to "2", "lastDate" to "2026-09-01", "lastHalf" to "1"), batch.map { text(it.args[1]) to text(it.args[2]) })
        assertTrue(batch.all { text(it.args[0]) == "default" })
    }

    @Test
    fun mintRowsIsPeriodThenPage_bothOrIgnore() {
        val fake = FakeCalendarStore()
        val rows = CalendarStore(fake).mintRows(dayPm, "per", "pg", 1404f, 1872f, 5L)
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO period (id, calendarId, kind, date) VALUES (?, ?, ?, ?)",
                "INSERT OR IGNORE INTO page (id, periodId, half, width, height, createdAt, updatedAt) VALUES (?, (SELECT id FROM period WHERE calendarId = ? AND kind = ? AND date = ?), ?, ?, ?, ?, ?)",
            ),
            rows.map { it.sql },
        )
        assertEquals(Cell.Integer(1), rows[1].args[4])   // the PM half
    }

    @Test
    fun everyStoreFailureReadsAsUnavailable() {
        for (failure in listOf(SecurityException("revoked"), IllegalArgumentException("refused"), RuntimeException("binder gone"))) {
            val fake = FakeCalendarStore()
            fake.failWith = { failure }
            val thrown = runCatching { CalendarStore(fake).readBookmark() }.exceptionOrNull()
            assertTrue("was $thrown for $failure", thrown is StoreUnavailable)
        }
    }
}
