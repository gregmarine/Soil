package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.soil.paper.core.CalendarDates
import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import com.symmetricalpalmtree.soil.seam.SeamCalBacklink
import java.time.LocalDate

/**
 * What the calendar's **Links** panel shows, as arithmetic (pure, JVM-tested): the days the page
 * showing covers, and the rows of Soil's link index grouped into the places that link to them —
 * a notebook page, or a document. The panel itself is `:paper`'s shared backlinks panel.
 *
 * **Scope is the period showing**: a Day its day, a Week its seven days, a Month its own days
 * (not the grid's spare cells: a link to the 3rd of next month is next month's). **A place is
 * one row, whatever it links to**: a page linking three days reads as one entry naming all
 * three, in day order. Groups sort by the earliest day they name, then name, then page.
 *
 * Never logged: an item's name is the person's own word.
 */
object CalLinksModel {

    /** The first and last day of the page [t] shows. */
    fun scopeOf(t: CalendarTarget): Pair<LocalDate, LocalDate> {
        val date = t.localDate
        return when (t.kind) {
            CalendarTarget.KIND_MONTH -> date to date.plusMonths(1).minusDays(1)
            CalendarTarget.KIND_WEEK -> date to date.plusDays(6)
            else -> date to date
        }
    }

    /** One entry of the panel: everything one place links to in the scope, its days in order. */
    data class Group(
        val itemId: String,
        val pageId: String,
        val kind: String,
        val name: String,
        val pageNumber: Int,
        val dates: List<String>,
    ) {
        val firstDate: String get() = dates.first()
    }

    /** The rows as the panel shows them: one group per place, the days de-duplicated and in
     *  order; a row whose day this build cannot read is dropped whole. */
    fun group(rows: List<SeamCalBacklink>): List<Group> {
        val byPlace = LinkedHashMap<Pair<String, String>, MutableList<SeamCalBacklink>>()
        for (row in rows) {
            if (CalendarDates.parse(row.date) == null) continue
            byPlace.getOrPut(row.sourceItemId to row.sourcePageId) { ArrayList() }.add(row)
        }
        val groups = byPlace.map { (place, keyed) ->
            val first = keyed.first()
            Group(place.first, place.second, first.sourceKind, first.sourceName, first.pageNumber, keyed.map { it.date }.distinct().sorted())
        }
        return groups.sortedWith(compareBy<Group> { it.firstDate }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.pageNumber })
    }

    /** A row's second line: the days it links to, as the calendar names them. */
    fun detail(group: Group): String =
        group.dates.mapNotNull { CalendarDates.parse(it) }.joinToString("; ") { CalendarDates.dayLabel(it) }
}
