package com.symmetricalpalmtree.soil.docsprout.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassageTextTest {

    @Test
    fun `the label line is dropped and the paragraphs kept, a chapter line bare when plain`() {
        val md = "**John 3:36; Proverbs 3:5**\n\n36 Whoever believes\n\n**Proverbs 3**\n\n5 Trust in the LORD"
        val p = PassageText.paragraphs(md)
        assertEquals(listOf("36 Whoever believes", "**Proverbs 3**", "5 Trust in the LORD"), p.map { it.markdown })
        assertEquals(listOf("36 Whoever believes", "Proverbs 3", "5 Trust in the LORD"), p.map { it.plain })
        assertTrue(PassageText.paragraphs("").isEmpty())
        assertTrue(PassageText.paragraphs("**John 3:16**").isEmpty())
    }
}
