package com.symmetricalpalmtree.soil.crypto

import com.symmetricalpalmtree.soil.crypto.PassphraseRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** Decision 13: ≥ 8 characters after trim, confirm equal, not the current one — and nothing else. */
class PassphraseRulesTest {

    @Test
    fun ok() {
        assertEquals(Verdict.OK, PassphraseRules.check("12345678", "12345678"))
        assertEquals(Verdict.OK, PassphraseRules.check("  eight ch  ", "eight ch", current = "something else"))
        // No character-class rule: all-lowercase, all-digits, spaces inside — all fine.
        assertEquals(Verdict.OK, PassphraseRules.check("a b c d e", "a b c d e"))
        assertEquals(Verdict.OK, PassphraseRules.check("00000000", "00000000"))
    }

    @Test
    fun tooShort_afterTrim_beforeMismatch() {
        assertEquals(Verdict.TOO_SHORT, PassphraseRules.check("1234567", "1234567"))
        assertEquals(Verdict.TOO_SHORT, PassphraseRules.check("   1234567   ", "1234567"))
        assertEquals(Verdict.TOO_SHORT, PassphraseRules.check("", ""))
        assertEquals(Verdict.TOO_SHORT, PassphraseRules.check("short", "different"))
    }

    @Test
    fun mismatch() {
        assertEquals(Verdict.MISMATCH, PassphraseRules.check("12345678", "12345679"))
        assertEquals(Verdict.MISMATCH, PassphraseRules.check("12345678", ""))
    }

    @Test
    fun sameAsCurrent_trimmedBothSides() {
        assertEquals(Verdict.SAME_AS_CURRENT, PassphraseRules.check("12345678", "12345678", current = "12345678"))
        assertEquals(Verdict.SAME_AS_CURRENT, PassphraseRules.check(" 12345678 ", "12345678", current = "12345678 "))
        assertEquals(Verdict.OK, PassphraseRules.check("12345678", "12345678", current = null))
    }

    @Test
    fun normalizeIsTrim() {
        assertEquals("abcdefgh", PassphraseRules.normalize("  abcdefgh\n"))
    }
}
