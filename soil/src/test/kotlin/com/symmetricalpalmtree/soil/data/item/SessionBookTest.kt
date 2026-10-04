package com.symmetricalpalmtree.soil.data.item

import com.symmetricalpalmtree.soil.data.item.SessionBook.Act
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBookTest {

    private val book = SessionBook()

    @Test
    fun `the first holder opens the file and the last closes it`() {
        assertEquals(Act.OPEN, book.join("a", 1))
        assertEquals(listOf("a"), book.openItems())
        assertEquals(Act.CLOSE, book.leave("a", 1, tidy = false))
        assertTrue(book.openItems().isEmpty())
        assertFalse(book.isHeld("a"))
    }

    @Test
    fun `a tidy close purges`() {
        book.join("a", 1)
        assertEquals(Act.CLOSE_TIDY, book.leave("a", 1, tidy = true))
    }

    @Test
    fun `a second holder shares the one connection`() {
        assertEquals(Act.OPEN, book.join("a", 1))
        assertEquals(Act.NONE, book.join("a", 2))
        assertEquals(Act.NONE, book.leave("a", 1, tidy = false))
        assertEquals(listOf("a"), book.openItems())
        assertEquals(Act.CLOSE, book.leave("a", 2, tidy = false))
    }

    @Test
    fun `parking gives up the file and keeps the session`() {
        book.join("a", 1)
        assertEquals(Act.RELEASE, book.park("a", 1))
        assertTrue(book.openItems().isEmpty())
        assertTrue(book.isHeld("a"))
        assertTrue(book.isParked("a", 1))
        assertEquals(Act.OPEN, book.resume("a", 1))
        assertFalse(book.isParked("a", 1))
    }

    @Test
    fun `parking twice and resuming twice do nothing the second time`() {
        book.join("a", 1)
        book.park("a", 1)
        assertEquals(Act.NONE, book.park("a", 1))
        book.resume("a", 1)
        assertEquals(Act.NONE, book.resume("a", 1))
    }

    @Test
    fun `the file stays open while any holder is not parked`() {
        book.join("a", 1)
        book.join("a", 2)
        assertEquals(Act.NONE, book.park("a", 1))
        assertEquals(Act.RELEASE, book.park("a", 2))
        assertEquals(Act.OPEN, book.resume("a", 1))
    }

    @Test
    fun `the live holder leaving a parked one behind releases the file`() {
        book.join("a", 1)
        book.join("a", 2)
        book.park("a", 1)
        assertEquals(Act.RELEASE, book.leave("a", 2, tidy = false))
        assertTrue(book.isHeld("a"))
    }

    @Test
    fun `nothing is purged while another holds the item, and the purge is owed`() {
        book.join("a", 1)
        book.join("a", 2)
        assertEquals(Act.NONE, book.leave("a", 1, tidy = true))
        assertEquals(Act.CLOSE_TIDY, book.leave("a", 2, tidy = false))
    }

    @Test
    fun `a parked holder that dies has its file tidied cold`() {
        book.join("a", 1)
        book.park("a", 1)
        assertEquals(Act.TIDY_COLD, book.leave("a", 1, tidy = true))
        assertFalse(book.isHeld("a"))
    }

    @Test
    fun `a parked holder that leaves without a tidy touches nothing`() {
        book.join("a", 1)
        book.park("a", 1)
        assertEquals(Act.NONE, book.leave("a", 1, tidy = false))
    }

    @Test
    fun `a holder that is not one is answered with nothing`() {
        book.join("a", 1)
        assertEquals(Act.NONE, book.park("a", 9))
        assertEquals(Act.NONE, book.resume("b", 1))
        assertEquals(Act.NONE, book.leave("a", 9, tidy = true))
        assertEquals(listOf("a"), book.openItems())
        // Leaving twice is leaving once.
        book.leave("a", 1, tidy = false)
        assertEquals(Act.NONE, book.leave("a", 1, tidy = true))
    }

    @Test
    fun `items are kept apart`() {
        book.join("a", 1)
        book.join("b", 2)
        book.park("a", 1)
        assertEquals(listOf("b"), book.openItems())
        assertEquals(setOf("a" to 1L, "b" to 2L), book.holders().toSet())
    }

    @Test
    fun `an open that failed is undone by leaving`() {
        assertEquals(Act.OPEN, book.join("a", 1))
        // The executor could not open the file: the holder is taken out again.
        book.leave("a", 1, tidy = false)
        assertFalse(book.isHeld("a"))
        assertEquals(Act.OPEN, book.join("a", 2))
    }
}
