package com.symmetricalpalmtree.soil.markdown

import com.symmetricalpalmtree.soil.markdown.rich.RichKind
import com.symmetricalpalmtree.soil.markdown.rich.RichParse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared code-line rule, and that it is the rendered editor's own reading. */
class MarkdownCodeTest {

    private fun code(text: String): List<Int> =
        MarkdownCode.codeLines(text.split('\n')).withIndex().filter { it.value }.map { it.index }

    @Test
    fun fencesOfEitherKindAndAnyLength() {
        assertEquals(listOf(1, 2, 3), code("a\n```\nx\n```\nb"))
        assertEquals(listOf(0, 1, 2, 3), code("~~~\n```\nx\n~~~\nb"))
        assertEquals(listOf(0, 1, 2, 3), code("````\n```\nx\n`````\nb"))
        // Never closed: to the end.
        assertEquals(listOf(0, 1, 2, 3), code("```\nx\n\n# y"))
    }

    @Test
    fun indentedCodeOnlyAfterABlankLineAndNotUnderAList() {
        assertEquals(listOf(2, 3, 4), code("a\n\n    one\n\n    two\n\nb"))
        assertEquals(emptyList<Int>(), code("a\n    joined"))
        assertEquals(emptyList<Int>(), code("- item\n\n    under it"))
        assertEquals(emptyList<Int>(), code("a\n\n    - nested item"))
        assertEquals(listOf(0), code("\tcode"))
    }

    /** Every non-blank line RichParse keeps raw (tables aside) is code here, and nothing else. */
    @Test
    fun agreesWithTheRenderedEditor() {
        val samples = listOf(
            "a\n```\n# x\n```\nb",
            "~~~\n```\nx\n~~~\n# h",
            "````\n```\n````\n- b",
            "p\n\n    one\n\n\n    two\n\n- item\n\n    under\n\n    still\n\ntext\n\n    code",
            "1. a\n   ```\n   in\n   ```\n2. b",
            "> q\n\n    code\nmore\n\n```",
        )
        for (src in samples) {
            val lines = src.split('\n')
            val starts = IntArray(lines.size)
            for (i in 1 until lines.size) starts[i] = starts[i - 1] + lines[i - 1].length + 1
            val parsed = RichParse.parse(src)
            val rawAt = parsed.doc.blocks.indices.filter { parsed.doc.blocks[it].attr.kind == RichKind.RAW }.map { parsed.offsets[it] }.toSet()
            val rich = BooleanArray(lines.size) { starts[it] in rawAt && lines[it].isNotBlank() && !lines[it].trim().startsWith("|") }
            val mine = MarkdownCode.codeLines(lines).also { arr -> lines.indices.forEach { if (lines[it].isBlank()) arr[it] = false } }
            assertArrayEquals(src, rich, mine)
        }
    }
}
