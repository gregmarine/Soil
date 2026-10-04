package com.symmetricalpalmtree.soil.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SchemeBuilderTest {

    @Test
    fun `the builder's text is the engine's own, part for part`() {
        val parts = listOf(
            SchemeEngine.Part.Literal("Journal_"), SchemeEngine.Part.Date, SchemeEngine.Part.Literal("-"),
            SchemeEngine.Part.MonthName, SchemeEngine.Part.Wd, SchemeEngine.Part.Counter(3),
        )
        val text = SchemeBuilderDialog.encode(parts)
        assertEquals("Journal_{date}-{monthname}{wd}{n:3}", text)
        assertEquals(parts, SchemeEngine.parse(text))
        assertEquals("{n}", SchemeBuilderDialog.encode(listOf(SchemeEngine.Part.Counter(1))))
    }
}
