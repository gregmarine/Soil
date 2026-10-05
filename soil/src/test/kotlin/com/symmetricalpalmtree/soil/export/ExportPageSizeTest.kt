package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.seam.Seam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPageSizeTest {

    @Test
    fun `a remembered size this build does not offer reads as the default`() {
        assertEquals(Seam.PAGE_A4, ExportPageSize.orDefault(Seam.PAGE_A4))
        assertEquals(ExportPageSize.DEFAULT, ExportPageSize.orDefault("tabloid"))
        assertEquals(ExportPageSize.DEFAULT, ExportPageSize.orDefault(null))
    }

    @Test
    fun `a paper size tells the exporter how many points a pixel is, the screen tells it nothing`() {
        assertEquals(mapOf(ExportContract.OPTION_PAGE_POINTS to "0.36"), ExportPageSize.specValues(Seam.PAGE_LETTER))
        assertEquals(ExportPageSize.specValues(Seam.PAGE_LETTER), ExportPageSize.specValues(Seam.PAGE_A4))
        assertTrue(ExportPageSize.specValues(Seam.PAGE_SCREEN).isEmpty())
    }
}
