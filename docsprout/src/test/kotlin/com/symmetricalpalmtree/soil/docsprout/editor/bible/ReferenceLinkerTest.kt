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

    @Test
    fun `a reference never runs across a line break`() {
        for (text in listOf("1. Genesis\n2. Exodus\n", "John\n3:16\n", "Read Genesis\n1 today\n")) {
            assertTrue(text, plan(text).hits.none { it.words.contains('\n') })
            assertTrue(text, (0..text.length).mapNotNull { ReferenceLinker.hitAt(text, it, it) }.none { it.words.contains('\n') })
        }
    }

    @Test
    fun `a verse range is linked whole`() {
        val cases = mapOf(
            "John 3:14-17" to "JHN:3:14-3:17",
            "John 3:16" to "JHN:3:16-3:16",
            "John 3:14–17" to "JHN:3:14-3:17",
            "1 John 3:14-17" to "1JN:3:14-3:17",
            "Psalm 119:1-8, 12" to "PSA:119:1-119:8,PSA:119:12-119:12",
        )
        for ((words, wire) in cases) {
            for (text in listOf("$words\n", "see $words today\n", "$words\n2 more\n")) {
                val p = plan(text, caret = text.length)
                assertEquals(text, listOf(words), p.hits.map { it.words })
                assertEquals(text, listOf(wire), p.hits.map { it.wire })
            }
        }
        assertEquals(listOf("Genesis 1:1", "Exodus 3:14"), plan("Genesis 1:1; Exodus 3:14\n").hits.map { it.words })
    }

    @Test
    fun `a reference still being typed waits for its verses`() {
        // Each prefix of "John 3:14-17" as typed, the caret at its end: nothing shorter is linked.
        val full = "John 3:14-17"
        for (n in 1..full.length) {
            val text = full.substring(0, n)
            val p = plan(text, caret = n)
            assertTrue("typed '$text'", p.hits.isEmpty())
        }
        // The same with a pause after the colon, the dash or a comma, with spaces around them.
        for (text in listOf("John 3:", "John 3:14-", "John 3:14 -", "John 3:14 - ", "Psalm 119:1-8,", "Psalm 119:1-8, ")) {
            val p = plan(text, caret = text.length)
            assertTrue("typed '$text'", p.hits.isEmpty())
            assertTrue("typed '$text'", p.heldByCaret)
        }
        // Moved on past it: linked.
        assertEquals(listOf("John 3:14-17"), plan("John 3:14-17 and", caret = 16).hits.map { it.words })
        assertEquals(listOf("John 3"), plan("John 3: and", caret = 11).hits.map { it.words })
        assertEquals(listOf("John 3:16"), plan("John 3:16. Then", caret = 15).hits.map { it.words })
    }
}
