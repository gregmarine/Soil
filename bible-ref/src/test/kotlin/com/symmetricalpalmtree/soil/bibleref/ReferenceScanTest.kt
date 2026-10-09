package com.symmetricalpalmtree.soil.bibleref

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** References found in prose: what a document's pass links, and what it leaves alone. */
class ReferenceScanTest {

    private fun words(text: String) = ReferenceScan.scan(text).map { text.substring(it.start, it.end) }
    private fun wires(text: String) = ReferenceScan.scan(text).map { it.wire }

    @Test
    fun `a reference in a sentence, with its wire and its bounds`() {
        val text = "see Romans 8:28 today"
        val hit = ReferenceScan.scan(text).single()
        assertEquals("Romans 8:28", text.substring(hit.start, hit.end))
        assertEquals("ROM:8:28-8:28", hit.wire)
    }

    @Test
    fun `every form the parser reads`() {
        assertEquals(listOf("JHN:3:16-3:16"), wires("John 3:16"))
        assertEquals(listOf("JHN:3:14-3:18"), wires("John 3:14-18."))
        assertEquals(listOf("JHN:3:14-3:18"), wires("John 3:14–18"))
        assertEquals(listOf("JHN:3:14-3:16,JHN:3:18-3:18"), wires("John 3:14-16, 18 is it"))
        assertEquals(listOf("GEN:1:5-2:3"), wires("Genesis 1:5-2:3"))
        assertEquals(listOf("GEN:1:0-1:999"), wires("Genesis 1"))
        assertEquals(listOf("ACT:2:0-3:999"), wires("Acts 2–3"))
        assertEquals(listOf("1CO:13:4-13:7"), wires("1 Cor 13:4-7"))
        assertEquals(listOf("1CO:13:4-13:7"), wires("1 Cor. 13:4-7"))
        assertEquals(listOf("1CO:13:4-13:7"), wires("I Corinthians 13:4-7"))
        assertEquals(listOf("1CO:13:4-13:7"), wires("1Cor 13:4-7"))
        assertEquals(listOf("PSA:23:1-23:1,PSA:23:4-23:4"), wires("Ps. 23:1, 4"))
        assertEquals(listOf("PSA:23:0-23:999"), wires("Psalm 23"))
        assertEquals(listOf("SNG:2:1-2:1"), wires("Song of Solomon 2:1"))
        assertEquals(listOf("JHN:3:16-3:16"), wires("JHN 3:16"))
        assertEquals(listOf("JUD:1:24-1:24"), wires("Jude 24"))
    }

    @Test
    fun `two references are two hits`() {
        assertEquals(listOf("John 3:16", "Acts 1:3"), words("Read John 3:16; Acts 1:3 tonight."))
        assertEquals(listOf("John 3:16", "Proverbs 3:5-6"), words("John 3:16, Proverbs 3:5-6"))
        assertEquals(listOf("JHN:3:16-3:16", "PRO:3:5-3:6"), wires("John 3:16, Proverbs 3:5-6"))
    }

    @Test
    fun `a list is cut back to what parses, and a trailing comma is not part of it`() {
        assertEquals(listOf("John 3:16"), words("In John 3:16, 2019 was the year"))
        assertEquals(listOf("John 3:16"), words("John 3:16, and more"))
        assertEquals(listOf("John 3:16"), words("John 3:16,"))
        assertEquals(listOf("John 3:16, 18"), words("John 3:16, 18, 2019"))
    }

    @Test
    fun `what is not a reference is left alone`() {
        assertTrue(ReferenceScan.scan("").isEmpty())
        assertTrue(ReferenceScan.scan("just some words").isEmpty())
        assertTrue(ReferenceScan.scan("at 3:16 pm").isEmpty())
        assertTrue(ReferenceScan.scan("the ratio is 3:1").isEmpty())
        assertTrue(ReferenceScan.scan("I am 3 today").isEmpty())
        assertTrue(ReferenceScan.scan("he 3").isEmpty())
        assertTrue(ReferenceScan.scan("Hezekiah 2:1").isEmpty())
        assertTrue(ReferenceScan.scan("John").isEmpty())
        assertTrue(ReferenceScan.scan("John 0:1").isEmpty())
        assertTrue(ReferenceScan.scan("Genesis 51").isEmpty())
        assertTrue(ReferenceScan.scan("Psalm 151").isEmpty())
        assertTrue(ReferenceScan.scan("John 3:18-14").isEmpty())
        assertTrue(ReferenceScan.scan("xJohn 3:16").isEmpty())
        assertTrue(ReferenceScan.scan("2John 3:16").isEmpty())
    }

    @Test
    fun `a two-letter alias needs its period, a name or a code never does`() {
        assertTrue(ReferenceScan.scan("Is 3:1").isEmpty())
        assertEquals(listOf("ISA:3:1-3:1"), wires("Is. 3:1"))
        assertTrue(ReferenceScan.scan("Ps 23").isEmpty())
        assertEquals(listOf("PSA:23:0-23:999"), wires("Ps. 23"))
        assertEquals(listOf("JOB:3:0-3:999"), wires("Job 3"))
        assertEquals(listOf("MRK:1:0-1:999"), wires("Mark 1"))
        assertEquals(listOf("NUM:5:0-5:999"), wires("Numbers 5 and 6 are prime"))
    }

    @Test
    fun `a book is written with a capital`() {
        assertTrue(ReferenceScan.scan("the numbers 5 and 6").isEmpty())
        assertTrue(ReferenceScan.scan("mark 3").isEmpty())
        assertTrue(ReferenceScan.scan("job 3").isEmpty())
        assertEquals(listOf("MRK:3:0-3:999"), wires("Mark 3"))
    }

    @Test
    fun `hits never overlap and keep their order`() {
        val text = "Genesis 1, Genesis 2 and John 1:1, 14; Jude 3"
        val hits = ReferenceScan.scan(text)
        assertEquals(listOf("Genesis 1", "Genesis 2", "John 1:1, 14", "Jude 3"), hits.map { text.substring(it.start, it.end) })
        for (i in 1 until hits.size) assertTrue(hits[i].start >= hits[i - 1].end)
    }

    @Test
    fun `a reference never runs across a line break`() {
        assertTrue(ReferenceScan.scan("1. Genesis\n2. Exodus").isEmpty())
        assertTrue(ReferenceScan.scan("1. Genesis\r\n2. Exodus").isEmpty())
        assertTrue(ReferenceScan.scan("John\n3:16").isEmpty())
        assertTrue(ReferenceScan.scan("Song of\nSolomon 2:1").isEmpty())
        assertEquals(listOf("John 3:16"), words("John 3:16\n, 18"))
        assertEquals(listOf("John 3:14"), words("John 3:14\n-16"))
        assertEquals(listOf("Genesis 1", "Exodus 2"), words("1. Genesis 1\n2. Exodus 2"))
        assertNull(ReferenceParser.parse("Genesis\n2"))
        assertEquals("GEN:2:0-2:999", ReferenceCodec.encode(listOf(ReferenceParser.parse("Genesis 2")!!)))
    }
}
