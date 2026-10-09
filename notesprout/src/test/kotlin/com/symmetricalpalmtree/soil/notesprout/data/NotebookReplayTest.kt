package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import com.symmetricalpalmtree.soil.paper.store.Cell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * An object replay's order: the rows land first, the ink half after. A replay whose row writes
 * fail leaves the ink in memory as it was, so the screen's undo sees a step that never reached
 * the page and puts it back — never half applied.
 */
class NotebookReplayTest {

    private fun stroke(id: String) = Stroke(id = id, points = List(3) { StrokePoint(it.toFloat(), it.toFloat()) })

    private suspend fun opened(rows: FakeNotebookRows): NotebookDocument {
        val page = PageRef("p1", 1404f, 1872f, "blank")
        rows.pages = listOf(FakeNotebookRows.Page(page.id, page.width, page.height, page.templateId))
        val doc = NotebookDocument(NotebookStore(rows, "nb")) { }
        doc.load(NotebookStore.Loaded(listOf(page), page.id))
        return doc
    }

    @Test
    fun aMoveWhoseRowsFailLeavesTheInkWhereItWas() = runBlocking {
        val rows = FakeNotebookRows()
        val doc = opened(rows)
        doc.addStroke(stroke("s"))
        val inkMove = doc.move(listOf("s"), 10f, 0f)!!
        doc.flushUntilClean()
        val action = NotebookAction.Moved("p1", inkMove, listOf("h1"), emptyList(), emptyList(), 10f, 0f)
        rows.failWith = { IllegalStateException("store gone") }
        try {
            doc.revert(action)
            fail("the row write should have thrown")
        } catch (e: StoreUnavailable) {
            // expected
        }
        assertEquals(10f, doc.strokes.single().points.first().x, 0f)

        // Once the store answers, the same step replays whole: the rows first, then the ink half's
        // write (the flush before the page is read again).
        rows.failWith = null
        rows.execs.clear()
        doc.revert(action)
        assertEquals(2, rows.execs.size)
        assertTrue(rows.execs[0].single().sql.startsWith("UPDATE"))
        assertTrue(rows.execs[1].any { st -> st.args.any { it == Cell.Text("s") } })
    }
}
