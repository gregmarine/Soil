package com.symmetricalpalmtree.soil.calsprout

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every statement the calendar sends, pinned: exact text + bound arguments, and the schema's
 * shape. `SeamSchema` validates DDL at construction, so constructing [CalendarSchema.SCHEMA] is
 * itself a check that Soil's checker would accept it; the statements are run through the seam's
 * query/exec gate here so a shape it refuses fails on the JVM, never at the seam.
 */
class CalendarSqlTest {

    private fun stroke() = Stroke(
        id = "s1",
        points = listOf(StrokePoint(1f, 2f, 0.5f, 0.25f, 0L), StrokePoint(3f, 4f, 0.6f, 0.3f, 0L)),
        color = Stroke.BLACK,
        width = 3f,
    )

    @Test
    fun schemaShape() {
        assertEquals("calsprout", CalendarSchema.KIND)
        assertEquals(2, CalendarSchema.SCHEMA.version)
        val step = CalendarSchema.SCHEMA.steps[0]
        assertEquals(6, step.size)
        assertEquals(5, step.count { it.trimStart().startsWith("CREATE TABLE") })
        // Every per-calendar table names its calendar, or hangs off a row that does.
        assertTrue(step[1].contains("calendarId TEXT NOT NULL REFERENCES calendar(id) ON DELETE CASCADE"))
        assertTrue(step[1].contains("UNIQUE(calendarId, kind, date)"))
        assertTrue(step[2].contains("UNIQUE(periodId, half)"))
        assertTrue(step[5].contains("PRIMARY KEY(calendarId, key)"))
        assertEquals(4, step.count { it.contains("ON DELETE CASCADE") })
    }

    @Test
    fun everyStatementPassesTheSeamGate() {
        val s = stroke()
        listOf(
            CalendarSql.insertCalendar("c", "Calendar", 0L),
            CalendarSql.insertPeriod("p", "c", 0, "2026-09-01"),
            CalendarSql.insertPage("g", "c", 0, "2026-09-01", 0, 1f, 1f, 0L),
            CalendarSql.sizePage("g", 1f, 1f, 0L),
            CalendarSql.touchPage("g", 0L),
            CalendarSql.putStroke("g", 0L, s),
            CalendarSql.dropStroke("s"),
            CalendarSql.setState("c", "k", "v"),
        ).forEach { SeamSql.checkExec(it.sql); assertEquals(it.sql, SeamSql.bindCount(it.sql), it.args.size) }
        listOf(
            CalendarSql.selectCalendar("c"),
            CalendarSql.selectPeriod("c", 0, "2026-09-01"),
            CalendarSql.selectPage("c", 0, "2026-09-01", 0),
            CalendarSql.selectStrokes("g"),
            CalendarSql.selectMaxOrder("g"),
            CalendarSql.selectState("c"),
            CalendarSql.selectCounts(),
        ).forEach { SeamSql.checkQuery(it.sql); assertEquals(it.sql, SeamSql.bindCount(it.sql), it.args.size) }
    }

    @Test
    fun calendarPeriodAndPageAreInsertOrIgnore_neverReplace() {
        val calendar = CalendarSql.insertCalendar("default", "Calendar", 5L)
        assertEquals("INSERT OR IGNORE INTO calendar (id, name, createdAt) VALUES (?, ?, ?)", calendar.sql)
        assertEquals(listOf(Cell.Text("default"), Cell.Text("Calendar"), Cell.Integer(5)), calendar.args)

        val period = CalendarSql.insertPeriod("p1", "default", 1, "2026-08-30")
        assertEquals("INSERT OR IGNORE INTO period (id, calendarId, kind, date) VALUES (?, ?, ?, ?)", period.sql)
        assertEquals(listOf(Cell.Text("p1"), Cell.Text("default"), Cell.Integer(1), Cell.Text("2026-08-30")), period.args)

        val page = CalendarSql.insertPage("g1", "default", 2, "2026-09-01", 1, 1404f, 1872f, 99L)
        assertEquals(
            "INSERT OR IGNORE INTO page (id, periodId, half, width, height, createdAt, updatedAt) " +
                "VALUES (?, (SELECT id FROM period WHERE calendarId = ? AND kind = ? AND date = ?), ?, ?, ?, ?, ?)",
            page.sql,
        )
        assertEquals(
            listOf(
                Cell.Text("g1"), Cell.Text("default"), Cell.Integer(2), Cell.Text("2026-09-01"), Cell.Integer(1),
                Cell.Real(1404.0), Cell.Real(1872.0), Cell.Integer(99), Cell.Integer(99),
            ),
            page.args,
        )
        // REPLACE's delete cascades. No row-minting statement may carry it.
        assertTrue(!calendar.sql.contains("REPLACE"))
        assertTrue(!period.sql.contains("REPLACE"))
        assertTrue(!page.sql.contains("REPLACE"))
    }

    @Test
    fun pageUpdates() {
        val size = CalendarSql.sizePage("g1", 800f, 1000f, 7L)
        assertEquals("UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?", size.sql)
        assertEquals(listOf(Cell.Real(800.0), Cell.Real(1000.0), Cell.Integer(7), Cell.Text("g1")), size.args)
        val touch = CalendarSql.touchPage("g1", 8L)
        assertEquals("UPDATE page SET updatedAt = ? WHERE id = ?", touch.sql)
        assertEquals(listOf(Cell.Integer(8), Cell.Text("g1")), touch.args)
    }

    @Test
    fun strokeRowsAreThePads() {
        val s = stroke()
        val put = CalendarSql.putStroke("g1", 4L, s)
        assertEquals(
            "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
            put.sql,
        )
        assertEquals(Cell.Text("s1"), put.args[0])
        assertEquals(Cell.Text("g1"), put.args[1])
        assertEquals(Cell.Integer(4), put.args[2])
        assertEquals(Cell.Integer(Stroke.BLACK.toLong()), put.args[3])
        assertEquals(Cell.Real(3.0), put.args[4])
        assertEquals(Cell.Text("PEN"), put.args[5])
        assertArrayEquals(StrokeBlob.encode(s), (put.args[6] as Cell.Blob).value)

        val drop = CalendarSql.dropStroke("s1")
        assertEquals("DELETE FROM stroke WHERE id = ?", drop.sql)
        assertEquals(listOf(Cell.Text("s1")), drop.args)
    }

    @Test
    fun stateRows() {
        val s = CalendarSql.setState("default", CalendarSql.KEY_LAST_DATE, "2026-09-01")
        assertEquals("INSERT OR REPLACE INTO state (calendarId, key, value) VALUES (?, ?, ?)", s.sql)
        assertEquals(listOf(Cell.Text("default"), Cell.Text("lastDate"), Cell.Text("2026-09-01")), s.args)
        assertEquals("lastView", CalendarSql.KEY_LAST_VIEW)
        assertEquals("lastHalf", CalendarSql.KEY_LAST_HALF)
        val read = CalendarSql.selectState("default")
        assertEquals("SELECT key, value FROM state WHERE calendarId = ?", read.sql)
        assertEquals(listOf(Cell.Text("default")), read.args)
    }

    @Test
    fun reads() {
        assertEquals("SELECT id FROM calendar WHERE id = ?", CalendarSql.selectCalendar("default").sql)

        val period = CalendarSql.selectPeriod("default", 0, "2026-09-01")
        assertEquals("SELECT id FROM period WHERE calendarId = ? AND kind = ? AND date = ?", period.sql)
        assertEquals(listOf(Cell.Text("default"), Cell.Integer(0), Cell.Text("2026-09-01")), period.args)

        val page = CalendarSql.selectPage("default", 2, "2026-09-01", 1)
        assertEquals(
            "SELECT page.id AS id, page.periodId AS periodId, page.width AS width, page.height AS height " +
                "FROM page JOIN period ON period.id = page.periodId " +
                "WHERE period.calendarId = ? AND period.kind = ? AND period.date = ? AND page.half = ?",
            page.sql,
        )
        assertEquals(listOf(Cell.Text("default"), Cell.Integer(2), Cell.Text("2026-09-01"), Cell.Integer(1)), page.args)

        assertEquals(
            "SELECT id, \"order\", color, width, style, blob FROM stroke WHERE pageId = ? ORDER BY \"order\"",
            CalendarSql.selectStrokes("g1").sql,
        )
        assertEquals("SELECT COALESCE(MAX(\"order\"), -1) AS maxOrder FROM stroke WHERE pageId = ?", CalendarSql.selectMaxOrder("g1").sql)
        assertEquals(
            "SELECT (SELECT COUNT(*) FROM period) AS periods, (SELECT COUNT(*) FROM page) AS pages, (SELECT COUNT(*) FROM stroke) AS strokes, (SELECT COUNT(*) FROM event) AS events",
            CalendarSql.selectCounts().sql,
        )
    }
}
