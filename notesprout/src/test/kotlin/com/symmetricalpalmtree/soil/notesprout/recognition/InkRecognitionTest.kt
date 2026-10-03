package com.symmetricalpalmtree.soil.notesprout.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

/** What recognised text becomes: one line for a heading or a tag, kept lines for a text object. */
class InkRecognitionTest {

    @Test
    fun `one line collapses every run of whitespace and trims`() {
        assertEquals("Reading list", InkRecognition.oneLine("  Reading \n  list \t"))
        assertEquals("", InkRecognition.oneLine(" \n "))
    }

    @Test
    fun `lines are kept, horizontal runs collapsed, blank runs reduced to one, the ends bare`() {
        assertEquals("a b\nc\n\nd", InkRecognition.normalizeLines("\n\n a   b \nc\n\n\n d \n\n"))
        assertEquals("", InkRecognition.normalizeLines("\n \n"))
        assertEquals("x", InkRecognition.normalizeLines("x"))
    }
}
