package com.symmetricalpalmtree.soil.pad

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.ink.PageInk
import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScratchStore] over the statement-recording fake (arc 22 / X2): what the pad declares, what it
 * reads on the way in, and the exact statements a placement emits — including the one case a
 * transaction cannot cover on its own, a placement too big for one batch whose second batch fails.
 */
class ScratchStoreTest {

    private fun stroke(id: String, seed: Int = 0) = Stroke(
        id = id,
        points = List(4) { StrokePoint((it + seed).toFloat(), it * 1.5f + seed, 0.5f, 0.25f, 0L) },
        color = Stroke.BLACK,
        width = 3f,
    )

    private fun text(cell: Cell) = (cell as Cell.Text).value

    // ── load ─────────────────────────────────────────────────────────────────

    @Test
    fun firstRunCreatesOnePage_inOneTransaction() {
        val fake = FakeScratchStore()
        val loaded = ScratchStore(fake).load()

        assertEquals(listOf(loaded.currentId), loaded.ids)
        // The page read, then ONE exec holding both statements.
        assertEquals(listOf("query(pages)", "exec(2)"), fake.calls)
        val statements = fake.execs.single()
        assertTrue(statements[0].sql.startsWith("INSERT OR IGNORE INTO page"))
        assertEquals(loaded.currentId, text(statements[0].args[0]))
        assertEquals(Cell.Integer(0), statements[0].args[1])
        assertEquals(ScratchSql.setCurrent(loaded.currentId).sql, statements[1].sql)
        assertEquals(loaded.currentId, text(statements[1].args[0]))
    }

    @Test
    fun anExistingLibraryIsReadBack_andAnAgreeingCurrentIsNotRewritten() {
        val fake = FakeScratchStore()
        fake.page("p1")
        fake.page("p2")
        fake.current = "p2"

        val loaded = ScratchStore(fake).load()
        assertEquals(listOf("p1", "p2"), loaded.ids)
        assertEquals("p2", loaded.currentId)
        assertTrue("nothing should have been written", fake.execs.isEmpty())
    }

    @Test
    fun aCurrentThatIsNotAPageIsClampedAndTheRowCorrected() {
        val fake = FakeScratchStore()
        fake.page("p1")
        fake.page("p2")
        fake.current = "gone"

        val loaded = ScratchStore(fake).load()
        assertEquals("p1", loaded.currentId)
        assertEquals(listOf(ScratchSql.setCurrent("p1").sql), fake.sql())
        assertEquals("p1", text(fake.statements.single().args[0]))
    }

    @Test
    fun aMissingStateRowIsTheSameClamp() {
        val fake = FakeScratchStore()
        fake.page("p1")
        fake.current = null
        assertEquals("p1", ScratchStore(fake).load().currentId)
        assertEquals(listOf(ScratchSql.setCurrent("p1").sql), fake.sql())
    }

    // ── readPage ─────────────────────────────────────────────────────────────

    @Test
    fun readPage_readsTheSizeThenTheStrokes() {
        val fake = FakeScratchStore()
        fake.page("p1", PageInk(800f, 1000f, listOf(0L to stroke("a"), 5L to stroke("b", 9))))

        val ink = ScratchStore(fake).readPage("p1")
        assertEquals(800f, ink.width, 0f)
        assertEquals(1000f, ink.height, 0f)
        assertEquals(listOf(0L to "a", 5L to "b"), ink.strokes.map { it.first to it.second.id })
        assertEquals(listOf("query(size)", "query(strokes)"), fake.calls)
    }

    @Test
    fun aMissingPageRowReadsAsEmptyRatherThanThrowing() {
        val fake = FakeScratchStore()
        val ink = ScratchStore(fake).readPage("never-existed")
        assertEquals(0f, ink.width, 0f)
        assertEquals(emptyList<Pair<Long, Stroke>>(), ink.strokes)
    }

    // ── structural ───────────────────────────────────────────────────────────

    @Test
    fun insertPage_createsRenumbersAndNamesTheNewPageCurrent() {
        val fake = FakeScratchStore()
        val (ids, id) = ScratchStore(fake).insertPage(listOf("p1", "p2"), "p1")
        assertEquals(listOf("p1", id, "p2"), ids)
        val statements = fake.execs.single()
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO page (id, position, width, height, createdAt, updatedAt) VALUES (?, ?, ?, ?, ?, ?)",
                "UPDATE page SET position = ? WHERE id = ?",
                "UPDATE page SET position = ? WHERE id = ?",
                "UPDATE page SET position = ? WHERE id = ?",
                "INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)",
            ),
            statements.map { it.sql },
        )
        assertEquals(listOf("p1", id, "p2"), statements.drop(1).take(3).map { text(it.args[1]) })
        assertEquals(listOf(0L, 1L, 2L), statements.drop(1).take(3).map { (it.args[0] as Cell.Integer).value })
        assertEquals(id, text(statements.last().args[0]))
    }

    @Test
    fun deletingAPageDropsItAndRenumbersTheRest() {
        val fake = FakeScratchStore()
        val (rest, landing) = ScratchStore(fake).deletePage(listOf("p1", "p2", "p3"), "p2")
        assertEquals(listOf("p1", "p3"), rest)
        assertEquals("p1", landing)
        val statements = fake.execs.single()
        assertEquals("DELETE FROM page WHERE id = ?", statements[0].sql)
        assertEquals("p2", text(statements[0].args[0]))
        assertEquals(listOf("p1", "p3"), statements.drop(1).dropLast(1).map { text(it.args[1]) })
        assertEquals("p1", text(statements.last().args[0]))
    }

    @Test
    fun deletingTheLonePageEmptiesItInsteadOfRemovingIt() {
        val fake = FakeScratchStore()
        val (rest, landing) = ScratchStore(fake).deletePage(listOf("p1"), "p1")
        assertEquals(listOf("p1"), rest)
        assertEquals("p1", landing)
        // No `DELETE FROM page` — that would take the row and, with the cascade, be a different act.
        assertEquals(
            listOf("DELETE FROM stroke WHERE pageId = ?", "INSERT OR REPLACE INTO state (key, value) VALUES ('current', ?)"),
            fake.sql(),
        )
    }

    // ── the store is gone ────────────────────────────────────────────────────

    @Test
    fun everyStoreFailureReadsAsUnavailable() {
        for (failure in listOf(SecurityException("revoked"), IllegalArgumentException("refused"), RuntimeException("database gone"))) {
            val fake = FakeScratchStore()
            fake.failWith = { failure }
            var thrown: Throwable? = null
            try {
                ScratchStore(fake).load()
            } catch (e: StoreUnavailable) {
                thrown = e
            }
            assertTrue("was $thrown for $failure", thrown is StoreUnavailable)
        }
    }
}
