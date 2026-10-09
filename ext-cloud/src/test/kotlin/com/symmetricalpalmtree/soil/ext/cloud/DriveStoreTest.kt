package com.symmetricalpalmtree.soil.ext.cloud

import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** [DriveStore] over [FakeDriveStore] (arc 25 / V1). A real Binder/SQLCipher/ashmem cannot run on
 *  the JVM — this exercises the statement shapes and the store's own contract only. */
class DriveStoreTest {

    @Test
    fun value_isNullBeforeAnyPut() {
        val store = DriveStore(FakeDriveStore())
        assertNull(store.value(DriveSql.Keys.REFRESH_TOKEN))
    }

    @Test
    fun put_thenValue_roundTrips() {
        val store = DriveStore(FakeDriveStore())
        store.put(DriveSql.Keys.ACCOUNT_LABEL, "person@example.com")
        assertEquals("person@example.com", store.value(DriveSql.Keys.ACCOUNT_LABEL))
    }

    @Test
    fun put_overwritesAnExistingValue() {
        val store = DriveStore(FakeDriveStore())
        store.put(DriveSql.Keys.ROOT_FOLDER_ID, "first")
        store.put(DriveSql.Keys.ROOT_FOLDER_ID, "second")
        assertEquals("second", store.value(DriveSql.Keys.ROOT_FOLDER_ID))
    }

    @Test
    fun putAll_writesEveryValueInOneBatch() {
        var batches = 0
        val rows = FakeDriveStore()
        val counting = object : RowStore by rows {
            override fun exec(statements: List<Statement>): LongArray {
                batches++
                return rows.exec(statements)
            }
        }
        val store = DriveStore(counting)
        store.putAll(listOf(DriveSql.Keys.REFRESH_TOKEN to "REFRESH", DriveSql.Keys.ACCOUNT_LABEL to "person@example.com"))
        assertEquals(1, batches)
        assertEquals("REFRESH", store.value(DriveSql.Keys.REFRESH_TOKEN))
        assertEquals("person@example.com", store.value(DriveSql.Keys.ACCOUNT_LABEL))
    }

    @Test
    fun remove_clearsOneKeyOnly() {
        val store = DriveStore(FakeDriveStore())
        store.put(DriveSql.Keys.REFRESH_TOKEN, "token")
        store.put(DriveSql.Keys.ACCOUNT_LABEL, "person@example.com")
        store.remove(DriveSql.Keys.REFRESH_TOKEN)
        assertNull(store.value(DriveSql.Keys.REFRESH_TOKEN))
        assertEquals("person@example.com", store.value(DriveSql.Keys.ACCOUNT_LABEL))
    }

    @Test
    fun clear_removesEveryKey() {
        val store = DriveStore(FakeDriveStore())
        store.put(DriveSql.Keys.REFRESH_TOKEN, "token")
        store.put(DriveSql.Keys.ACCOUNT_LABEL, "person@example.com")
        store.put(DriveSql.Keys.ROOT_FOLDER_ID, "root")
        store.clear()
        assertNull(store.value(DriveSql.Keys.REFRESH_TOKEN))
        assertNull(store.value(DriveSql.Keys.ACCOUNT_LABEL))
        assertNull(store.value(DriveSql.Keys.ROOT_FOLDER_ID))
    }

    @Test
    fun aFailingStore_becomesStoreUnavailable() {
        val fake = FakeDriveStore()
        fake.failWith = { RuntimeException("boom") }
        val store = DriveStore(fake)
        try {
            store.value(DriveSql.Keys.REFRESH_TOKEN)
            fail("expected StoreUnavailable")
        } catch (expected: StoreUnavailable) {
        }
    }
}
