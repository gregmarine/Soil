package com.symmetricalpalmtree.soil.pad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.core.StrokeCodec
import com.symmetricalpalmtree.soil.paper.store.Cell
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every statement the pad sends, pinned as exact text and arguments (arc 22 / X2) — the host's
 * validator is run over each one, so a shape it would refuse fails here rather than on the device,
 * and the `"order"` quoting is pinned because an unquoted ORDER is a syntax error.
 */
class ScratchSqlTest {

    private fun stroke(id: String = "s1") = Stroke(
        id = id,
        points = listOf(StrokePoint(1f, 2f, 0.5f, 0.25f, 0L), StrokePoint(3f, 4f, 0.6f, 0.3f, 0L)),
        color = Stroke.BLACK,
        width = 3f,
        style = StrokeStyle.PEN,
    )

    // ── Writes ───────────────────────────────────────────────────────────────

    @Test
    fun insertPage_ignoresOnConflict_andNeverReplaces() {
        val s = ScratchSql.insertPage("p1", 2, 1404f, 1872f, 99L)
        assertEquals(
            "INSERT OR IGNORE INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
            s.sql,
        )
        assertEquals(
            listOf<Cell>(
                Cell.Text("p1"), Cell.Integer(2), Cell.Real(1404.0), Cell.Real(1872.0),
                Cell.Integer(99), Cell.Integer(99),
            ),
            s.args,
        )
        // The trap the schema's cascade sets: REPLACE would delete the page row first, and that
        // delete takes the page's strokes with it.
        assertTrue(!s.sql.contains("REPLACE"))
    }

    @Test
    fun sizePage_andPosition() {
        val size = ScratchSql.sizePage("p1", 800f, 1000f, 7L)
        assertEquals("UPDATE page SET width = ?, height = ?, updatedAt = ? WHERE id = ?", size.sql)
        assertEquals(listOf<Cell>(Cell.Real(800.0), Cell.Real(1000.0), Cell.Integer(7), Cell.Text("p1")), size.args)

        val position = ScratchSql.position("p1", 3)
        assertEquals("UPDATE page SET position = ? WHERE id = ?", position.sql)
        assertEquals(listOf<Cell>(Cell.Integer(3), Cell.Text("p1")), position.args)
    }

    @Test
    fun deletePage_andClearPage_areDifferentSentences() {
        val delete = ScratchSql.deletePage("p1")
        assertEquals("DELETE FROM page WHERE id = ?", delete.sql)
        assertEquals(listOf<Cell>(Cell.Text("p1")), delete.args)

        val clear = ScratchSql.clearPage("p1")
        assertEquals("DELETE FROM stroke WHERE pageId = ?", clear.sql)
        assertEquals(listOf<Cell>(Cell.Text("p1")), clear.args)
    }

    @Test
    fun putStroke_isIdempotent_andCarriesFormatBGeometry() {
        val s = ScratchSql.putStroke("p1", 4L, stroke())
        assertEquals(
            "INSERT OR REPLACE INTO stroke (id, pageId, \"order\", color, width, style, blob) VALUES (?, ?, ?, ?, ?, ?, ?)",
            s.sql,
        )
        assertEquals(Cell.Text("s1"), s.args[0])
        assertEquals(Cell.Text("p1"), s.args[1])
        assertEquals(Cell.Integer(4), s.args[2])
        assertEquals(Cell.Integer(Stroke.BLACK.toLong()), s.args[3])
        assertEquals(Cell.Real(3.0), s.args[4])
        assertEquals(Cell.Text("PEN"), s.args[5])
        // The blob is the `.soil`'s own stroke encoding, unchanged by the move to rows.
        val blob = (s.args[6] as Cell.Blob).value
        assertArrayEquals(
            StrokeCodec.encode(floatArrayOf(1f, 3f), floatArrayOf(2f, 4f), floatArrayOf(0.5f, 0.6f), floatArrayOf(0.25f, 0.3f)),
            blob,
        )
    }

    @Test
    fun dropStroke_andSetCurrent() {
        val drop = ScratchSql.dropStroke("s1")
        assertEquals("DELETE FROM stroke WHERE id = ?", drop.sql)
        assertEquals(listOf<Cell>(Cell.Text("s1")), drop.args)

        val current = ScratchSql.setCurrent("p2")
        assertEquals("INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)", current.sql)
        assertEquals(listOf<Cell>(Cell.Text("p2")), current.args)
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Test
    fun theFourReads() {
        val pages = ScratchSql.selectPages()
        assertEquals("SELECT id FROM page ORDER BY position", pages.sql)
        assertEquals(emptyList<Cell>(), pages.args)

        val current = ScratchSql.selectCurrent()
        assertEquals("SELECT value FROM state WHERE key = 'current'", current.sql)

        val size = ScratchSql.selectPageSize("p1")
        assertEquals("SELECT width, height FROM page WHERE id = ?", size.sql)
        assertEquals(listOf<Cell>(Cell.Text("p1")), size.args)

        val strokes = ScratchSql.selectStrokes("p1")
        assertEquals(
            "SELECT id, \"order\", color, width, style, blob FROM stroke WHERE pageId = ? ORDER BY \"order\"",
            strokes.sql,
        )
        assertEquals(listOf<Cell>(Cell.Text("p1")), strokes.args)
    }

    // ── The schema ───────────────────────────────────────────────────────────

    @Test
    fun theSchemaIsAtVersionOne_threeTablesAndACascade() {
        assertEquals(1, ScratchSchema.SCHEMA.version)
        val step = ScratchSchema.SCHEMA.steps[0]
        assertEquals(5, step.size)
        assertEquals(3, step.count { it.trimStart().startsWith("CREATE TABLE") })
        assertTrue(step.any { it.contains("ON DELETE CASCADE") })
    }

    /** REPLACE deletes the row it conflicts with first, and that delete would cascade. */
    @Test
    fun aPageRowIsNeverWrittenWithReplace() {
        assertTrue(ScratchSql.insertPage("p", 0, 0f, 0f, 0L).sql.startsWith("INSERT OR IGNORE INTO page"))
    }
}
