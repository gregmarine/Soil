package com.symmetricalpalmtree.soil.ext.pdf

import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.ext.PageBundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

class PdfTest {

    @Test fun `the descriptor offers the paper toggle and the password toggle over a pages bundle`() {
        val info = PdfDescriptor.info()
        assertEquals("pdf", info.fileExtension)
        assertEquals(ExportContract.SOURCE_PAGES, info.sourceKind)
        assertEquals(PageBundle.VERSION, info.bundleVersion)
        assertEquals(listOf(ExportContract.OPTION_PAGE_TEMPLATE, ExportContract.OPTION_PROTECT), info.options.map { it.id })
    }

    @Test fun `the spec wants a secret exactly when protect is armed`() {
        PdfExportSpec.require(mapOf("protect" to "1", "template" to "0"), "pw")
        PdfExportSpec.require(mapOf("template" to "1"), null)
        assertThrows(IllegalArgumentException::class.java) { PdfExportSpec.require(mapOf("protect" to "1"), null) }
        assertThrows(IllegalArgumentException::class.java) { PdfExportSpec.require(emptyMap(), "pw") }
        assertThrows(IllegalArgumentException::class.java) { PdfExportSpec.require(mapOf("quality" to "best"), null) }
    }

    @Test fun `links flip to a bottom-left origin and zero-based pages`() {
        val links = listOf(PageBundle.Link(1, 100f, 200f, 172f, 272f, 3), PageBundle.Link(3, 0f, 940f, 800f, 1000f, 1))
        val out = PdfLinks.annotations(links, listOf(1872, 1872, 1000))
        assertEquals(PdfLinks.Annotation(0, 100f, 1872f - 272f, 172f, 1872f - 200f, 2), out[0])
        assertEquals(PdfLinks.Annotation(2, 0f, 0f, 800f, 60f, 0), out[1])
        assertTrue(PdfLinks.annotations(emptyList(), listOf(10)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PdfLinks.annotations(listOf(PageBundle.Link(1, 0f, 0f, 1f, 1f, 3)), listOf(10, 10)) }
    }

    @Test fun `the gray plane round-trips through Flate and the Up predictor`() {
        val w = 5; val h = 3
        val rows = byteArrayOf(0, 10, 20, 30, 40, 1, 11, 21, 31, 41, 2, 12, 22, 32, 42)
        val encoded = GrayFlate.encode(w, h, rows)
        val raw = InflaterInputStream(ByteArrayInputStream(encoded)).readBytes()
        assertEquals((w + 1) * h, raw.size)
        // Undo the Up filter row by row.
        val back = ByteArray(w * h)
        for (y in 0 until h) {
            assertEquals(2, raw[y * (w + 1)].toInt())
            for (x in 0 until w) {
                val d = raw[y * (w + 1) + 1 + x]
                back[y * w + x] = if (y == 0) d else (back[(y - 1) * w + x] + d).toByte()
            }
        }
        assertArrayEquals(rows, back)
    }

    @Test fun `a plane of the wrong size is refused and a white page is tiny`() {
        assertThrows(IllegalArgumentException::class.java) { GrayFlate.encode(2, 2, ByteArray(3)) }
        val white = ByteArray(1404 * 1872) { 0xFF.toByte() }
        val size = GrayFlate.encode(1404, 1872, white).size
        assertTrue("white page encoded to $size bytes", size < 8192)
    }

    @Test fun `the counting stream counts`() {
        val sink = java.io.ByteArrayOutputStream()
        val c = CountingOutputStream(sink)
        c.write(1); c.write(byteArrayOf(2, 3, 4), 0, 3)
        assertEquals(4L, c.count)
        assertEquals(4, sink.size())
    }
}
