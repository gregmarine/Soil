package com.symmetricalpalmtree.soil.docsprout.export

import com.symmetricalpalmtree.soil.docsprout.data.DocumentLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextImportTest {

    @Test
    fun `text comes in as it is`() {
        assertEquals("# Title\n\nWords — and é.\n", TextImport.decode("# Title\n\nWords — and é.\n".toByteArray(Charsets.UTF_8)))
        assertEquals("", TextImport.decode(ByteArray(0)))
    }

    @Test
    fun `a byte order mark is dropped and line endings become one kind`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a\r\nb\rc\n".toByteArray(Charsets.UTF_8)
        assertEquals("a\nb\nc\n", TextImport.decode(bytes))
    }

    @Test
    fun `bytes that are not UTF-8 are refused, not guessed at`() {
        val latin1 = byteArrayOf('c'.code.toByte(), 'a'.code.toByte(), 'f'.code.toByte(), 0xE9.toByte())
        assertEquals(TextImport.Refusal.NOT_TEXT, assertThrows(TextImport.TextProblem::class.java) { TextImport.decode(latin1) }.refusal)
    }

    @Test
    fun `a file holding a NUL is binary wearing a text name`() {
        assertEquals(TextImport.Refusal.NOT_TEXT, assertThrows(TextImport.TextProblem::class.java) { TextImport.decode(byteArrayOf(65, 0, 66)) }.refusal)
    }

    @Test
    fun `a file over a document's limit is refused before it is decoded`() {
        val over = ByteArray(DocumentLimits.MAX_BODY_BYTES + 1) { 'a'.code.toByte() }
        assertEquals(TextImport.Refusal.TOO_LARGE, assertThrows(TextImport.TextProblem::class.java) { TextImport.decode(over) }.refusal)
    }
}
