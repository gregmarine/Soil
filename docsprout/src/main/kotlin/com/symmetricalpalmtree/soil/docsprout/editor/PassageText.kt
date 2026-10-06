package com.symmetricalpalmtree.soil.docsprout.editor

/**
 * **The reader's passage Markdown, as a document takes it**: a bold label line, then the verses
 * as prose, one paragraph per chapter run, each later run under a bold line of its own book and
 * chapter. The label line is dropped (the document links the passage under its own label); every
 * other paragraph is kept, as Markdown for the source and as plain words for the rendered
 * document (a bold chapter line loses its stars). Pure.
 */
object PassageText {

    class Paragraph(val markdown: String) {
        val plain: String get() = markdown.removeSurrounding("**")
    }

    fun paragraphs(markdown: String): List<Paragraph> {
        val parts = markdown.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val body = if (parts[0].startsWith("**") && parts[0].endsWith("**")) parts.drop(1) else parts
        return body.map { Paragraph(it) }
    }
}
