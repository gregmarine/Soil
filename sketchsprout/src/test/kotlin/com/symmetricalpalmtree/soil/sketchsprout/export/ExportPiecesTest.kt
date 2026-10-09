package com.symmetricalpalmtree.soil.sketchsprout.export

import com.symmetricalpalmtree.soil.seam.SeamSql
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPiecesTest {

    @Test
    fun `a relabel moves the root and every parent, and passes the checker`() {
        val s = Relabel.statements("aaaa-1", "bbbb-2")
        assertEquals(2, s.size)
        assertTrue(s[0].contains("SET id = 'bbbb-2' WHERE id = 'aaaa-1'"))
        assertTrue(s[1].contains("SET parentId = 'bbbb-2' WHERE parentId = 'aaaa-1'"))
        for (sql in s) { SeamSql.checkExec(sql); assertEquals(0, SeamSql.bindCount(sql)) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a relabel refuses an id that is not plain`() {
        Relabel.statements("a'b", "c")
    }

    @Test
    fun `a plan keeps the item's order and numbers, narrowed by the ids asked for`() {
        val all = listOf(SketchPage("a", 10f, 20f, ""), SketchPage("b", 10f, 20f, ""), SketchPage("c", 10f, 20f, ""))
        val ready = RenderPlan.of(all, listOf("c", "a")) as RenderPlan.Outcome.Ready
        assertEquals(listOf("a", "c"), ready.pages.map { it.ref.id })
        assertEquals(listOf(1, 3), ready.pages.map { it.number })
        assertEquals(3, (RenderPlan.of(all, emptyList()) as RenderPlan.Outcome.Ready).pages.size)
        assertTrue(RenderPlan.of(all, listOf("zz")) is RenderPlan.Outcome.Empty)
        assertTrue(RenderPlan.of(listOf(SketchPage("a", 0f, 20f, "")), emptyList()) is RenderPlan.Outcome.Damaged)
    }
}
