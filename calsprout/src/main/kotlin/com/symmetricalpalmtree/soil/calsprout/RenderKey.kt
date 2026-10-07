package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.soil.paper.core.CalendarDates
import com.symmetricalpalmtree.soil.paper.core.CalendarTarget

/**
 * **What an export of the calendar names**: the key Soil's export screen hands the render
 * service as its "item id" (`M:2026-09-01`, `W:2026-09-06`, `D:2026-09-08` — a kind letter and
 * the period's normalized date), the pages that key means (a Day is **two**, AM then PM — the
 * page shown and its other half), the file stem, and each page's title. Pure, so the shape is
 * JVM-tested; only the calendar writes the key and only its renderer reads it.
 */
object RenderKey {

    const val KIND = "calendar"

    private const val STEM = "Calendar"

    /** The key for the period [t] is a page of: the half is not in it, a Day exports whole. */
    fun of(t: CalendarTarget): String = when (t.kind) {
        CalendarTarget.KIND_MONTH -> "M:${t.date}"
        CalendarTarget.KIND_WEEK -> "W:${t.date}"
        else -> "D:${t.date}"
    }

    /** The pages [key] names, in export order; null for a key this build does not read. */
    fun pagesOf(key: String): List<CalendarTarget>? {
        if (key.length < 3 || key[1] != ':') return null
        val day = CalendarDates.parse(key.substring(2)) ?: return null
        val kind = when (key[0]) {
            'M' -> CalendarTarget.KIND_MONTH
            'W' -> CalendarTarget.KIND_WEEK
            'D' -> CalendarTarget.KIND_DAY
            else -> return null
        }
        // The date must already be the period's own: a key is written from a page, never typed.
        if (!CalendarDates.isNormalized(kind, day)) return null
        return if (kind == CalendarTarget.KIND_DAY) {
            listOf(CalendarTarget.of(kind, day, CalendarTarget.HALF_AM), CalendarTarget.of(kind, day, CalendarTarget.HALF_PM))
        } else {
            listOf(CalendarTarget.of(kind, day))
        }
    }

    /** The file stem: `Calendar - September 2026`, `Calendar - Week of 2026-09-06`, `Calendar - 2026-09-08`. */
    fun stemOf(t: CalendarTarget): String = when (t.kind) {
        CalendarTarget.KIND_MONTH -> "$STEM - ${CalendarDates.monthTitle(t.localDate)}"
        CalendarTarget.KIND_WEEK -> "$STEM - Week of ${t.date}"
        else -> "$STEM - ${t.date}"
    }

    /** A page's title in the bundle: a Day's half, nothing for a Month or a Week. */
    fun titleOf(t: CalendarTarget): String =
        if (t.kind == CalendarTarget.KIND_DAY) CalendarDates.HALF_NAMES[t.half] else ""
}
