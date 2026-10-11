package com.symmetricalpalmtree.soil.files

import com.symmetricalpalmtree.soil.ext.CloudEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The local browser's rules (Greg, 2026-10-10): what is shown, in what order, what a filter admits, and the remembered path. */
class LocalFilesTest {

    @Test
    fun `dotfiles and Android's folder are not shown`() {
        assertTrue(LocalFiles.isShown("Document"))
        assertFalse(LocalFiles.isShown(".thumbnails"))
        assertFalse(LocalFiles.isShown("Android"))
        assertFalse(LocalFiles.isShown(""))
    }

    @Test
    fun `folders first, then files, by name without regard to case`() {
        val e = listOf(entry("b.png", false), entry("a", true), entry("A.png", false), entry("c", true))
        assertEquals(listOf("a", "c", "A.png", "b.png"), LocalFiles.sorted(e).map { it.name })
    }

    @Test
    fun `a filter judges by extension and admits what it does not know`() {
        assertTrue(LocalFiles.matches("a.png", arrayOf("image/*")))
        assertTrue(LocalFiles.matches("a.JPG", arrayOf("image/png", "image/jpeg")))
        assertFalse(LocalFiles.matches("a.pdf", arrayOf("image/*")))
        assertTrue(LocalFiles.matches("a.xyz", arrayOf("image/*")))
        assertTrue(LocalFiles.matches("README", arrayOf("image/*")))
        assertTrue(LocalFiles.matches("a.pdf", arrayOf("*/*")))
        assertTrue(LocalFiles.matches("a.pdf", null))
    }

    @Test
    fun `a remembered path round-trips and a bad one is the root`() {
        assertEquals("Document/Exports", LocalFiles.encodePath(listOf("Document", "Exports")))
        assertEquals(listOf("Document", "Exports"), LocalFiles.decodePath("Document/Exports"))
        assertEquals(emptyList<String>(), LocalFiles.decodePath(null))
        assertEquals(emptyList<String>(), LocalFiles.decodePath("Document//x"))
        assertEquals(emptyList<String>(), LocalFiles.decodePath("../etc"))
    }

    @Test
    fun `a folder's path under the root`() {
        val root = File("/storage/emulated/0")
        assertEquals(emptyList<String>(), LocalFiles.pathUnder(root, File("/storage/emulated/0")))
        assertEquals(listOf("Document", "Soil"), LocalFiles.pathUnder(root, File("/storage/emulated/0/Document/Soil")))
        assertNull(LocalFiles.pathUnder(root, File("/storage/emulated/1/x")))
        assertEquals("This device › Document", LocalFiles.label("This device", listOf("Document"), " › "))
    }

    private fun entry(name: String, folder: Boolean) = CloudEntry(id = name, name = name, isFolder = folder, sizeBytes = 0L, modifiedAt = 0L)
}
