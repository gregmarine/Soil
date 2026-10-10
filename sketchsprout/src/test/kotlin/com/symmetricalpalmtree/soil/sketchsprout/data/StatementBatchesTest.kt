package com.symmetricalpalmtree.soil.sketchsprout.data

import com.symmetricalpalmtree.soil.paper.store.Statement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementBatchesTest {

    private fun blob(id: Int, size: Int) = Statement("INSERT INTO t VALUES (?, ?)", "r$id", ByteArray(size))

    @Test
    fun `nothing is no batch, and a few small statements are one`() {
        assertTrue(StatementBatches.split(emptyList()).isEmpty())
        val few = List(3) { Statement("DELETE FROM t WHERE id = ?", "x$it") }
        assertEquals(listOf(few), StatementBatches.split(few))
    }

    @Test
    fun `batches are cut by count and by bytes, in order, and an oversized statement stands alone`() {
        val many = List(25) { Statement("DELETE FROM t WHERE id = ?", "x$it") }
        val byCount = StatementBatches.split(many, maxStatements = 10)
        assertEquals(listOf(10, 10, 5), byCount.map { it.size })
        assertEquals(many, byCount.flatten())

        val rasters = List(5) { blob(it, 1000) }
        val byBytes = StatementBatches.split(rasters, maxBytes = 2_400)
        assertEquals(listOf(2, 2, 1), byBytes.map { it.size })
        assertEquals(rasters, byBytes.flatten())
        byBytes.forEach { b -> assertTrue(b.sumOf { StatementBatches.estimate(it) } <= 2_400) }

        val huge = listOf(blob(0, 5_000), blob(1, 10))
        assertEquals(listOf(1, 1), StatementBatches.split(huge, maxBytes = 2_400).map { it.size })
    }

    @Test
    fun `a full-size raster always fits a batch under the seam's payload`() {
        assertTrue(StatementBatches.MAX_BATCH_BYTES > com.symmetricalpalmtree.soil.seam.SeamLimits.MAX_VALUE_BYTES + 1024)
        assertTrue(StatementBatches.MAX_BATCH_BYTES < com.symmetricalpalmtree.soil.seam.SeamLimits.MAX_PAYLOAD_BYTES)
    }
}
