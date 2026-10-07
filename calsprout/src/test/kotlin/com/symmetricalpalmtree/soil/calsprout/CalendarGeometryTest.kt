package com.symmetricalpalmtree.soil.calsprout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The three pages' rects and their hit-tests — at the Nomad's size and at pages it never sees. */
class CalendarGeometryTest {

    /**
     * The Nomad: 1404 × 1872 at 1.875. The bars float over the page — they are no
     * longer a layout input, so the geometry is built from just the page size and density.
     */
    private val nomad = CalendarGeometry.month(1404, 1872, 1.875f)
    private val nomadWeek = CalendarGeometry.week(1404, 1872, 1.875f)
    private val nomadDay = CalendarGeometry.day(1404, 1872, 1.875f)
    private val september = LocalDate.of(2026, 9, 1)   // a Tuesday; the grid opens Sun Aug 30
    private val augustSunday = LocalDate.of(2026, 8, 30)   // the Sunday the week of Sep 1 opens on

    @Test
    fun hairlineIsRoundedDensity_andEveryEdgeIsAnInteger() {
        assertEquals(2, nomad.hairline)
        assertEquals(1, CalendarGeometry.month(1000, 1000, 1f).hairline)
        assertEquals(1, CalendarGeometry.month(1000, 1000, 0.75f).hairline)
        assertEquals(3, CalendarGeometry.month(1000, 1000, 3.0f).hairline)
    }

    @Test
    fun cellsAreSquareFromTheWidth_andTheNotesBandTakesTheRest() {
        val g = nomad
        assertEquals((1404 - 6 * 2) / 7, g.cell)          // 198
        assertEquals(198, g.cell)
        assertEquals(3, g.left)                             // (1404 − (7·198 + 6·2)) / 2
        assertEquals(1401, g.contentRight)
        assertEquals(0, g.headerTop)                        // no top-bar inset: the bars float
        assertEquals(0 + 75, g.headerBottom)                // 40 dp → 75 px
        assertEquals(g.headerBottom + 2, g.gridTop)
        assertEquals(g.gridTop + 6 * 198 + 5 * 2, g.gridBottom)
        assertEquals(g.gridBottom + 2, g.notesTop)
        assertEquals(1872, g.notesBottom)                   // bottom = heightPx, no bottom-bar inset
        assertTrue("notes band ${g.notesHeight}", g.notesHeight > 300)
        // Nothing is a proportional slice of the height: the same width at a taller page gives the
        // same cells and a taller band.
        val taller = CalendarGeometry.month(1404, 2400, 1.875f)
        assertEquals(g.cell, taller.cell)
        assertEquals(g.notesHeight + (2400 - 1872), taller.notesHeight)
    }

    @Test
    fun dividersSitOnIntegerEdgesBetweenCells() {
        val g = nomad
        for (c in 1..6) {
            assertEquals(g.cellLeft(c) - g.hairline, g.columnDividerX(c))
            assertEquals(g.cellLeft(c - 1) + g.cell, g.columnDividerX(c))
        }
        for (r in 1..5) {
            assertEquals(g.cellTop(r) - g.hairline, g.rowDividerY(r))
            assertEquals(g.cellTop(r - 1) + g.cell, g.rowDividerY(r))
        }
    }

    @Test
    fun aShortPageShrinksTheCellsRatherThanRunningOffThePage() {
        val g = CalendarGeometry.month(1404, 900, 1.875f)
        assertTrue(g.cell < 198)
        assertTrue(g.gridBottom + g.hairline <= 900)        // bottom = heightPx, no bottom-bar inset
        // Integer cells leave at most the division's remainder as a band — a few px, never negative.
        assertTrue("band ${g.notesHeight}", g.notesHeight in 0..6)
        assertTrue(g.notesBottom >= g.notesTop)
    }

    @Test
    fun hitTestNamesTheCellsDay() {
        val g = nomad
        // Top-left cell = the grid's first cell, Sunday Aug 30; row 0 col 2 = Tue Sep 1.
        assertEquals(LocalDate.of(2026, 8, 30), g.hitTest(g.cellLeft(0) + 1f, g.cellTop(0) + 1f, september))
        assertEquals(september, g.hitTest(g.cellLeft(2) + 10f, g.cellTop(0) + 10f, september))
        // Row 4 col 3 = Sep 30 (Wed); row 5 col 6 = Oct 10, an out-of-month cell — writable, hit-testable.
        assertEquals(LocalDate.of(2026, 9, 30), g.hitTest(g.cellLeft(3) + 10f, g.cellTop(4) + 10f, september))
        assertEquals(LocalDate.of(2026, 10, 10), g.hitTest(g.cellLeft(6) + g.cell - 1f, g.cellTop(5) + g.cell - 1f, september))
    }

    @Test
    fun hitTestIsNullOffTheGrid() {
        val g = nomad
        assertNull(g.hitTest(10f, g.headerTop + 5f, september))                     // the header
        assertNull(g.hitTest(10f, g.notesTop + 5f, september))                      // the Notes band
        assertNull(g.hitTest(0f, g.cellTop(0) + 5f, september))                     // the left margin (left = 3)
        assertNull(g.hitTest(g.contentRight.toFloat(), g.cellTop(0) + 5f, september))
        assertNull(g.hitTest(g.columnDividerX(1).toFloat(), g.cellTop(0) + 5f, september))   // on a divider
        assertNull(g.hitTest(g.cellLeft(1) + 5f, g.rowDividerY(1).toFloat(), september))
        // Month keeps its own header (headerTop = 0, gridTop = 77), so y = 5 is in it.
        assertNull(g.hitTest(10f, 5f, september))
        assertNull(g.hitTest(10f, 1871f, september))                                // deep in the Notes band
    }

    // ── Week ─────────────────────────────────────────────────────────────────

    @Test
    fun weekCellsAreTheWidthsQuarter_andItsBandIsMonthsBand() {
        val g = nomadWeek
        assertEquals(2, g.hairline)
        assertEquals((1404 - 3 * 2) / 4, g.cellW)            // 349
        assertEquals(349, g.cellW)
        assertEquals(1, g.left)                                // (1404 − (4·349 + 3·2)) / 2
        assertEquals(1403, g.contentRight)
        assertEquals(0, g.cellsTop)                          // no top-bar inset: the bars float
        // The cell area IS the Month page's grid area, so the two bands match — to the one px
        // that halving an odd area cannot give back.
        assertTrue("cellsBottom ${g.cellsBottom} vs ${nomad.gridBottom}", nomad.gridBottom - g.cellsBottom in 0..1)
        assertEquals(636, g.cellH)
        assertEquals(g.cellsTop + 2 * g.cellH + g.hairline, g.cellsBottom)
        assertEquals(g.cellsBottom + g.hairline, g.notesTop)
        assertEquals(1872, g.notesBottom)                    // bottom = heightPx, no bottom-bar inset
        assertTrue("week ${g.notesHeight} vs month ${nomad.notesHeight}", Math.abs(g.notesHeight - nomad.notesHeight) <= 1)
    }

    @Test
    fun weekDividersSitOnIntegerEdgesBetweenCells() {
        val g = nomadWeek
        for (c in 1..3) {
            assertEquals(g.cellLeft(c) - g.hairline, g.columnDividerX(c))
            assertEquals(g.cellLeft(c - 1) + g.cellW, g.columnDividerX(c))
        }
        assertEquals(g.cellTop(1) - g.hairline, g.rowDividerY())
        assertEquals(g.cellTop(0) + g.cellH, g.rowDividerY())
    }

    @Test
    fun weekHitTestNamesSundayThroughSaturday() {
        val g = nomadWeek
        for (index in 0..6) {
            val row = index / 4
            val col = index % 4
            assertEquals(
                "cell $index",
                augustSunday.plusDays(index.toLong()),
                g.hitTest(g.cellLeft(col) + 10f, g.cellTop(row) + 10f, augustSunday),
            )
        }
    }

    @Test
    fun weekHitTestIsNullForTheSpareCellAndEverythingThatIsNotACell() {
        val g = nomadWeek
        assertNull(g.hitTest(g.cellLeft(3) + 10f, g.cellTop(1) + 10f, augustSunday))   // the spare 8th cell
        assertNull(g.hitTest(10f, g.notesTop + 5f, augustSunday))                      // the Notes band
        assertNull(g.hitTest(0f, g.cellTop(0) + 5f, augustSunday))                     // the left margin
        assertNull(g.hitTest(g.contentRight.toFloat(), g.cellTop(0) + 5f, augustSunday))
        assertNull(g.hitTest(g.columnDividerX(1).toFloat(), g.cellTop(0) + 5f, augustSunday))
        assertNull(g.hitTest(g.cellLeft(1) + 5f, g.rowDividerY().toFloat(), augustSunday))
        // Week has no header of its own, so cellsTop is 0 and (10, 5) lands inside the first
        // cell — there is no point "above the grid" except off the page entirely (y < 0).
        assertNull(g.hitTest(10f, -1f, augustSunday))
        assertNull(g.hitTest(10f, 1871f, augustSunday))                                // deep in the Notes band
    }

    // ── Day ──────────────────────────────────────────────────────────────────

    @Test
    fun dayRowsShareTheWholeHeightOfThePage_andTheLastRowTakesTheRemainder() {
        val g = nomadDay
        assertEquals(2, g.hairline)
        // (1872 − 0 − 23 × 2) / 24 = 1826 / 24 = 76, remainder 2 px — the last row's.
        assertEquals(76, g.rowHeight)
        assertEquals(78, g.pitch)                              // rowHeight + hairline
        assertEquals(0, g.rowsTop)                             // no top-bar inset: the bars float
        assertEquals(150, g.gutterLeft)                        // round(80 × 1.875)
        assertEquals(152, g.gutterRight)
        assertEquals(0, g.left)
        assertEquals(1404, g.right)
        assertEquals(1872, g.rowsBottom)                        // the page's own bottom, exactly
        for (i in 0 until CalendarGeometry.DAY_ROWS) assertEquals(0 + i * 78, g.rowTop(i))
        for (i in 0 until CalendarGeometry.DAY_ROWS - 1) assertEquals(76, g.rowHeight(i))
        assertEquals(76 + 2, g.rowHeight(23))
        assertEquals(g.rowsBottom, g.rowTop(23) + g.rowHeight(23))
        for (i in 1..23) assertEquals(g.rowTop(i - 1) + g.rowHeight, g.rowDividerY(i))
    }

    @Test
    fun dayRowsGrowWithThePage_theMantaGetsTallerRowsThanTheNomad() {
        // A taller page is taller rows, not a band: the Day page is the one height-derived layout.
        val taller = CalendarGeometry.day(1404, 2400, 1.875f)
        assertTrue(taller.rowHeight > nomadDay.rowHeight)
        assertEquals((2400 - 23 * 2) / 24, taller.rowHeight)
        assertEquals(2400, taller.rowsBottom)
        assertTrue(taller.rowHeight(23) - taller.rowHeight in 0 until CalendarGeometry.DAY_ROWS)
    }

    @Test
    fun aShortDayPageShrinksTheRowsRatherThanRunningOffThePage() {
        val g = CalendarGeometry.day(1404, 900, 1.875f)
        assertTrue("row ${g.rowHeight}", g.rowHeight in 1 until 76)
        assertEquals(900, g.rowsBottom)
        assertTrue("last row at ${g.rowTop(23) + g.rowHeight(23)}", g.rowTop(23) + g.rowHeight(23) <= 900)
    }

    @Test
    fun dayRowLabelsAreTwelveHourAndBuiltFromInts() {
        val am = (0 until CalendarGeometry.DAY_ROWS).map { CalendarGeometry.dayRowLabel(0, it) }
        assertEquals("12:00 AM", am[0])
        assertEquals("12:30 AM", am[1])
        assertEquals("1:00 AM", am[2])
        assertEquals("11:00 AM", am[22])
        assertEquals("11:30 AM", am[23])
        val pm = (0 until CalendarGeometry.DAY_ROWS).map { CalendarGeometry.dayRowLabel(1, it) }
        assertEquals("12:00 PM", pm[0])
        assertEquals("12:30 PM", pm[1])
        assertEquals("1:00 PM", pm[2])
        assertEquals("11:30 PM", pm[23])
        // The two halves are the same twelve clock faces, only the suffix differs.
        assertEquals(am.map { it.removeSuffix(" AM") }, pm.map { it.removeSuffix(" PM") })
    }
}
