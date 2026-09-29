package com.symmetricalpalmtree.soil.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenAppsTest {

    private fun app(label: String, pkg: String, cls: String = "$pkg.Main") = AppEntry(label, pkg, cls, icon = null)

    private val notes = app("Notes", "a.notes")
    private val probe = app("EBC probe", "a.probe")
    private val reader = app("Reader", "a.reader", "a.reader.Pdf")
    private val all = listOf(probe, notes, reader)

    @Test
    fun anAppIsRememberedByTheActivityThatOpensIt() {
        assertEquals("a.reader/a.reader.Pdf", HiddenApps.keyOf(reader))
    }

    @Test
    fun withNothingHiddenEveryAppShows() {
        assertEquals(all, HiddenApps.visible(all, emptySet()))
        assertEquals(emptyList<AppEntry>(), HiddenApps.hiddenOf(all, emptySet()))
    }

    @Test
    fun aHiddenAppLeavesTheList_andTheRestKeepTheirOrder() {
        val hidden = setOf(HiddenApps.keyOf(probe))
        assertEquals(listOf(notes, reader), HiddenApps.visible(all, hidden))
        assertEquals(listOf(probe), HiddenApps.hiddenOf(all, hidden))
    }

    /** One entry of an app with two is hidden on its own. */
    @Test
    fun hidingIsPerEntry_notPerPackage() {
        val epub = app("Reader", "a.reader", "a.reader.Epub")
        val hidden = setOf(HiddenApps.keyOf(reader))
        assertEquals(listOf(epub), HiddenApps.visible(listOf(reader, epub), hidden))
    }

    @Test
    fun aHiddenAppThatIsNotInstalledIsSimplyNotThere() {
        val hidden = setOf("gone.app/gone.app.Main", HiddenApps.keyOf(notes))
        assertEquals(listOf(probe, reader), HiddenApps.visible(all, hidden))
        assertEquals(listOf(notes), HiddenApps.hiddenOf(all, hidden))
    }
}
