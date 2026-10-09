package com.symmetricalpalmtree.soil.seamkit.clip

import org.junit.Assert.assertEquals
import com.symmetricalpalmtree.soil.seam.SeamLimits
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
        assertNull(BibleClip.decode("{\"version\":1,\"wire\":\"JHN:3:16-3:16\",\"label\":\"${"x".repeat(BibleClip.MAX_LABEL_CHARS + 1)}\",\"text\":\"\",\"copiedAt\":1}".toByteArray()))
        assertNull(BibleClip.encode(clip.copy(text = "x".repeat(SeamLimits.MAX_VALUE_BYTES + 1))))
    }

    @Test
    fun `a clip that could not be pasted is never written`() {
        assertNull(BibleClip.encode(clip.copy(wire = "not a wire")))
        assertNull(BibleClip.encode(clip.copy(label = "")))
        assertNull(BibleClip.encode(clip.copy(label = " ")))
        assertNull(BibleClip.encode(clip.copy(label = "x".repeat(BibleClip.MAX_LABEL_CHARS + 1))))
        val longest = clip.copy(label = "x".repeat(BibleClip.MAX_LABEL_CHARS))
        assertEquals(longest, BibleClip.decode(BibleClip.encode(longest)))
    }
}
