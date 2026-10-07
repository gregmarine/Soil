package com.symmetricalpalmtree.soil.sketchsprout.raster

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterRowsTest {

    /** A minimal `VP8L` container of [width] × [height]. */
    private fun webp(width: Int, height: Int): ByteArray {
        val packed = ((width - 1) and 0x3FFF) or (((height - 1) and 0x3FFF) shl 14) or (1 shl 28)
        val out = ArrayList<Byte>()
        out += "RIFF".map { it.code.toByte() }
        out += listOf(0, 0, 0, 0).map { it.toByte() }
        out += "WEBP".map { it.code.toByte() }
        out += "VP8L".map { it.code.toByte() }
        out += listOf(5, 0, 0, 0).map { it.toByte() }
        out += 0x2F.toByte()
        out += listOf(packed, packed ushr 8, packed ushr 16, packed ushr 24).map { it.toByte() }
        return out.toByteArray()
    }

    @Test
    fun `each layer has its own row type and the two map back`() {
        assertEquals(SketchbookSchema.TYPE_SKETCH_GRAPHITE, RasterRows.typeFor(RasterLayer.GRAPHITE))
        assertEquals(SketchbookSchema.TYPE_SKETCH_INK, RasterRows.typeFor(RasterLayer.INK))
        assertEquals(RasterLayer.GRAPHITE, RasterRows.layerOf("sketch_graphite"))
        assertEquals(RasterLayer.INK, RasterRows.layerOf("sketch_ink"))
        assertNull("SN's dead arc-43 row name is nobody's", RasterRows.layerOf("sketch"))
        assertNull(RasterRows.layerOf("stroke"))
    }

    @Test
    fun `graphite is walked first`() {
        assertEquals(listOf(RasterLayer.GRAPHITE, RasterLayer.INK), RasterRows.LAYERS)
    }

    @Test
    fun `a picture fits its page exactly, in both directions`() {
        assertTrue(RasterRows.fitsPage(webp(1404, 1872), 1404, 1872))
        assertFalse(RasterRows.fitsPage(webp(1404, 1872), 1404, 1873))
        assertFalse(RasterRows.fitsPage(webp(1403, 1872), 1404, 1872))
        assertFalse("a page with no size has nothing to check against", RasterRows.fitsPage(webp(1, 1), 0, 0))
        assertFalse(RasterRows.fitsPage(ByteArray(0), 1404, 1872))
    }

    @Test
    fun `the cap is the seam's and the watch line sits under it`() {
        assertEquals(SeamLimits.MAX_VALUE_BYTES, RasterRows.MAX_BYTES)
        assertTrue(RasterRows.WATCH_BYTES < RasterRows.MAX_BYTES)
    }

    @Test
    fun `a layer's name is a word`() {
        assertEquals("graphite", RasterRows.name(RasterLayer.GRAPHITE))
        assertEquals("ink", RasterRows.name(RasterLayer.INK))
    }
}
