package com.symmetricalpalmtree.soil.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class AppListTest {

    private fun app(label: String, pkg: String, cls: String = "$pkg.Main") = AppEntry(label, pkg, cls, icon = null)

    private val self = "com.symmetricalpalmtree.soil"

    @Test
    fun appsAreInOrderOfName_ignoringCase() {
        val arranged = AppList.arrange(
            listOf(app("settings", "a.settings"), app("Atelier", "a.atelier"), app("Notes", "a.notes"), app("calendar", "a.cal")),
            self,
        )
        assertEquals(listOf("Atelier", "calendar", "Notes", "settings"), arranged.map { it.label })
    }

    @Test
    fun soilItselfIsLeftOut() {
        val arranged = AppList.arrange(listOf(app("Soil", self), app("Notes", "a.notes")), self)
        assertEquals(listOf("Notes"), arranged.map { it.label })
    }

    @Test
    fun anAppWithNoNameIsCalledByItsPackage() {
        val arranged = AppList.arrange(listOf(app("  ", "a.nameless")), self)
        assertEquals(listOf("a.nameless"), arranged.map { it.label })
    }

    @Test
    fun anAppWithTwoEntriesHasTwo_andTheSameEntryTwiceHasOne() {
        val arranged = AppList.arrange(
            listOf(app("Reader", "a.reader", "a.reader.Pdf"), app("Reader", "a.reader", "a.reader.Epub"), app("Reader", "a.reader", "a.reader.Pdf")),
            self,
        )
        assertEquals(listOf("a.reader.Epub", "a.reader.Pdf"), arranged.map { it.className })
    }

    @Test
    fun twoAppsOfOneNameKeepASteadyOrder() {
        val a = app("Notes", "b.notes")
        val b = app("Notes", "a.notes")
        assertEquals(listOf("a.notes", "b.notes"), AppList.arrange(listOf(a, b), self).map { it.packageName })
        assertEquals(listOf("a.notes", "b.notes"), AppList.arrange(listOf(b, a), self).map { it.packageName })
    }
}
