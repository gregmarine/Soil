package com.symmetricalpalmtree.soil.seam

import org.junit.Assert.assertThrows
import org.junit.Test

class SeamBytesTest {

    @Test
    fun `a count within the region is valid`() {
        SeamBytes.requireValid(0, 1)
        SeamBytes.requireValid(10, 10)
        SeamBytes.requireValid(SeamLimits.MAX_PAYLOAD_BYTES, SeamLimits.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun `a count past the region, negative or over the cap is refused`() {
        assertThrows(IllegalArgumentException::class.java) { SeamBytes.requireValid(11, 10) }
        assertThrows(IllegalArgumentException::class.java) { SeamBytes.requireValid(-1, 10) }
        assertThrows(IllegalArgumentException::class.java) {
            SeamBytes.requireValid(SeamLimits.MAX_PAYLOAD_BYTES + 1, SeamLimits.MAX_PAYLOAD_BYTES + 1)
        }
    }
}
