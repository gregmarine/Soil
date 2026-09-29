package com.symmetricalpalmtree.soil.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class RekeyExportTest {

    @Test
    fun aLiteralIsSingleQuoted() {
        assertEquals("'abc'", RekeyExport.sqlLiteral("abc"))
    }

    @Test
    fun quotesAreDoubled_soAPassphraseCannotEndTheLiteral() {
        assertEquals("'it''s'", RekeyExport.sqlLiteral("it's"))
        assertEquals("'''; DROP TABLE item; --'", RekeyExport.sqlLiteral("'; DROP TABLE item; --"))
    }

    @Test
    fun anEmptyStringIsTheEmptyLiteral() {
        assertEquals("''", RekeyExport.sqlLiteral(""))
    }

    @Test
    fun aRawKeyTravelsAsText() {
        // `x'…'` inside a string literal, never a bare blob literal.
        assertEquals("'x''00ff'''", RekeyExport.sqlLiteral("x'00ff'"))
    }
}
