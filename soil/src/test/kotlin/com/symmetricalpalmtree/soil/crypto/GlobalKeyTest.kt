package com.symmetricalpalmtree.soil.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalKeyTest {

    /** The Notesprout family's vectors, under Soil's prefix: the groups are byte-for-byte theirs. */
    @Test
    fun format_matchesFamilyVectors() {
        assertEquals(
            "SOIL-000G-40R4-0M30-E209-185G-R38E-1W81-24GK",
            GlobalKey.format(ByteArray(20) { it.toByte() }),
        )
        assertEquals(
            "SOIL-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ",
            GlobalKey.format(ByteArray(20) { 0xFF.toByte() }),
        )
    }

    @Test
    fun format_rejectsWrongLength() {
        assertTrue(runCatching { GlobalKey.format(ByteArray(19)) }.isFailure)
        assertTrue(runCatching { GlobalKey.format(ByteArray(21)) }.isFailure)
    }

    @Test
    fun mint_shape() {
        val key = GlobalKey.mint()
        assertTrue(key.startsWith(GlobalKey.PREFIX))
        val groups = key.removePrefix(GlobalKey.PREFIX).split("-")
        assertEquals(8, groups.size)
        assertTrue(groups.all { g -> g.length == 4 && g.all { it in GlobalKey.ALPHABET } })
        assertNotEquals(key, GlobalKey.mint())
    }

    @Test
    fun normalize_foldsConfusables_upperCases() {
        assertEquals("SOIL-0011", GlobalKey.normalize("soil-OoIl"))
        assertEquals("SOIL-ABCD", GlobalKey.normalize("soil-abcd"))
    }

    /** The prefix is spelled with the letters the fold rewrites — it must survive the fold. */
    @Test
    fun normalize_keepsThePrefix_howeverItWasTyped() {
        for (typed in listOf("SOIL-", "soil-", "S0IL-", "SO1L-", "S011-", "s0il-", "SOlL-")) {
            assertEquals("SOIL-7K4M", GlobalKey.normalize(typed + "7k4m"))
        }
    }

    @Test
    fun normalize_foldsWholeWhatIsNotAKey() {
        assertEquals("H0110 W0R1D", GlobalKey.normalize("hollo world"))
        // The prefix is matched at the start only.
        assertEquals("X-S011-", GlobalKey.normalize("x-soil-"))
    }

    @Test
    fun normalize_isIdentityOnValidKeys() {
        repeat(50) {
            val key = GlobalKey.mint()
            assertEquals(key, GlobalKey.normalize(key))
        }
    }
}
