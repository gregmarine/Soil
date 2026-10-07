package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The zone rule over the Nomad's page: 1404 × 1872 at density 1.875,
 * full page — no insets since F4. Every expected point is derived from the geometry, never from
 * a run.
 */
class CalendarDoubleTapTest {

    private val month = CalendarGeometry.month(1404, 1872, 1.875f)
    private val week = CalendarGeometry.week(1404, 1872, 1.875f)

    /** September 2026 — the grid's first cell is Sunday 30 August. */
    private val monthStart: LocalDate = LocalDate.of(2026, 9, 1)

    /** The week of Sunday 30 August 2026. */
    private val sunday: LocalDate = LocalDate.of(2026, 8, 30)

    private fun month(x: Float, y: Float) =
        CalendarDoubleTap.decide(CalendarTarget.KIND_MONTH, x, y, monthStart, month, null)

    private fun week(x: Float, y: Float) =
        CalendarDoubleTap.decide(CalendarTarget.KIND_WEEK, x, y, sunday, null, week)

    // ── Month ────────────────────────────────────────────────────────────────

    @Test
    fun `month cell centre opens that day`() {
        // Row 0, column 2: the grid opens on Sunday 30 August, so this is Tuesday 1 September.
        val x = month.cellLeft(2) + month.cell / 2f
        val y = month.cellTop(0) + month.cell / 2f
        assertEquals(CalendarDoubleTap.Decision.OpenDay(LocalDate.of(2026, 9, 1)), month(x, y))
    }

    @Test
    fun `month header is nothing`() {
        val y = (month.headerTop + month.headerBottom) / 2f
        assertEquals(CalendarDoubleTap.Decision.Nothing, month(month.width / 2f, y))
    }

    @Test
    fun `month hairline is nobody's`() {
        // The divider before column 1: its own pixels belong to no cell.
        val x = month.columnDividerX(1) + 0.5f
        val y = month.cellTop(0) + month.cell / 2f
        assertEquals(CalendarDoubleTap.Decision.Nothing, month(x, y))
    }

    @Test
    fun `month side margin beside the grid is nothing`() {
        val y = month.cellTop(0) + month.cell / 2f
        assertEquals(CalendarDoubleTap.Decision.Nothing, month(0f, y))
    }

    @Test
    fun `month notes band toggles at both its edges and across the full width`() {
        assertEquals(CalendarDoubleTap.Decision.Toggle, month(month.width / 2f, month.notesTop.toFloat()))
        assertEquals(CalendarDoubleTap.Decision.Toggle, month(month.width / 2f, month.notesBottom - 1f))
        val mid = (month.notesTop + month.notesBottom) / 2f
        assertEquals(CalendarDoubleTap.Decision.Toggle, month(0f, mid))
        assertEquals(CalendarDoubleTap.Decision.Toggle, month(month.width - 1f, mid))
    }

    @Test
    fun `month notes bottom itself is off the page`() {
        assertEquals(
            CalendarDoubleTap.Decision.Nothing,
            month(month.width / 2f, month.notesBottom.toFloat()),
        )
    }

    @Test
    fun `month without its geometry is nothing`() {
        assertEquals(
            CalendarDoubleTap.Decision.Nothing,
            CalendarDoubleTap.decide(CalendarTarget.KIND_MONTH, 100f, 100f, monthStart, null, null),
        )
    }

    // ── Week ─────────────────────────────────────────────────────────────────

    @Test
    fun `week first cell opens its sunday`() {
        val x = week.cellLeft(0) + week.cellW / 2f
        val y = week.cellTop(0) + week.cellH / 2f
        assertEquals(CalendarDoubleTap.Decision.OpenDay(sunday), week(x, y))
    }

    @Test
    fun `week spare cell is nobody's`() {
        // Index row * 4 + col: row 1, column 3 is the eighth cell — blank and nobody's day.
        val x = week.cellLeft(3) + week.cellW / 2f
        val y = week.cellTop(1) + week.cellH / 2f
        assertEquals(CalendarDoubleTap.Decision.Nothing, week(x, y))
    }

    @Test
    fun `week notes band toggles`() {
        assertEquals(CalendarDoubleTap.Decision.Toggle, week(week.width / 2f, week.notesTop.toFloat()))
        assertEquals(CalendarDoubleTap.Decision.Toggle, week(0f, week.notesBottom - 1f))
        assertEquals(
            CalendarDoubleTap.Decision.Nothing,
            week(week.width / 2f, week.notesBottom.toFloat()),
        )
    }

    @Test
    fun `week without its geometry is nothing`() {
        assertEquals(
            CalendarDoubleTap.Decision.Nothing,
            CalendarDoubleTap.decide(CalendarTarget.KIND_WEEK, 100f, 100f, sunday, null, null),
        )
    }

    // ── Day and the unknown ──────────────────────────────────────────────────

    @Test
    fun `day toggles anywhere, with no geometry at all`() {
        val day = LocalDate.of(2026, 9, 9)
        for (point in listOf(0f to 0f, 700f to 900f, 1403f to 1871f)) {
            assertEquals(
                CalendarDoubleTap.Decision.Toggle,
                CalendarDoubleTap.decide(CalendarTarget.KIND_DAY, point.first, point.second, day, null, null),
            )
        }
    }

    @Test
    fun `an unknown kind is nothing`() {
        assertEquals(
            CalendarDoubleTap.Decision.Nothing,
            CalendarDoubleTap.decide(99, 100f, 100f, monthStart, month, week),
        )
    }
}
