package com.symmetricalpalmtree.soil.docsprout.editor.bible

import com.symmetricalpalmtree.soil.docsprout.data.BibleUnlinked
import com.symmetricalpalmtree.soil.docsprout.editor.proofread.ProofreadCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pass that links references as they are typed: what it links, what it leaves, and the Markdown it writes. */
class ReferenceLinkerTest {

    private fun plan(text: String, caret: Int? = null, unlinked: Set<String> = emptySet(), region: ProofreadCheck.Region? = null) =
        ReferenceLinker.plan(text, region ?: ProofreadCheck.Region(0, text.length), ReferenceLinker.markdownProtected(text), unlinked, caret)

    @Test
    fun `a reference in prose is a hit with its words and wire`() {
        val p = plan("see Romans 8:28 today\n")
        assertEquals(1, p.hits.size)
        assertEquals("Romans 8:28", p.hits[0].words)
        assertEquals("ROM:8:28-8:28", p.hits[0].wire)
        assertEquals("bible:ROM:8:28-8:28", p.hits[0].address)
        assertEquals(4, p.hits[0].start)
        assertFalse(p.heldByCaret)
    }

    @Test
    fun `what is already a link, code or an address is left alone`() {
        assertTrue(plan("[John 3:16](bible:JHN:3:16-3:16) and [John 3:16](http://x) and `John 3:16`\n").hits.isEmpty())
        assertTrue(plan("```\nJohn 3:16\n```\n").hits.isEmpty())
        assertEquals(listOf("Acts 1:3"), plan("[John 3:16](soil:abc) Acts 1:3\n").hits.map { it.words })
    }

    @Test
    fun `a reference the writer unlinked stays plain, the same words anywhere`() {
        val skip = setOf(BibleUnlinked.key("John 3:16", "JHN:3:16-3:16"))
        assertTrue(plan("John 3:16 and later john  3:16", unlinked = skip).hits.isEmpty())
        assertEquals(listOf("Jn. 3:16"), plan("John 3:16 and Jn. 3:16", unlinked = skip).hits.map { it.words })
    }

    @Test
    fun `a reference the caret is touching waits, and the plan says so`() {
        val text = "see John 3:16"
        for (caret in listOf(4, 8, 13)) {
            val p = plan(text, caret = caret)
            assertTrue("caret $caret", p.hits.isEmpty())
            assertTrue(p.heldByCaret)
        }
        val away = plan("see John 3:16 x", caret = 15)
        assertEquals(1, away.hits.size)
        assertFalse(away.heldByCaret)
        assertEquals(1, plan(text, caret = 3).hits.size)
    }

    @Test
    fun `only the changed lines are read`() {
        val text = "John 3:16\nActs 1:3\nPsalm 23\n"
        val p = plan(text, region = ProofreadCheck.Region(12, 13))
        assertEquals(listOf("Acts 1:3"), p.hits.map { it.words })
    }

    @Test
    fun `the Markdown is rewritten last first and the caret carried past what grew`() {
        val text = "John 3:16 and Acts 1:3 end"
        val hits = plan(text).hits
        val (out, caret) = ReferenceLinker.rewriteMarkdown(text, hits, text.length)
        assertEquals("[John 3:16](bible:JHN:3:16-3:16) and [Acts 1:3](bible:ACT:1:3-1:3) end", out)
        assertEquals(out.length, caret)
        val (_, before) = ReferenceLinker.rewriteMarkdown(text, hits, 0)
        assertEquals(0, before)
        val (_, between) = ReferenceLinker.rewriteMarkdown(text, hits, 12)
        assertEquals(12 + "[](bible:JHN:3:16-3:16)".length, between)
    }

    @Test
    fun `the Link tool finds the reference the caret or the selection touches`() {
        val text = "see John 3:16 today\nActs 1:3\n"
        assertEquals("John 3:16", ReferenceLinker.hitAt(text, 8, 8)?.words)
        assertEquals("John 3:16", ReferenceLinker.hitAt(text, 4, 4)?.words)
        assertEquals("John 3:16", ReferenceLinker.hitAt(text, 13, 13)?.words)
        assertEquals("John 3:16", ReferenceLinker.hitAt(text, 4, 13)?.words)
        assertEquals("John 3:16", ReferenceLinker.hitAt(text, 2, 6)?.words)
        assertEquals("Acts 1:3", ReferenceLinker.hitAt(text, 22, 22)?.words)
        assertEquals(null, ReferenceLinker.hitAt(text, 15, 18))
        assertEquals(null, ReferenceLinker.hitAt("plain words", 3, 3))
    }
}
