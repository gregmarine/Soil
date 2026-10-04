package com.symmetricalpalmtree.soil.library

import com.symmetricalpalmtree.soil.library.ItemApps.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItemAppsTest {

    private val dev = "com.symmetricalpalmtree.soil.dev"
    private val release = "com.symmetricalpalmtree.soil"
    private val notesDev = Candidate("com.symmetricalpalmtree.soil.notesprout.dev", "Open", "notebook", sameKey = true)
    private val notes = Candidate("com.symmetricalpalmtree.soil.notesprout", "Open", "notebook", sameKey = true)

    @Test
    fun `an item opens in the app for its kind`() {
        assertEquals(notesDev, ItemApps.choose(listOf(notesDev), "notebook", dev))
        assertNull(ItemApps.choose(listOf(notesDev), "sketchbook", dev))
        assertNull(ItemApps.choose(emptyList(), "notebook", dev))
    }

    @Test
    fun `a debug Soil opens the debug app and a release Soil the release one`() {
        assertEquals(notesDev, ItemApps.choose(listOf(notes, notesDev), "notebook", dev))
        assertEquals(notes, ItemApps.choose(listOf(notes, notesDev), "notebook", release))
        assertNull(ItemApps.choose(listOf(notes), "notebook", dev))
    }

    @Test
    fun `an app signed with another key is never chosen`() {
        assertNull(ItemApps.choose(listOf(notesDev.copy(sameKey = false)), "notebook", dev))
    }

    @Test
    fun `an app that names no kind is never chosen`() {
        assertNull(ItemApps.choose(listOf(notesDev.copy(kind = null)), "notebook", dev))
    }

    @Test
    fun `of two that may open a kind the answer does not change`() {
        val other = notesDev.copy(packageName = "com.symmetricalpalmtree.soil.another.dev")
        assertEquals(other, ItemApps.choose(listOf(notesDev, other), "notebook", dev))
        assertEquals(other, ItemApps.choose(listOf(other, notesDev), "notebook", dev))
    }
}
