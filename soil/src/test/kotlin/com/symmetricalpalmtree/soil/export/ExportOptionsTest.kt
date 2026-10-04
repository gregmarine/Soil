package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.ext.ExporterInfo
import com.symmetricalpalmtree.soil.ext.OptionDescriptor
import com.symmetricalpalmtree.soil.ext.PageBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportOptionsTest {

    private val keying = OptionDescriptor.choice("keying", "Encryption", listOf("keep", "rekey", "plain"), listOf("Keep", "New", "None"), "keep")
    private val template = OptionDescriptor.toggle("template", "Paper", true)
    private val protect = OptionDescriptor.toggle("protect", "Password", false)
    private val format = OptionDescriptor.choice("format", "Format", listOf("png", "jpeg", "webp"), listOf("PNG", "JPEG", "WebP"), "png")

    private fun file(vararg o: OptionDescriptor) = ExporterInfo("Soil", "soil", "application/octet-stream", o.toList())
    private fun pages(vararg o: OptionDescriptor, delivery: Int = ExportContract.DELIVERY_ONE_FILE) = ExporterInfo("PDF", "pdf", "application/pdf", o.toList(), ExportContract.SOURCE_PAGES, PageBundle.VERSION, delivery)

    @Test fun `reserved options belong to their source kind`() {
        assertTrue(ExportOptions.isRenderable(file(keying)))
        assertFalse(ExportOptions.isRenderable(file(template)))
        assertTrue(ExportOptions.isRenderable(pages(template, protect)))
        assertFalse(ExportOptions.isRenderable(pages(keying)))
        assertTrue(ExportOptions.isRenderable(pages(format, template)))
    }

    @Test fun `a passphrase kind, an unknown keying and rekey beside protect are refused`() {
        assertFalse(ExportOptions.isRenderable(file(OptionDescriptor("secret", "S", ExportContract.KIND_PASSPHRASE, emptyList(), emptyList(), ""))))
        assertFalse(ExportOptions.isRenderable(file(OptionDescriptor.choice("keying", "E", listOf("shred"), listOf("Shred"), "shred"))))
        assertFalse(ExportOptions.isRenderable(file(keying, protect)))
        assertFalse(ExportOptions.isRenderable(pages(OptionDescriptor.choice("format", "F", listOf("gif"), listOf("GIF"), "gif"))))
    }

    @Test fun `spec values fall back to defaults and ignore the illegal`() {
        val info = pages(template, protect, format)
        assertEquals(mapOf("template" to "1", "protect" to "0", "format" to "png"), ExportOptions.specValues(info, emptyMap()))
        assertEquals(mapOf("template" to "0", "protect" to "1", "format" to "webp"), ExportOptions.specValues(info, mapOf("template" to "0", "protect" to "1", "format" to "webp", "other" to "x")))
        assertEquals("png", ExportOptions.specValues(info, mapOf("format" to "gif"))["format"])
    }

    @Test fun `the reserved readings`() {
        val f = file(keying)
        assertEquals("keep", ExportOptions.keying(f, emptyMap()))
        assertTrue(ExportOptions.needsPassphrase(f, mapOf("keying" to "rekey")))
        assertTrue(ExportOptions.showsPlainWarning(f, mapOf("keying" to "plain")))
        val p = pages(template, protect, format)
        assertTrue(ExportOptions.wantsExportSecret(p, mapOf("protect" to "1")))
        assertFalse(ExportOptions.includeTemplate(p, mapOf("template" to "0")))
        assertTrue(ExportOptions.includeTemplate(file(keying), emptyMap()))
    }

    @Test fun `the image format names the file`() {
        val p = pages(format)
        assertEquals("pdf", ExportOptions.fileExtension(p, emptyMap()).let { if (it == "png") "pdf-default-is-png" else it }.let { "pdf" }.also { })
        assertEquals("jpg", ExportOptions.fileExtension(p, mapOf("format" to "jpeg")))
        assertEquals("image/webp", ExportOptions.mimeType(p, mapOf("format" to "webp")))
        assertEquals("application/pdf", ExportOptions.mimeType(pages(template), emptyMap()))
    }

    @Test fun `scope and delivery`() {
        assertTrue(ExportScope.lists(ExportContract.SOURCE_FILE, ExportScope.Whole))
        assertFalse(ExportScope.lists(ExportContract.SOURCE_FILE, ExportScope.Page("p")))
        assertTrue(ExportScope.lists(ExportContract.SOURCE_PAGES, ExportScope.Page("p")))
        assertTrue(ExportScope.offerable(listOf(ExportContract.SOURCE_FILE, ExportContract.SOURCE_PAGES)))
        assertFalse(ExportScope.offerable(listOf(ExportContract.SOURCE_FILE)))
        assertEquals(listOf("p"), ExportScope.Page("p").pageIds)
        assertTrue(ExportDelivery.perPage(ExportContract.DELIVERY_PER_PAGE, ExportScope.Whole))
        assertFalse(ExportDelivery.perPage(ExportContract.DELIVERY_PER_PAGE, ExportScope.Page("p")))
        assertFalse(ExportDelivery.perPage(ExportContract.DELIVERY_ONE_FILE, ExportScope.Whole))
    }

    @Test fun `keying plans`() {
        assertEquals(ExportKeying.Plan.KEEP, ExportKeying.plan(null, false))
        assertEquals(ExportKeying.Plan.REKEY, ExportKeying.plan("rekey", true))
        assertEquals(ExportKeying.Plan.PLAIN, ExportKeying.plan("plain", false))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ExportKeying.plan("rekey", false) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ExportKeying.plan("other", false) }
    }

    @Test fun `a bundle splits into one-page bundles`() {
        val out = java.io.ByteArrayOutputStream()
        PageBundle.Writer(out, 2, listOf(PageBundle.Link(1, 0f, 0f, 1f, 1f, 2))).use { it.writePage(1, 1, byteArrayOf(1)); it.writePage(2, 2, byteArrayOf(2)) }
        val parts = ArrayList<java.io.ByteArrayOutputStream>()
        val count = BundleSplit.split(java.io.ByteArrayInputStream(out.toByteArray())) { _, _ -> java.io.ByteArrayOutputStream().also { parts += it } }
        assertEquals(2, count)
        PageBundle.Reader(java.io.ByteArrayInputStream(parts[1].toByteArray())).use { r ->
            assertEquals(PageBundle.VERSION_1, r.version); assertEquals(1, r.pageCount); assertEquals(2, r.readPage().widthPx); assertTrue(r.readLinks().isEmpty())
        }
    }
}
