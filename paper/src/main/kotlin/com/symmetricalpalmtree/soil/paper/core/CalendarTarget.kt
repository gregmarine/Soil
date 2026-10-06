package com.symmetricalpalmtree.soil.paper.core

import java.time.LocalDate

/**
 * One calendar page, named: a month, a week, or one half of a day. Notesprout SN's
 * `CalendarTarget`, here in `:paper` so the calendar, a notebook's link picker and a document's
 * Link tool name a page the same way.
 *
 * [kind] is one of [KIND_MONTH] / [KIND_WEEK] / [KIND_DAY]. [date] is the period's ISO day
 * (`yyyy-MM-dd`), **already normalized** — a month's first day, a week's Sunday, the day itself
 * ([CalendarDates.isNormalized]); anything else is rejected rather than corrected, because a
 * caller that named the 15th as a month page has a bug, not a rounding problem. [half] is 0 for a
 * month or a week and 0 (AM) or 1 (PM) for a day.
 *
 * The constructor `require`s **are** the validation: a target that fails them is an
 * `IllegalArgumentException` wherever it is built.
 */
class CalendarTarget(
    val kind: Int,
    val date: String,
    val half: Int,
) {

    init {
        requireValid(kind, date, half)
    }

    /** The target's day as a [LocalDate] — valid by construction. */
    val localDate: LocalDate get() = LocalDate.parse(date)

    override fun equals(other: Any?): Boolean =
        other is CalendarTarget && other.kind == kind && other.date == date && other.half == half

    override fun hashCode(): Int = (kind * 31 + date.hashCode()) * 31 + half

    override fun toString(): String = "CalendarTarget(kind=$kind, date=$date, half=$half)"

    companion object {
        /** A month page — [date] is the month's first day. */
        const val KIND_MONTH: Int = 0

        /** A week page — [date] is the week's Sunday. */
        const val KIND_WEEK: Int = 1

        /** One half of a day — [date] is the day, [half] says which ledger. */
        const val KIND_DAY: Int = 2

        /** The AM half of a day page (midnight to noon). */
        const val HALF_AM: Int = 0

        /** The PM half of a day page (noon to midnight). */
        const val HALF_PM: Int = 1

        /** The constructor's checks, pure so they are JVM-testable. */
        fun requireValid(kind: Int, date: String, half: Int) {
            require(kind == KIND_MONTH || kind == KIND_WEEK || kind == KIND_DAY) { "unknown kind ($kind)" }
            val day = requireNotNull(CalendarDates.parse(date)) { "date is not an ISO day" }
            require(CalendarDates.isNormalized(kind, day)) { "date is not normalized for kind $kind" }
            require(half == HALF_AM || (kind == KIND_DAY && half == HALF_PM)) { "half $half is not legal for kind $kind" }
        }

        /** A target from a [LocalDate], normalized here — the constructor for a caller that has a
         *  day in hand rather than a page. */
        fun of(kind: Int, day: LocalDate, half: Int = HALF_AM): CalendarTarget =
            CalendarTarget(kind, CalendarDates.format(CalendarDates.periodDate(kind, day)), half)
    }
}
