package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import com.symmetricalpalmtree.soil.seam.SeamCalBacklink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalLinksModelTest {

    private fun row(linkId: String, date: String, itemId: String = "nb", pageId: String = "p1", kind: String = "notebook", name: String = "Study", pageNumber: Int = 4) =
        SeamCalBacklink(linkId, itemId, kind, name, pageId, pageNumber, date)

    @Test
    fun `the scope is the period showing, a Month its own days`() {
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 30), CalLinksModel.scopeOf(CalendarTarget.of(CalendarTarget.KIND_MONTH, LocalDate.of(2026, 9, 8))))
        assertEquals(LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 2, 28), CalLinksModel.scopeOf(CalendarTarget.of(CalendarTarget.KIND_MONTH, LocalDate.of(2026, 2, 8))))
        assertEquals(LocalDate.of(2026, 9, 6) to LocalDate.of(2026, 9, 12), CalLinksModel.scopeOf(CalendarTarget.of(CalendarTarget.KIND_WEEK, LocalDate.of(2026, 9, 8))))
        assertEquals(LocalDate.of(2026, 9, 8) to LocalDate.of(2026, 9, 8), CalLinksModel.scopeOf(CalendarTarget.of(CalendarTarget.KIND_DAY, LocalDate.of(2026, 9, 8), CalendarTarget.HALF_PM)))
    }

    @Test
    fun `one place is one entry naming its days in order, groups by the earliest day then name then page`() {
        val groups = CalLinksModel.group(
            listOf(
                row("c", "2026-10-09", itemId = "doc", pageId = "", kind = "document", name = "Sermon", pageNumber = 0),
                row("a", "2026-10-08"),
                row("b", "2026-10-06"),
                row("b2", "2026-10-06"),
                row("d", "2026-10-08", pageId = "p2", pageNumber = 2),
                row("e", "nonsense"),
            ),
        )
        assertEquals(3, groups.size)
        assertEquals(listOf("2026-10-06", "2026-10-08"), groups[0].dates)
        assertEquals("p1", groups[0].pageId)
        assertEquals("p2", groups[1].pageId)
        assertEquals("doc", groups[2].itemId)
        assertEquals("Tue, Oct 6, 2026; Thu, Oct 8, 2026", CalLinksModel.detail(groups[0]))
        assertTrue(CalLinksModel.group(emptyList()).isEmpty())
    }
}
