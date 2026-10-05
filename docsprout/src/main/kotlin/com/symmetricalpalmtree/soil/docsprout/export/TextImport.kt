package com.symmetricalpalmtree.soil.docsprout.export

import com.symmetricalpalmtree.soil.docsprout.data.DocumentLimits
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * What turns the bytes of a picked `.md` or `.txt` into a document's Markdown, or refuses
 * them. The bytes are as untrusted as any file's. Pure and JVM-tested.
 *
 * The rules, in order:
 *
 * 1. **The byte cap first**, before anything is decoded: a document's own limit.
 * 2. **Strict UTF-8**: a malformed sequence refuses the import, instead of landing mojibake in
 *    a document the writer would then edit.
 * 3. **No NULs**: a decodable file holding `U+0000` is binary wearing a text extension.
 *
 * What survives is normalized, not rewritten: a leading BOM is dropped, and line endings
 * become `\n`. Nothing else is touched, and both kinds of file are read as Markdown: a plain
 * text file's wrapped lines join into paragraphs, as its writer meant them to.
 */
object TextImport {

    enum class Refusal { NOT_TEXT, TOO_LARGE }

    class TextProblem(val refusal: Refusal, cause: Throwable? = null) : Exception(cause)

    fun decode(bytes: ByteArray): String {
        if (bytes.size > DocumentLimits.MAX_BODY_BYTES) throw TextProblem(Refusal.TOO_LARGE)
        val decoded = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw TextProblem(Refusal.NOT_TEXT, e)
        }
        if ('\u0000' in decoded) throw TextProblem(Refusal.NOT_TEXT)
        return normalize(decoded)
    }

    /** Drop a leading BOM; fold `\r\n` and a lone `\r` to `\n`. Shrinks or keeps, never grows. */
    fun normalize(text: String): String = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
}
