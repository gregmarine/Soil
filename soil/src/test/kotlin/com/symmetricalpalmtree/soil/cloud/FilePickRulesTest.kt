package com.symmetricalpalmtree.soil.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file-pick screen's decisions (cleanup, 2026-10-10): the filter, the title, the cap and the answer's name. */
class FilePickRulesTest {

    @Test
    fun `no filter, or a blank one, is any file`() {
        assertArrayEquals(arrayOf("*/*"), FilePickRules.mimeTypes(null))
        assertArrayEquals(arrayOf("*/*"), FilePickRules.mimeTypes(arrayOf("", "  ")))
        assertEquals("*/*", FilePickRules.pickerType(FilePickRules.mimeTypes(null)))
    }

    @Test
    fun `a filter is trimmed, deduplicated and capped`() {
        assertArrayEquals(arrayOf("image/png", "image/jpeg"), FilePickRules.mimeTypes(arrayOf(" image/png", "image/jpeg", "image/png", "")))
        assertEquals(16, FilePickRules.mimeTypes(Array(40) { "x/$it" }).size)
    }

    @Test
    fun `the picker's type is the one family asked for, or any`() {
        assertEquals("image/*", FilePickRules.pickerType(arrayOf("image/png", "image/webp")))
        assertEquals("*/*", FilePickRules.pickerType(arrayOf("image/png", "text/plain")))
        assertEquals("*/*", FilePickRules.pickerType(arrayOf("*/*")))
    }

    @Test
    fun `only images wear the image title`() {
        assertTrue(FilePickRules.imagesOnly(arrayOf("image/png", "image/jpeg")))
        assertFalse(FilePickRules.imagesOnly(arrayOf("image/png", "application/pdf")))
        assertFalse(FilePickRules.imagesOnly(arrayOf("*/*")))
    }

    @Test
    fun `the cap and the name`() {
        assertTrue(FilePickRules.fits(FilePickRules.MAX_BYTES))
        assertFalse(FilePickRules.fits(FilePickRules.MAX_BYTES + 1))
        assertTrue(FilePickRules.fits(-1))
        assertEquals("sketch.png", FilePickRules.fileName(" sketch.png "))
        assertEquals("file", FilePickRules.fileName(null))
        assertEquals("file", FilePickRules.fileName("  "))
    }
}
