package com.symmetricalpalmtree.soil.ext.image

import android.graphics.Bitmap
import com.symmetricalpalmtree.soil.ext.ExportContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageTest {

    @Test fun `the descriptor is a per-page pages exporter with format, quality and paper`() {
        val info = ImageDescriptor.info()
        assertEquals(ExportContract.DELIVERY_PER_PAGE, info.delivery)
        assertEquals(ExportContract.SOURCE_PAGES, info.sourceKind)
        assertEquals(listOf("format", "quality", "template"), info.options.map { it.id })
        assertEquals("png", info.fileExtension)
    }

    @Test fun `every declared format choice is one Soil can name`() {
        val format = ImageDescriptor.options.first { it.id == ExportContract.OPTION_IMAGE_FORMAT }
        for (id in format.choiceIds) {
            assert(ExportContract.imageExtension(id) != null)
            assert(ExportContract.imageMime(id) != null)
        }
    }

    @Test fun `the spec maps formats and qualities, PNG always lossless`() {
        assertEquals(ImageExportSpec.Encoding(Bitmap.CompressFormat.PNG, 100), ImageExportSpec.require(emptyMap(), null))
        assertEquals(ImageExportSpec.Encoding(Bitmap.CompressFormat.PNG, 100), ImageExportSpec.require(mapOf("format" to "png", "quality" to "small"), null))
        assertEquals(ImageExportSpec.Encoding(Bitmap.CompressFormat.JPEG, 70), ImageExportSpec.require(mapOf("format" to "jpeg", "quality" to "small"), null))
        assertEquals(95, ImageExportSpec.require(mapOf("format" to "webp", "quality" to "best"), null).quality)
        assertEquals(85, ImageExportSpec.require(mapOf("format" to "jpeg"), null).quality)
    }

    @Test fun `the spec refuses what it does not offer`() {
        assertThrows(IllegalArgumentException::class.java) { ImageExportSpec.require(mapOf("format" to "gif"), null) }
        assertThrows(IllegalArgumentException::class.java) { ImageExportSpec.require(mapOf("quality" to "huge"), null) }
        assertThrows(IllegalArgumentException::class.java) { ImageExportSpec.require(mapOf("protect" to "1"), null) }
        assertThrows(IllegalArgumentException::class.java) { ImageExportSpec.require(emptyMap(), "pw") }
    }

    @Test fun `one page only`() {
        ImageAssembly.requireOnePage(1)
        assertThrows(IllegalStateException::class.java) { ImageAssembly.requireOnePage(2) }
        assertThrows(IllegalStateException::class.java) { ImageAssembly.requireOnePage(0) }
    }
}
