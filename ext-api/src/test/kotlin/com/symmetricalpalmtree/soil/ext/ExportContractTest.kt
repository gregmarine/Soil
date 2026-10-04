package com.symmetricalpalmtree.soil.ext

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ExportContractTest {

    private fun info(
        ext: String = "pdf",
        mime: String = "application/pdf",
        options: List<OptionDescriptor> = emptyList(),
        source: Int = ExportContract.SOURCE_PAGES,
        delivery: Int = ExportContract.DELIVERY_ONE_FILE,
    ) = ExporterInfo("PDF", ext, mime, options, source, PageBundle.VERSION, delivery)

    @Test fun `a well-formed descriptor is accepted`() {
        val i = info(options = listOf(OptionDescriptor.toggle("template", "Paper", true)))
        assertEquals("pdf", i.fileExtension)
        assertEquals(1, i.options.size)
    }

    @Test fun `per-page delivery needs the pages source`() {
        assertThrows(IllegalArgumentException::class.java) { info(source = ExportContract.SOURCE_FILE, delivery = ExportContract.DELIVERY_PER_PAGE) }
        info(source = ExportContract.SOURCE_PAGES, delivery = ExportContract.DELIVERY_PER_PAGE)
    }

    @Test fun `an unknown source kind or delivery is refused`() {
        assertThrows(IllegalArgumentException::class.java) { info(source = 7) }
        assertThrows(IllegalArgumentException::class.java) { info(delivery = 7) }
    }

    @Test fun `the extension and the MIME are held to their shapes`() {
        assertThrows(IllegalArgumentException::class.java) { info(ext = "PDF") }
        assertThrows(IllegalArgumentException::class.java) { info(ext = ".pdf") }
        assertThrows(IllegalArgumentException::class.java) { info(ext = "") }
        assertThrows(IllegalArgumentException::class.java) { info(mime = "pdf") }
        assertThrows(IllegalArgumentException::class.java) { info(mime = "/pdf") }
    }

    @Test fun `too many options or duplicate ids are refused`() {
        val many = (1..9).map { OptionDescriptor.toggle("o$it", "Option $it", false) }
        assertThrows(IllegalArgumentException::class.java) { info(options = many) }
        val dup = listOf(OptionDescriptor.toggle("a", "A", false), OptionDescriptor.toggle("a", "A again", true))
        assertThrows(IllegalArgumentException::class.java) { info(options = dup) }
    }

    @Test fun `a single choice needs parallel lists and a declared default`() {
        OptionDescriptor.choice("format", "Format", listOf("png", "jpeg"), listOf("PNG", "JPEG"), "png")
        assertThrows(IllegalArgumentException::class.java) { OptionDescriptor.choice("f", "F", listOf("a"), listOf("A", "B"), "a") }
        assertThrows(IllegalArgumentException::class.java) { OptionDescriptor.choice("f", "F", listOf("a"), listOf("A"), "b") }
        assertThrows(IllegalArgumentException::class.java) { OptionDescriptor.choice("f", "F", emptyList(), emptyList(), "") }
        assertThrows(IllegalArgumentException::class.java) { OptionDescriptor.choice("f", "F", listOf("a", "a"), listOf("A", "A"), "a") }
    }

    @Test fun `a toggle has no choices and a binary default`() {
        assertEquals("1", OptionDescriptor.toggle("t", "T", true).defaultValue)
        assertThrows(IllegalArgumentException::class.java) { OptionDescriptor("t", "T", ExportContract.KIND_TOGGLE, emptyList(), emptyList(), "yes") }
        assertThrows(IllegalArgumentException::class.java) { OptionDescriptor("t", "T", ExportContract.KIND_TOGGLE, listOf("a"), listOf("A"), "1") }
    }

    @Test fun `an option id is bounded and plain`() {
        assertThrows(IllegalArgumentException::class.java) { OptionDescriptor.toggle("has space", "T", true) }
        assertThrows(IllegalArgumentException::class.java) { OptionDescriptor.toggle("x".repeat(33), "T", true) }
        assertThrows(IllegalArgumentException::class.java) { OptionDescriptor.toggle("ok", " ", true) }
    }

    @Test fun `a spec holds bounded values and a display name only`() {
        ExportSpec(mapOf("template" to "1"), "My notebook")
        assertThrows(IllegalArgumentException::class.java) { ExportSpec(mapOf("bad key" to "1"), "n") }
        assertThrows(IllegalArgumentException::class.java) { ExportSpec(emptyMap(), "a/b") }
        assertThrows(IllegalArgumentException::class.java) { ExportSpec(emptyMap(), "n".repeat(201)) }
        assertThrows(IllegalArgumentException::class.java) { ExportSpec(emptyMap(), "n", exportSecret = "") }
        assertThrows(IllegalArgumentException::class.java) { ExportSpec(emptyMap(), "n", exportSecret = "s".repeat(129)) }
        assertEquals("pw", ExportSpec(emptyMap(), "n", "pw").exportSecret)
    }

    @Test fun `results refuse a negative count`() {
        assertThrows(IllegalArgumentException::class.java) { ExportResult(-1) }
        assertThrows(IllegalArgumentException::class.java) { ImportResult(-1) }
        assertEquals(0L, ExportResult(0).bytesWritten)
    }

    @Test fun `an importer declares bounded extensions and MIME types`() {
        ImporterInfo("Soil item", listOf("soil"), listOf("application/octet-stream"))
        assertThrows(IllegalArgumentException::class.java) { ImporterInfo("X", emptyList(), listOf("a/b")) }
        assertThrows(IllegalArgumentException::class.java) { ImporterInfo("X", listOf("soil", "soil"), listOf("a/b")) }
        assertThrows(IllegalArgumentException::class.java) { ImporterInfo("X", listOf("soil"), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { ImporterInfo("X", listOf("Soil"), listOf("a/b")) }
        assertThrows(IllegalArgumentException::class.java) { ImportSpec(emptyMap(), "a/b") }
    }

    // ── The page bundle ──────

    private fun bundle(pages: List<Triple<Int, Int, ByteArray>>, links: List<PageBundle.Link> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        PageBundle.Writer(out, pages.size, links).use { w -> for ((wd, ht, img) in pages) w.writePage(wd, ht, img) }
        return out.toByteArray()
    }

    @Test fun `a bundle round-trips its pages in order`() {
        val bytes = bundle(listOf(Triple(10, 20, byteArrayOf(1, 2, 3)), Triple(30, 40, byteArrayOf(4))))
        PageBundle.Reader(ByteArrayInputStream(bytes)).use { r ->
            assertEquals(PageBundle.VERSION_1, r.version)
            assertEquals(2, r.pageCount)
            val p1 = r.readPage(); assertEquals(10, p1.widthPx); assertEquals(20, p1.heightPx); assertArrayEquals(byteArrayOf(1, 2, 3), p1.image)
            val p2 = r.readPage(); assertEquals(30, p2.widthPx); assertArrayEquals(byteArrayOf(4), p2.image)
            assertTrue(r.readLinks().isEmpty())
        }
    }

    @Test fun `links make a version-2 stream and come back after the pages`() {
        val links = listOf(PageBundle.Link(1, 0f, 0f, 5f, 5f, 2))
        val bytes = bundle(listOf(Triple(10, 10, byteArrayOf(1)), Triple(10, 10, byteArrayOf(2))), links)
        PageBundle.Reader(ByteArrayInputStream(bytes)).use { r ->
            assertEquals(PageBundle.VERSION, r.version)
            assertThrows(IOException::class.java) { r.readLinks() }
            r.readPage(); r.readPage()
            val back = r.readLinks()
            assertEquals(1, back.size); assertEquals(2, back[0].toPage); assertEquals(5f, back[0].r)
        }
    }

    @Test fun `a writer refuses a short close, a link outside the count and an empty image`() {
        val out = ByteArrayOutputStream()
        val w = PageBundle.Writer(out, 2)
        w.writePage(1, 1, byteArrayOf(1))
        assertThrows(IOException::class.java) { w.close() }
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Writer(ByteArrayOutputStream(), 1, listOf(PageBundle.Link(1, 0f, 0f, 1f, 1f, 2))) }
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Writer(ByteArrayOutputStream(), 1).writePage(1, 1, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Writer(ByteArrayOutputStream(), 0) }
    }

    @Test fun `a reader refuses the wrong magic, a bad version and a truncated page`() {
        assertThrows(IOException::class.java) { PageBundle.Reader(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 0, 0, 0, 1, 0, 0, 0, 1))) }
        assertThrows(IOException::class.java) { PageBundle.Reader(ByteArrayInputStream("SO".toByteArray())) }
        val good = bundle(listOf(Triple(10, 10, byteArrayOf(1, 2, 3, 4))))
        val truncated = good.copyOf(good.size - 2)
        PageBundle.Reader(ByteArrayInputStream(truncated)).use { r -> assertThrows(IOException::class.java) { r.readPage() } }
        val badVersion = good.copyOf().also { it[7] = 9 }
        assertThrows(IOException::class.java) { PageBundle.Reader(ByteArrayInputStream(badVersion)) }
    }

    @Test fun `a link rect must be finite and non-empty`() {
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Link(1, 5f, 5f, 5f, 9f, 1) }
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Link(1, Float.NaN, 0f, 1f, 1f, 1) }
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Link(0, 0f, 0f, 1f, 1f, 1) }
    }
}
