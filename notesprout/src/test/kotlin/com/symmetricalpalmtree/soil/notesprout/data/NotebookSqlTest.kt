package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.SeamLinks
import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookSqlTest {

    private val stroke = Stroke(
        id = "s1",
        points = listOf(StrokePoint(1f, 2f, 0.5f, 0f, 0L), StrokePoint(3f, 4f, 0.6f, 0f, 0L)),
        color = 0xFF808080.toInt(), width = 3f, style = StrokeStyle.PEN,
    )

    private val link = com.symmetricalpalmtree.soil.notesprout.objects.PageLink(
        id = "l1", payload = "L1|1|0||p2", chrome = 1, x = 1f, y = 2f, width = 3f, height = 4f, order = 0, strokes = emptyList(),
    )

    private val writes = listOf(
        NotebookSql.insertRoot("nb", "Journal", 10L),
        NotebookSql.setLastOpened("nb", "p1", 10L),
        NotebookSql.setTitle("nb", "Journal", 10L),
        NotebookSql.insertPage("p1", "nb", 0, 1404f, 1872f, "", 10L),
        NotebookSql.setOrder("p1", 2, 10L),
        NotebookSql.softDelete("p1", 10L),
        NotebookSql.restore("p1"),
        NotebookSql.putStroke("p1", 7L, stroke, 10L),
        NotebookSql.dropStroke("s1", 10L),
        NotebookSql.insertLink(link, "p1", 0, 10L),
        NotebookSql.reparent("s1", "l1", 10L),
        NotebookSql.setLinkPayload("l1", "L1|1|0||p2", 10L),
        NotebookSql.mirror(link, "p1", "nb").single(),
        NotebookSql.mirrorDrop("l1"),
        NotebookSql.mirrorDropPage("p1"),
        NotebookSql.insertTemplate("t1", "nb", "LINED", 1404, 1872, byteArrayOf(1, 2), 10L),
        NotebookSql.setPageTemplate("p1", "t1", 10L),
        NotebookSql.insertRow(NotebookRow("r1", "p1", "heading", 2, text = "## T", x = 1f, y = 2f, width = 3f, height = 4f, flags = 2L), 10L),
        NotebookSql.mirrorRows("l1", "L1|1|0||p2", "p1", "nb").single(),
        NotebookSql.mirrorRows("b1", "L1|1|3|JHN:3:14-3:18,PRO:3:5-3:6|", "p1", "nb")[0],
    )


    private val reads = listOf(
        NotebookSql.selectLinkPayload("l1"),
        NotebookSql.selectRoot("nb"),
        NotebookSql.selectPages("nb"),
        NotebookSql.selectLiveDescendantIds("p1"),
        NotebookSql.selectStrokes("p1"),
        NotebookSql.selectObjects("p1"),
        NotebookSql.selectAllHeadings(),
        NotebookSql.selectMaxOrder("p1", "link"),
        NotebookSql.selectLinks("p1"),
        NotebookSql.selectLiveChildIds("p1", "stroke"),
        NotebookSql.selectTemplateDigests("nb"),
        NotebookSql.selectTemplateBlob("t1"),
        NotebookSql.selectRows(listOf("a", "b")),
        NotebookSql.selectLiveDescendantRows("p1"),
        NotebookSql.selectChildRows("l1", "stroke"),
    )

    @Test
    fun `every statement passes the seam's checker with its binds matched`() {
        for (s in writes) {
            SeamSql.checkExec(s.sql)
            assertEquals(s.sql, SeamSql.bindCount(s.sql), s.args.size)
        }
        for (s in reads) {
            SeamSql.checkQuery(s.sql)
            assertEquals(s.sql, SeamSql.bindCount(s.sql), s.args.size)
        }
    }

    @Test
    fun `order is always quoted`() {
        for (s in writes + reads) assertFalse(s.sql, Regex("[^\"]order[^\"]").containsMatchIn(s.sql.replace("\"order\"", "")))
    }

    @Test
    fun `a page row is never replaced`() {
        assertTrue(NotebookSql.insertPage("p", "nb", 0, 1f, 1f, "", 0L).sql.startsWith("INSERT OR IGNORE"))
        assertFalse(writes.any { it.sql.contains("OR REPLACE") })
    }

    @Test
    fun `a stroke is written as SN wrote it and keeps its createdAt on a re-put`() {
        val s = NotebookSql.putStroke("p1", 7L, stroke, 10L)
        assertEquals(Cell.Text("s1"), s.args[0])
        assertEquals(Cell.Text("p1"), s.args[1])
        assertEquals(Cell.Integer(7L), s.args[2])
        assertEquals(Cell.Text("#808080"), s.args[5])
        assertEquals(Cell.Real(3.0), s.args[6])
        assertEquals(Cell.Text("PEN"), s.args[7])
        assertTrue(s.args[8] is Cell.Blob)
        assertTrue(s.sql.contains("ON CONFLICT(id) DO UPDATE"))
        assertFalse(s.sql.substringAfter("DO UPDATE").contains("createdAt"))
        assertTrue(s.sql.contains("deletedAt = NULL"))
    }

    @Test
    fun `a dropped stroke is soft-deleted, never deleted`() {
        val s = NotebookSql.dropStroke("s1", 10L)
        assertTrue(s.sql.startsWith("UPDATE"))
        assertTrue(s.sql.contains("deletedAt = ?"))
        assertEquals(listOf<Cell>(Cell.Integer(10L), Cell.Text("s1")), s.args)
    }

    @Test
    fun `housekeeping never bumps updatedAt`() {
        assertFalse(NotebookSql.softDelete("x", 1L).sql.contains("updatedAt"))
        assertFalse(NotebookSql.restore("x").sql.contains("updatedAt"))
    }

    @Test
    fun `the mirror is written in Soil's statements, and only the mirror names a soil table`() {
        assertTrue(SeamSql.writesLinkMirror(NotebookSql.mirror(link, "p1", "nb").single().sql))
        assertTrue(SeamSql.writesLinkMirror(NotebookSql.mirrorDrop("l1").sql))
        assertTrue(SeamSql.writesLinkMirror(NotebookSql.mirrorDropPage("p1").sql))
        assertEquals(5, writes.count { SeamSql.writesLinkMirror(it.sql) })
        assertEquals(listOf<Cell>(Cell.Text("l1"), Cell.Text("p1"), Cell.Text("nb"), Cell.Text("p2")), NotebookSql.mirror(link, "p1", "nb").single().args)
        assertEquals(Cell.Null, NotebookSql.mirror(link.copy(payload = "L1|0|1|other|"), "p1", "nb").single().args[3])
    }

    @Test
    fun `a link to a day mirrors as one row the seam admits, and drops as one`() {
        val rows = NotebookSql.mirrorRows("c1", "L1|1|5|2026-10-06|", "p1", "nb")
        assertEquals(listOf(SeamLinks.PUT_CAL), rows.map { it.sql })
        assertEquals(listOf<Cell>(Cell.Text("c1"), Cell.Text("p1"), Cell.Text("2026-10-06")), rows.single().args)
        assertTrue(SeamSql.writesLinkMirror(rows.single().sql))
        assertEquals(listOf(Cell.Text("c1")), NotebookSql.mirrorDrops("c1", "L1|1|5|2026-10-06|").map { it.args[0] })
    }

    @Test
    fun `a link into the Bible mirrors one row per range, and drops them all`() {
        val wire = "JHN:3:14-3:18,PRO:3:5-3:6"
        val rows = NotebookSql.mirrorRows("b1", "L1|1|3|$wire|", "p1", "nb")
        assertEquals(2, rows.size)
        assertEquals(listOf(SeamLinks.PUT_BIBLE, SeamLinks.PUT_BIBLE), rows.map { it.sql })
        assertEquals(listOf<Cell>(Cell.Text("b1"), Cell.Text("p1"), Cell.Text(wire), Cell.Integer(43003014), Cell.Integer(43003018)), rows[0].args)
        assertEquals(Cell.Text("b1#1"), rows[1].args[0])
        val drops = NotebookSql.mirrorDrops("b1", "L1|1|3|$wire|")
        assertEquals(listOf(Cell.Text("b1"), Cell.Text("b1#1")), drops.map { it.args[0] })
        assertEquals(listOf(Cell.Text("l1")), NotebookSql.mirrorDrops("l1", "L1|1|0||p2").map { it.args[0] })
        assertEquals(1, NotebookSql.mirrorRows("x", "nonsense", "p1", "nb").size)
        assertEquals(SeamLinks.DROP, NotebookSql.mirrorRows("x", "nonsense", "p1", "nb").single().sql)
    }

    @Test
    fun `a template row carries its token and size, and a page points at it by refId`() {
        val t = NotebookSql.insertTemplate("t1", "nb", "IMG#0a1b2c3d", 1404, 1872, byteArrayOf(1), 10L)
        assertTrue(t.sql.contains("'template'"))
        assertEquals(Cell.Text("IMG#0a1b2c3d"), t.args[4])
        assertEquals(Cell.Real(1404.0), t.args[5])
        assertTrue(NotebookSql.setPageTemplate("p1", "", 10L).sql.contains("SET refId = ?"))
        assertTrue(NotebookSql.selectTemplateDigests("nb").sql.contains("length(blob) AS blobLength"))
    }

    @Test
    fun `a wrapped heading is listed for the Contents under its page, not its link`() {
        val sql = NotebookSql.selectAllHeadings().sql
        assertTrue(sql.contains("CASE WHEN p.type = 'link' THEN p.parentId ELSE h.parentId END AS parentId"))
    }

    @Test
    fun `the reads name the columns the decoders expect`() {
        val strokes: Statement = NotebookSql.selectStrokes("p")
        for (c in listOf("id", "\"order\"", "color", "strokeWidth", "style", "blob")) assertTrue(strokes.sql.contains(c))
        val pages = NotebookSql.selectPages("nb")
        for (c in listOf("id", "\"order\"", "width", "height", "refId")) assertTrue(pages.sql.contains(c))
        assertTrue(pages.sql.contains("deletedAt IS NULL"))
        assertTrue(strokes.sql.contains("deletedAt IS NULL"))
    }

    @Test
    fun `a whole row is written with every column and never replaced, and the whole-row reads name the row's columns`() {
        val row = NotebookRow("r1", "p1", "stroke", 2, color = "#000000", strokeWidth = 3f, style = "PEN", blob = byteArrayOf(1))
        val s = NotebookSql.insertRow(row, 10L)
        assertTrue(s.sql.startsWith("INSERT OR IGNORE INTO notebook (id, parentId, type, \"order\", text, refId, x, y, width, height, color, strokeWidth, style, flags, blob, createdAt, updatedAt)"))
        assertEquals(17, s.args.size)
        assertEquals(Cell.Integer(10L), s.args[15])
        for (read in listOf(NotebookSql.selectRows(listOf("a")), NotebookSql.selectLiveDescendantRows("p1"), NotebookSql.selectChildRows("l1", "stroke"))) {
            assertTrue(read.sql, read.sql.contains("SELECT id, parentId, type, \"order\", text, refId, x, y, width, height, color, strokeWidth, style, flags, blob FROM notebook"))
            assertTrue(read.sql.contains("deletedAt IS NULL"))
        }
        assertEquals(2, NotebookSql.selectRows(listOf("a", "b")).args.size)
    }

    @Test
    fun `a pasted link's mirror row is the same statement as a wrapped link's`() {
        assertEquals(NotebookSql.mirror(link, "p1", "nb").single().sql, NotebookSql.mirrorRows("l1", "L1|1|0||p2", "p1", "nb").single().sql)
        assertEquals(NotebookSql.mirror(link, "p1", "nb").single().args, NotebookSql.mirrorRows("l1", "L1|1|0||p2", "p1", "nb").single().args)
        assertTrue(SeamSql.writesLinkMirror(NotebookSql.mirrorRows("l1", "garbage", "p1", "nb").single().sql))
    }
}
