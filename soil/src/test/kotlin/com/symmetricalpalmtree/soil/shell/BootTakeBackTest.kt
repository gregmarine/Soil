package com.symmetricalpalmtree.soil.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootTakeBackTest {

    private val notes = BootTakeBack.FIRMWARE_NOTES

    @Test
    fun notesArrivingUnaskedSoonAfterBootIsThePush() {
        assertTrue(BootTakeBack.isPush(notes, personAsked = false, sinceBootMs = 15_000))
    }

    @Test
    fun notesThePersonOpenedIsTheirs() {
        assertFalse(BootTakeBack.isPush(notes, personAsked = true, sinceBootMs = 15_000))
    }

    @Test
    fun afterTheWindowNotesIsLeftAlone() {
        assertFalse(BootTakeBack.isPush(notes, personAsked = false, sinceBootMs = BootTakeBack.WINDOW_MS))
        assertFalse(BootTakeBack.isPush(notes, personAsked = false, sinceBootMs = 3_600_000))
    }

    @Test
    fun noOtherAppIsEverTakenBackFrom() {
        assertFalse(BootTakeBack.isPush("com.ratta.supernote.document", personAsked = false, sinceBootMs = 15_000))
        assertFalse(BootTakeBack.isPush("com.symmetricalpalmtree.soil", personAsked = false, sinceBootMs = 15_000))
    }
}
