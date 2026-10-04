package com.symmetricalpalmtree.soil.docsprout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaretMemoryTest {

    @Test
    fun `what is stored reads back`() {
        val known = CaretMemory.put(CaretMemory.put(emptyMap(), "a", 12, 100L), "b", 0, 200L)
        assertEquals(known, CaretMemory.decode(CaretMemory.encode(known)))
    }

    @Test
    fun `a document remembered again keeps one place, the newest`() {
        val known = CaretMemory.put(CaretMemory.put(emptyMap(), "a", 12, 100L), "a", 40, 200L)
        assertEquals(1, known.size)
        assertEquals(CaretMemory.Entry(40, 200L), known["a"])
    }

    @Test
    fun `past a hundred the document longest untouched is forgotten`() {
        var known = emptyMap<String, CaretMemory.Entry>()
        for (i in 0..CaretMemory.MAX) known = CaretMemory.put(known, "doc$i", i, i.toLong())
        assertEquals(CaretMemory.MAX, known.size)
        assertFalse(known.containsKey("doc0"))
        assertTrue(known.containsKey("doc1"))
        assertTrue(known.containsKey("doc${CaretMemory.MAX}"))
    }

    @Test
    fun `a stored line that does not read is dropped and the rest still read`() {
        val known = CaretMemory.decode("a 5 10\nnonsense\nb x 3\nc -4 9\nd 7 11")
        assertEquals(setOf("a", "d"), known.keys)
        assertNull(known["b"])
    }

    @Test
    fun `nothing stored is nothing known`() {
        assertTrue(CaretMemory.decode(null).isEmpty())
        assertTrue(CaretMemory.decode("").isEmpty())
    }

    @Test
    fun `a size this build does not offer reads as the default`() {
        assertEquals(TextSizes.DEFAULT, TextSizes.orDefault(17f))
        assertEquals(21f, TextSizes.orDefault(21f))
    }
}
