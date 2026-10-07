package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RenderKeyTest {

    private val month = CalendarTarget.of(CalendarTarget.KIND_MONTH, LocalDate.of(2026, 9, 8))
    private val week = CalendarTarget.of(CalendarTarget.KIND_WEEK, LocalDate.of(2026, 9, 8))
    private val dayPm = CalendarTarget.of(CalendarTarget.KIND_DAY, LocalDate.of(2026, 9, 8), CalendarTarget.HALF_PM)

    @Test
    fun `a key is the kind letter and the period's date, whichever half is showing`() {
        assertEquals("M:2026-09-01", RenderKey.of(month))
        assertEquals("W:2026-09-06", RenderKey.of(week))
        assertEquals("D:2026-09-08", RenderKey.of(dayPm))
    }

    @Test
    fun `a key reads back as its pages, a Day as AM then PM`() {
        assertEquals(listOf(month), RenderKey.pagesOf("M:2026-09-01"))
        assertEquals(listOf(week), RenderKey.pagesOf("W:2026-09-06"))
        val day = RenderKey.pagesOf("D:2026-09-08")!!
        assertEquals(2, day.size)
        assertEquals(listOf(CalendarTarget.HALF_AM, CalendarTarget.HALF_PM), day.map { it.half })
        assertEquals(listOf("2026-09-08", "2026-09-08"), day.map { it.date })
    }

    @Test
    fun `a key this build does not read is null`() {
        assertNull(RenderKey.pagesOf(""))
        assertNull(RenderKey.pagesOf("M"))
        assertNull(RenderKey.pagesOf("X:2026-09-01"))
        assertNull(RenderKey.pagesOf("M:nonsense"))
        assertNull(RenderKey.pagesOf("M:2026-09-08"))   // not the month's first
        assertNull(RenderKey.pagesOf("W:2026-09-08"))   // not a Sunday
        assertNull(RenderKey.pagesOf("notebook-id"))
    }

    @Test
    fun `the stem names the period and the title names a Day's half`() {
        assertEquals("Calendar - September 2026", RenderKey.stemOf(month))
        assertEquals("Calendar - Week of 2026-09-06", RenderKey.stemOf(week))
        assertEquals("Calendar - 2026-09-08", RenderKey.stemOf(dayPm))
        assertEquals("", RenderKey.titleOf(month))
        assertEquals("", RenderKey.titleOf(week))
        assertEquals("AM", RenderKey.titleOf(RenderKey.pagesOf("D:2026-09-08")!![0]))
        assertEquals("PM", RenderKey.titleOf(dayPm))
    }
}
