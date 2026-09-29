package com.symmetricalpalmtree.soil.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoilFilesTest {

    @Test
    fun aStoreIsNamedByItsFile() {
        assertEquals("scratchpad", SoilFiles.storeName("scratchpad.db"))
        assertEquals("com.example.app", SoilFiles.storeName("com.example.app.db"))
    }

    @Test
    fun nothingElseInTheGardenIsAStore() {
        assertNull(SoilFiles.storeName("0b7c.soil"))
        assertNull(SoilFiles.storeName("scratchpad.db-wal"))
        assertNull(SoilFiles.storeName("scratchpad.db-shm"))
        assertNull(SoilFiles.storeName("scratchpad.db.rekey.tmp"))
        assertNull(SoilFiles.storeName("scratchpad.db.old.bak"))
        assertNull(SoilFiles.storeName(".db"))
    }

    @Test
    fun aStoreNameCannotLeaveTheGarden() {
        assertTrue(SoilFiles.isValidStoreName("scratchpad"))
        assertFalse(SoilFiles.isValidStoreName(""))
        assertFalse(SoilFiles.isValidStoreName(".."))
        assertFalse(SoilFiles.isValidStoreName("."))
        assertFalse(SoilFiles.isValidStoreName("../soil"))
        assertFalse(SoilFiles.isValidStoreName("a/b"))
        assertFalse(SoilFiles.isValidStoreName("a b"))
    }
}
