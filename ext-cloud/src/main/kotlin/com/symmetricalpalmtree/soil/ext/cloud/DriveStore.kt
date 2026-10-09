package com.symmetricalpalmtree.soil.ext.cloud

import com.symmetricalpalmtree.soil.paper.store.RowStore

class StoreUnavailable(cause: Throwable) : Exception(cause.message, cause)

/** The account rows: the refresh token, the account's label and the root folder's id. Soil made
 *  the table; every failure of the store is one shape, [StoreUnavailable]. */
class DriveStore(private val rows: RowStore) {

    fun value(key: String): String? = guard { rows.query(DriveSql.selectValue(key)).rows.firstOrNull()?.text("value") }

    fun put(key: String, value: String) = guard { rows.exec(listOf(DriveSql.upsertValue(key, value))); Unit }

    /** Several values in one batch — one transaction in the store Soil lends. */
    fun putAll(values: List<Pair<String, String>>) =
        guard { rows.exec(values.map { (k, v) -> DriveSql.upsertValue(k, v) }); Unit }

    fun remove(key: String) = guard { rows.exec(listOf(DriveSql.deleteValue(key))); Unit }

    fun clear() = guard { rows.exec(listOf(DriveSql.deleteAll())); Unit }

    private inline fun <T> guard(block: () -> T): T =
        try {
            block()
        } catch (e: StoreUnavailable) {
            throw e
        } catch (e: Exception) {
            throw StoreUnavailable(e)
        }
}
