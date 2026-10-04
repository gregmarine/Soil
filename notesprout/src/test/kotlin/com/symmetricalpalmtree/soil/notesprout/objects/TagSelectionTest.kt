package com.symmetricalpalmtree.soil.notesprout.objects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lasso's Tag: which selections get it, and which flow each takes. */
class TagSelectionTest {

    @Test
    fun `a lone heading is silent, ink alone is recognised, everything else has no flow`() {
        assertEquals(TagFlow.SILENT, TagSelection.flowFor(SelectionMode.HEADING))
        assertEquals(TagFlow.RECOGNIZE, TagSelection.flowFor(SelectionMode.STROKES))
        for (mode in listOf(SelectionMode.TEXT, SelectionMode.STICKY, SelectionMode.LINK, SelectionMode.MIXED, SelectionMode.MIXED_WITH_LINK)) {
            assertEquals(mode.name, TagFlow.NONE, TagSelection.flowFor(mode))
        }
    }

    @Test
    fun `the heading is always offered, ink only once recognition is here`() {
        assertTrue(TagSelection.offered(SelectionMode.HEADING, recognitionAvailable = false))
        assertFalse(TagSelection.offered(SelectionMode.STROKES, recognitionAvailable = false))
        assertTrue(TagSelection.offered(SelectionMode.STROKES, recognitionAvailable = true))
        assertFalse(TagSelection.offered(SelectionMode.MIXED, recognitionAvailable = true))
        assertFalse(TagSelection.offered(SelectionMode.LINK, recognitionAvailable = true))
    }
}
