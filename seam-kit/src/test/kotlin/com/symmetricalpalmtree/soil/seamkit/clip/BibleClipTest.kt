package com.symmetricalpalmtree.soil.seamkit.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BibleClipTest {

    private val clip = BibleClip(BibleClip.VERSION, "JHN:3:14-3:18", "John 3:14–18", "**John 3:14–18**\n\n14 Just as…", 7L)

    @Test
    fun `a passage round-trips through the slot's bytes`() {
        assertEquals(clip, BibleClip.decode(BibleClip.encode(clip)))
    }

    @Test
    fun `what cannot be read is no clipboard`() {
        assertNull(BibleClip.decode(null))
        assertNull(BibleClip.decode(ByteArray(0)))
        assertNull(BibleClip.decode("nonsense".toByteArray()))
        assertNull(BibleClip.decode(BibleClip.encode(clip.copy(version = 2))))
        assertNull(BibleClip.decode(BibleClip.encode(clip.copy(wire = "not a wire"))))
        assertNull(BibleClip.decode(BibleClip.encode(clip.copy(label = " "))))
        assertNull(BibleClip.encode(clip.copy(text = "x".repeat(7 * 1024 * 1024))))
    }
}
