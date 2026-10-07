package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import java.time.LocalDate

/**
 * Where a finger double-tap on a calendar page goes — pure, JVM-tested.
 * Month / Week: a cell hit opens that day; the Notes band toggles the chrome; the header, the side
 * margins, a hairline and the spare Week cell are nobody's. Day: the whole page toggles — there is
 * no band and nothing to open. An unknown kind is nothing.
 *
 * The band's x-range is the page's full width: the band is everything below the grid, the side
 * margins included, so a tap beside the "Notes" ruling is still a tap in the band.
 */
object CalendarDoubleTap {

    /** What the screen should do with the tap. */
    sealed class Decision {
        /** Open [date] as a Day page. */
        data class OpenDay(val date: LocalDate) : Decision()

        /** Flip the chrome. */
        object Toggle : Decision()

        /** Nothing, silently — there is no wrong page to land on. */
        object Nothing : Decision()
    }

    /**
     * The decision for a double-tap at ([x], [y]) on the page [kind] showing [date] (a Month's
     * first, a Week's Sunday, a Day's day). [month] and [week] are that page's geometry — the
     * caller passes only the one its kind needs, and a null where a geometry is wanted is
     * [Decision.Nothing] rather than a guess.
     */
    fun decide(
        kind: Int,
        x: Float,
        y: Float,
        date: LocalDate,
        month: CalendarGeometry.Month?,
        week: CalendarGeometry.Week?,
    ): Decision = when (kind) {
        CalendarTarget.KIND_MONTH -> {
            val g = month ?: return Decision.Nothing
            g.hitTest(x, y, date)?.let { Decision.OpenDay(it) }
                ?: inBand(x, y, g.width, g.notesTop, g.notesBottom)
        }
        CalendarTarget.KIND_WEEK -> {
            val g = week ?: return Decision.Nothing
            g.hitTest(x, y, date)?.let { Decision.OpenDay(it) }
                ?: inBand(x, y, g.width, g.notesTop, g.notesBottom)
        }
        CalendarTarget.KIND_DAY -> Decision.Toggle
        else -> Decision.Nothing
    }

    /** The Notes band, `[notesTop, notesBottom)` over the page's whole width. */
    private fun inBand(x: Float, y: Float, width: Int, notesTop: Int, notesBottom: Int): Decision =
        if (y >= notesTop && y < notesBottom && x >= 0f && x < width) Decision.Toggle else Decision.Nothing
}
