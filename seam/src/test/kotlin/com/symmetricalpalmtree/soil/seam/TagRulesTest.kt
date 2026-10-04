package com.symmetricalpalmtree.soil.seam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The one definition of "the same tag". Both sides of the seam ask these and must agree. */
class TagRulesTest {

    @Test
    fun `display trims the ends and collapses runs`() {
        assertEquals("Reading List", TagRules.display("  Reading   List  "))
        assertEquals("a b", TagRules.display("a\t\t b"))
        assertEquals("a b", TagRules.display("a\n b"))
        assertEquals("", TagRules.display("   "))
        assertEquals("", TagRules.display(""))
        assertEquals("Reading List", TagRules.display("Reading List"))
    }

    @Test
    fun `display keeps case`() {
        assertEquals("READING", TagRules.display("READING"))
    }

    @Test
    fun `identity folds case and whitespace together`() {
        assertEquals(TagRules.identityKey("  Reading   List "), TagRules.identityKey("reading list"))
        assertEquals("reading list", TagRules.identityKey("Reading List"))
    }

    @Test
    fun `identity is locale-neutral`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("title", TagRules.identityKey("TITLE"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `validity is measured on the normalized form`() {
        assertTrue(TagRules.isValid("reading list"))
        assertFalse(TagRules.isValid("   "))
        assertFalse(TagRules.isValid(""))
        val exactly = "x".repeat(TagRules.MAX_TAG_CHARS)
        assertTrue(TagRules.isValid("   $exactly   "))
        assertFalse(TagRules.isValid(exactly + "x"))
    }

    @Test
    fun `normalized text carries no separators`() {
        val d = TagRules.display("a\tb\nc  d")
        assertFalse('\t' in d)
        assertFalse('\n' in d)
        assertEquals("a b c d", d)
    }

    @Test
    fun `a prefill is normalized, cut to the cap, and never splits a surrogate pair`() {
        assertEquals("reading list", TagRules.prefill("  reading   list \n"))
        assertEquals("two lines", TagRules.prefill("two\nlines"))
        assertNull(TagRules.prefill("   \n\t "))
        assertNull(TagRules.prefill(""))
        assertEquals(TagRules.MAX_TAG_CHARS, TagRules.prefill("x".repeat(TagRules.MAX_TAG_CHARS + 40))!!.length)
        val head = "a".repeat(TagRules.MAX_TAG_CHARS - 1)
        val cut = TagRules.prefill(head + "🌱" + "tail")!!
        assertEquals(head, cut)
        val whole = TagRules.prefill("a".repeat(TagRules.MAX_TAG_CHARS - 2) + "🌱" + "tail")!!
        assertEquals(TagRules.MAX_TAG_CHARS, whole.length)
        assertTrue(whole.endsWith("🌱"))
    }

    @Test
    fun `isId accepts a canonical UUID and nothing else`() {
        assertTrue(TagRules.isId("11111111-1111-4111-8111-111111111111"))
        assertTrue(TagRules.isId(java.util.UUID.randomUUID().toString()))
        assertTrue(TagRules.isId("AAAAAAAA-1111-4111-8111-111111111111"))
        for (bad in listOf(
            "", " ", "n1", "1-2-3-4-5", "11111111111141118111111111111111",
            "{11111111-1111-4111-8111-111111111111}", "urn:uuid:11111111-1111-4111-8111-111111111111",
            "11111111-1111-4111-8111-11111111111", "11111111-1111-4111-8111-1111111111111",
            "zzzzzzzz-1111-4111-8111-111111111111", "11111111-1111-4111-8111-111111111111 ",
            "11111111-1111-4111-8111-111111111111\u0000",
        )) {
            assertFalse("accepted '$bad'", TagRules.isId(bad))
        }
    }
}
