package com.symmetricalpalmtree.soil.docsprout.editor.rich

import com.symmetricalpalmtree.soil.markdown.rich.RichBlock
import com.symmetricalpalmtree.soil.markdown.rich.RichDoc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RichHistoryTest {

    private fun snap(text: String) = RichHistory.Snapshot(RichDoc(listOf(RichBlock(text = text))), 0, 0)

    @Test
    fun `a program edit keeps what was undone to redo, a writer's edit does not`() {
        val history = RichHistory()
        var current = snap("a")
        history.beforeEdit(typing = false, now = 0L) { current }
        current = snap("ab")
        assertTrue(history.undo({ current }) { current = it })
        assertEquals(1, history.redoSteps)

        history.beforeEdit(typing = false, now = 10L, keepRedo = true) { current }
        assertEquals(1, history.redoSteps)

        history.beforeEdit(typing = false, now = 20L) { current }
        assertEquals(0, history.redoSteps)
    }

    @Test
    fun `the oldest steps go once the documents held pass the cap, the newest always kept`() {
        val history = RichHistory(maxSteps = 100, maxChars = 30)
        repeat(10) { history.beforeEdit(typing = false, now = it * 10_000L) { snap("x".repeat(9)) } }
        // Ten characters a step (nine and the line break): three fit.
        assertEquals(3, history.steps)

        val big = RichHistory(maxSteps = 100, maxChars = 5)
        big.beforeEdit(typing = false, now = 0L) { snap("x".repeat(50)) }
        assertEquals(1, big.steps)
    }

    @Test
    fun `steps are capped by count too`() {
        val history = RichHistory(maxSteps = 4, maxChars = Long.MAX_VALUE)
        repeat(10) { history.beforeEdit(typing = false, now = it * 10_000L) { snap("a") } }
        assertEquals(4, history.steps)
    }
}
