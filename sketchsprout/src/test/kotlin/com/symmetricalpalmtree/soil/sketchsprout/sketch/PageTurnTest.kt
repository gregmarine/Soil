package com.symmetricalpalmtree.soil.sketchsprout.sketch

import com.symmetricalpalmtree.soil.sketchsprout.sketch.PageTurn.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnTest {

    @Test
    fun `a turn in the middle lands one page along, an edge has nowhere to go`() {
        assertEquals(3, PageTurn.targetIndex(2, 10, Direction.NEXT))
        assertEquals(1, PageTurn.targetIndex(2, 10, Direction.PREV))
        assertNull(PageTurn.targetIndex(0, 10, Direction.PREV))
        assertNull(PageTurn.targetIndex(9, 10, Direction.NEXT))
        assertFalse(PageTurn.canTurn(0, 1, Direction.PREV))
        assertFalse(PageTurn.canTurn(0, 1, Direction.NEXT))
        assertNull(PageTurn.targetIndex(5, 3, Direction.NEXT))
        assertNull(PageTurn.targetIndex(0, 0, Direction.NEXT))
    }

    @Test
    fun `a replay walks towards the page its entry names, bounded by the distance plus slack`() {
        assertEquals(Direction.PREV, PageTurn.directionTowards(5, 2))
        assertEquals(Direction.NEXT, PageTurn.directionTowards(2, 5))
        assertNull(PageTurn.directionTowards(3, 3))
        assertEquals(4, PageTurn.maxSteps(5, 2))
        assertEquals(1, PageTurn.maxSteps(3, 3))
    }

    @Test
    fun `an insert pushes the pages at and after it one along`() {
        assertEquals(0, PageTurn.reindexAfterInsert(0, 2))
        assertEquals(1, PageTurn.reindexAfterInsert(1, 2))
        assertEquals(3, PageTurn.reindexAfterInsert(2, 2))
        assertEquals(4, PageTurn.reindexAfterInsert(3, 2))
        assertEquals(1, PageTurn.reindexAfterInsert(0, 0))
        assertEquals(4, PageTurn.reindexAfterInsert(4, 5))
    }

    @Test
    fun `a delete pulls the pages after it back one and leaves the deleted index alone`() {
        assertEquals(0, PageTurn.reindexAfterDelete(0, 2))
        assertEquals(1, PageTurn.reindexAfterDelete(1, 2))
        assertEquals(2, PageTurn.reindexAfterDelete(3, 2))
        assertEquals(2, PageTurn.reindexAfterDelete(2, 2))   // the drop is by key, at the call site
    }

    @Test
    fun `an insert then a delete of the same position is the identity`() {
        for (at in 0..4) for (index in 0..5) {
            assertEquals(index, PageTurn.reindexAfterDelete(PageTurn.reindexAfterInsert(index, at), at))
        }
    }

    @Test
    fun `the engine's rule for the edge is to stay put`() {
        assertTrue(PageTurn.canTurn(1, 3, Direction.PREV))
    }
}
