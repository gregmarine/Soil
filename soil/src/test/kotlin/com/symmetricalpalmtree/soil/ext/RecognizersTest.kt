package com.symmetricalpalmtree.soil.ext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which recogniser Soil relays to: the setting among what is installed, the lone one by default. */
class RecognizersTest {

    private fun r(pkg: String, vararg languages: String) = Recognizer(pkg, "$pkg.Service", pkg, languages.toList())

    @Test
    fun `the lone installed recogniser is used by default, in its first language`() {
        val only = r("a", "en-US", "de-DE")
        val c = Recognizers.choose(listOf(only), null, null)!!
        assertEquals(only, c.recognizer)
        assertEquals("en-US", c.languageTag)
    }

    @Test
    fun `two installed and no choice is no recogniser, and None stays none`() {
        assertNull(Recognizers.choose(listOf(r("a", "en-US"), r("b", "en-US")), null, null))
        assertNull(Recognizers.choose(listOf(r("a", "en-US")), Recognizers.NONE, null))
        assertNull(Recognizers.choose(emptyList(), null, null))
    }

    @Test
    fun `the chosen one wins with its chosen language, or its first when that language went`() {
        val a = r("a", "en-US", "de-DE")
        val b = r("b", "fr-FR")
        assertEquals("de-DE", Recognizers.choose(listOf(a, b), a.key, "de-DE")!!.languageTag)
        assertEquals("en-US", Recognizers.choose(listOf(a, b), a.key, "it-IT")!!.languageTag)
        assertEquals(b, Recognizers.choose(listOf(a, b), b.key, null)!!.recognizer)
    }

    @Test
    fun `a chosen recogniser that is gone falls back to the lone one, or none`() {
        val a = r("a", "en-US")
        assertEquals(a, Recognizers.choose(listOf(a), "gone", null)!!.recognizer)
        assertNull(Recognizers.choose(listOf(a, r("b", "en-US")), "gone", null))
    }
}
