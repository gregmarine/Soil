package com.symmetricalpalmtree.soil.docsprout.editor

import com.symmetricalpalmtree.soil.docsprout.data.BibleUnlinked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BibleLinksTest {

    @Test
    fun `typed words that are a reference become a bible address, and read back as the label`() {
        assertEquals("JHN:3:14-3:18", BibleLinks.wireOf("Jn 3:14-18"))
        assertEquals("JUD:1:24-1:24", BibleLinks.wireOf("Jude 24"))
        assertEquals("bible:JHN:3:14-3:18", BibleLinks.addressOf("JHN:3:14-3:18"))
        assertEquals("John 3:14–18", BibleLinks.labelOf("bible:JHN:3:14-3:18"))
        assertEquals("JHN:3:14-3:18", BibleLinks.wireOfAddress("bible:JHN:3:14-3:18"))
    }

    @Test
    fun `anything else is not`() {
        assertNull(BibleLinks.wireOf("http://example.com"))
        assertNull(BibleLinks.wireOf("just words"))
        assertNull(BibleLinks.wireOf("Genesis 51"))
        assertNull(BibleLinks.wireOf(""))
        assertNull(BibleLinks.labelOf("soil:abc"))
        assertNull(BibleLinks.labelOf("bible:nonsense"))
        assertNull(BibleLinks.wireOfAddress("bible:JHN:3:18-3:14"))
    }

    @Test
    fun `an unlinked reference is keyed by its words, folded, and its wire`() {
        assertEquals(BibleUnlinked.key("John  3:16", "JHN:3:16-3:16"), BibleUnlinked.key(" john 3:16 ", "JHN:3:16-3:16"))
        assert(BibleUnlinked.key("John 3:16", "JHN:3:16-3:16") != BibleUnlinked.key("Jn 3:16", "JHN:3:16-3:16"))
        assertEquals("john 3:16", BibleUnlinked.words("John\n3:16"))
        assert(BibleUnlinked.names(BibleUnlinked.key("Jn 3:16", "JHN:3:16-3:16"), "JHN:3:16-3:16"))
        assert(!BibleUnlinked.names(BibleUnlinked.key("Jn 3:16", "JHN:3:16-3:16"), "JHN:3:17-3:17"))
    }
}
