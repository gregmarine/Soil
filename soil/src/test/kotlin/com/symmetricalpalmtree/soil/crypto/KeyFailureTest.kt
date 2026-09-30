package com.symmetricalpalmtree.soil.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFailureTest {

    @Test fun lockedIsKey() {
        assertTrue(KeyFailure.isKeyFailure(SoilLockedException("no candidate key opens x")))
    }

    @Test fun sqlcipherWrongKeyMessagesAreKey() {
        assertTrue(KeyFailure.isKeyFailure(RuntimeException("file is not a database (code 26 SQLITE_NOTADB)")))
        assertTrue(KeyFailure.isKeyFailure(RuntimeException("File Is Encrypted or is not a database")))
        assertTrue(KeyFailure.isKeyFailure(RuntimeException("database disk image is malformed / corrupt")))
    }

    @Test fun corruptClassByName() {
        assertEquals(
            KeyFailure.Verdict.KEY,
            KeyFailure.classify(isLocked = false, className = "SQLiteDatabaseCorruptException", isIllegalState = false, message = null),
        )
    }

    @Test fun schemaErrorsAreNotKey() {
        assertFalse(KeyFailure.isKeyFailure(IllegalStateException("Pre-packaged database has an invalid schema")))
        assertFalse(KeyFailure.isKeyFailure(IllegalStateException("A migration from 1 to 2 was required but not found")))
        assertFalse(KeyFailure.isKeyFailure(IllegalStateException("Room cannot verify the data integrity — identity hash")))
    }

    @Test fun schemaWinsOverKeyDeeperInTheChain() {
        // The first verdict on the way down decides: Room's schema complaint wraps a SQLite cause.
        val t = IllegalStateException("invalid schema", RuntimeException("file is not a database"))
        assertFalse(KeyFailure.isKeyFailure(t))
    }

    @Test fun keyFoundUnderAnUnknownWrapper() {
        val t = RuntimeException("open failed", RuntimeException("wrapped", SoilLockedException("stale raw key")))
        assertTrue(KeyFailure.isKeyFailure(t))
    }

    @Test fun unknownAndNullAreNotKey() {
        assertFalse(KeyFailure.isKeyFailure(null))
        assertFalse(KeyFailure.isKeyFailure(RuntimeException("Notebook file is missing")))
        assertFalse(KeyFailure.isKeyFailure(IllegalArgumentException("bad id")))
    }

    @Test fun aCycleTerminates() {
        class Loop : RuntimeException("loop") { override val cause: Throwable get() = this }
        assertFalse(KeyFailure.isKeyFailure(Loop()))
    }
}
