package com.symmetricalpalmtree.soil.ext.soilfile

import com.symmetricalpalmtree.soil.ext.ExportContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SoilFileExportSpecTest {
    @Test fun `absent keying is keep`() = assertEquals(ExportContract.KEYING_KEEP, SoilFileExportSpec.keying(emptyMap()))
    @Test fun `each offered keying passes`() {
        for (k in SoilFileExportSpec.SUPPORTED_KEYING) assertEquals(k, SoilFileExportSpec.keying(mapOf(ExportContract.OPTION_KEYING to k)))
    }
    @Test fun `an unknown keying is refused`() {
        assertThrows(IllegalArgumentException::class.java) { SoilFileExportSpec.keying(mapOf(ExportContract.OPTION_KEYING to "shred")) }
    }
    @Test fun `unknown keys are ignored`() = assertEquals(ExportContract.KEYING_KEEP, SoilFileExportSpec.keying(mapOf("template" to "1")))
}
