package com.symmetricalpalmtree.soil.markdown.rich

import org.junit.Assert.assertEquals
import org.junit.Test

class RichParseTest {

    private fun blocks(markdown: String) = RichParse.parse(markdown).doc.blocks
    private fun one(markdown: String) = blocks(markdown).single()

    @Test
    fun `nothing parses to no blocks`() {
        assertEquals(emptyList<RichBlock>(), blocks(""))
        assertEquals(emptyList<RichBlock>(), blocks("\n\n  \n"))
    }

    @Test
    fun `a paragraph's wrapped lines join into one line of text`() {
        assertEquals(RichBlock(RichAttr.PARAGRAPH, "one two three"), one("one\ntwo\n  three  "))
    }

    @Test
    fun `headings carry their level and no marker`() {
        assertEquals(RichBlock(RichAttr.heading(1), "Title"), one("# Title"))
        assertEquals(RichBlock(RichAttr.heading(6), "Small"), one("###### Small"))
        assertEquals(RichBlock(RichAttr.PARAGRAPH, "#hashtag"), one("#hashtag"))
    }

    @Test
    fun `the three lists carry depth, tick and number`() {
        val got = blocks("- a\n  - b\n- [ ] c\n- [x] d\n3. e\n7. f")
        assertEquals(
            listOf(
                RichBlock(RichAttr(RichKind.BULLET), "a"),
                RichBlock(RichAttr(RichKind.BULLET, depth = 1), "b"),
                RichBlock(RichAttr(RichKind.TASK), "c"),
                RichBlock(RichAttr(RichKind.TASK, checked = true), "d"),
                RichBlock(RichAttr(RichKind.ORDERED, number = 3), "e"),
                RichBlock(RichAttr(RichKind.ORDERED, number = 4), "f"),
            ),
            got,
        )
    }

    @Test
    fun `consecutive quote lines are one quote and a rule is a rule`() {
        assertEquals(
            listOf(RichBlock(RichAttr(RichKind.QUOTE), "one two"), RichBlock(RichAttr(RichKind.RULE)), RichBlock(RichAttr(RichKind.QUOTE), "three")),
            blocks("> one\n> two\n---\n> three"),
        )
    }

    @Test
    fun `inline styles become spans over text without markers`() {
        val b = one("a **bold** _it_ *al* ~~gone~~ `code` [link](http://x)")
        assertEquals("a bold it al gone code link", b.text)
        assertEquals(
            listOf(
                RichSpan(2, 6, RichStyle.BOLD),
                RichSpan(7, 9, RichStyle.ITALIC),
                RichSpan(10, 12, RichStyle.ITALIC),
                RichSpan(13, 17, RichStyle.STRIKE),
                RichSpan(18, 22, RichStyle.CODE),
                RichSpan(23, 27, RichStyle.LINK, "http://x"),
            ),
            b.spans,
        )
    }

    @Test
    fun `styles nest`() {
        val b = one("**bold _both_**")
        assertEquals("bold both", b.text)
        assertEquals(listOf(RichSpan(0, 9, RichStyle.BOLD), RichSpan(5, 9, RichStyle.ITALIC)), b.spans)
    }

    @Test
    fun `an escaped marker is the character itself`() {
        assertEquals(RichBlock(RichAttr.PARAGRAPH, "*not* _it_ `x` [y](z) \\"), one("""\*not\* \_it\_ \`x\` \[y](z) \\"""))
        assertEquals(RichBlock(RichAttr.PARAGRAPH, "# not a heading"), one("""\# not a heading"""))
        assertEquals(RichBlock(RichAttr.PARAGRAPH, "1. not a list"), one("""1\. not a list"""))
    }

    @Test
    fun `half-typed and empty markup stays as typed`() {
        assertEquals("a * b", one("a * b").text)
        assertEquals("**", one("**").text)
        assertEquals("a ****", one("a ****").text)
        assertEquals("[text](", one("[text](").text)
        assertEquals("a ` b", one("a ` b").text)
    }

    @Test
    fun `code keeps its characters as they are`() {
        val b = one("""`a*b\_c`""")
        assertEquals("""a*b\_c""", b.text)
        assertEquals(listOf(RichSpan(0, 6, RichStyle.CODE)), b.spans)
    }

    @Test
    fun `a marker inside a link's address does not close a style around the link`() {
        val b = one("_[a](http://x/a_b)_")
        assertEquals("a", b.text)
        assertEquals(listOf(RichSpan(0, 1, RichStyle.ITALIC), RichSpan(0, 1, RichStyle.LINK, "http://x/a_b")), b.spans)
    }

    @Test
    fun `an image stays the characters that spell it`() {
        assertEquals(RichBlock(RichAttr.PARAGRAPH, "see ![a_b](http://x/y_z.png) here"), one("see ![a_b](http://x/y_z.png) here"))
    }

    @Test
    fun `a fenced block is raw lines, exactly as written, blank lines and all`() {
        val raw = RichAttr(RichKind.RAW)
        assertEquals(
            listOf(
                RichBlock(RichAttr.PARAGRAPH, "before"),
                RichBlock(raw, "```kotlin"),
                RichBlock(raw, "  val x = **1**  "),
                RichBlock(raw, ""),
                RichBlock(raw, "# not a heading"),
                RichBlock(raw, "```"),
                RichBlock(RichAttr.PARAGRAPH, "after"),
            ),
            blocks("before\n```kotlin\n  val x = **1**  \n\n# not a heading\n```\nafter"),
        )
    }

    @Test
    fun `table rows are raw lines and a blank line between two tables is kept`() {
        val raw = RichAttr(RichKind.RAW)
        assertEquals(
            listOf(RichBlock(raw, "| a | b |"), RichBlock(raw, "|---|---|"), RichBlock(raw, ""), RichBlock(raw, "| c |"), RichBlock(RichAttr.PARAGRAPH, "text")),
            blocks("| a | b |\n|---|---|\n\n| c |\n\ntext"),
        )
    }

    @Test
    fun `every block reports where it began`() {
        val md = "# T\n\npara\nmore\n\n- a\n- b\n"
        val result = RichParse.parse(md)
        assertEquals(listOf(0, 5, 16, 20), result.offsets.toList())
        assertEquals(4, result.doc.blocks.size)
    }

    @Test
    fun `windows line endings are not part of the text`() {
        assertEquals(listOf(RichBlock(RichAttr.heading(1), "T"), RichBlock(RichAttr.PARAGRAPH, "a b")), blocks("# T\r\n\r\na\r\nb\r\n"))
    }

    @Test
    fun `a tilde fence and a longer fence are raw, closed only by the same run`() {
        val raw = RichAttr(RichKind.RAW)
        assertEquals(
            listOf(RichBlock(raw, "~~~"), RichBlock(raw, "**x**"), RichBlock(raw, "```"), RichBlock(raw, "~~~"), RichBlock(RichAttr.PARAGRAPH, "after")),
            blocks("~~~\n**x**\n```\n~~~\nafter"),
        )
        assertEquals(
            listOf(RichBlock(raw, "````md"), RichBlock(raw, "```"), RichBlock(raw, "```kotlin"), RichBlock(raw, "````"), RichBlock(RichAttr.PARAGRAPH, "after")),
            blocks("````md\n```\n```kotlin\n````\nafter"),
        )
    }

    @Test
    fun `a fence never closed runs to the end`() {
        val raw = RichAttr(RichKind.RAW)
        assertEquals(listOf(RichBlock(RichAttr.PARAGRAPH, "a"), RichBlock(raw, "```"), RichBlock(raw, "# b")), blocks("a\n```\n# b"))
    }

    @Test
    fun `indented code after a blank line is raw, blank lines inside it kept`() {
        val raw = RichAttr(RichKind.RAW)
        assertEquals(
            listOf(RichBlock(RichAttr.PARAGRAPH, "a"), RichBlock(raw, "    x = *1*"), RichBlock(raw, ""), RichBlock(raw, "\t# y"), RichBlock(RichAttr.PARAGRAPH, "b")),
            blocks("a\n\n    x = *1*\n\n\t# y\n\nb"),
        )
    }

    @Test
    fun `an indented line is not code inside a paragraph or under a list`() {
        assertEquals(listOf(RichBlock(RichAttr.PARAGRAPH, "a b")), blocks("a\n    b"))
        assertEquals(
            listOf(RichBlock(RichAttr(RichKind.BULLET), "a"), RichBlock(RichAttr(RichKind.BULLET, depth = 2), "b")),
            blocks("- a\n\n    - b"),
        )
    }

    @Test
    fun `many brackets with nothing to close them read as written`() {
        val text = "[".repeat(20_000) + "x"
        assertEquals(text, one(text).text)
        val opened = "[a](".repeat(5_000)
        assertEquals(opened, one(opened).text)
    }

    @Test
    fun `the bracket scan finds what a scan from each bracket finds`() {
        val random = kotlin.random.Random(20261008)
        val alphabet = "[]()\\a!"
        repeat(2_000) {
            val src = String(CharArray(random.nextInt(0, 24)) { alphabet[random.nextInt(alphabet.length)] })
            val scan = RichInline.Scan(src)
            for (i in src.indices) {
                if (src[i] != '[') continue
                val plain = RichInline.linkAt(src, i)
                val cached = RichInline.linkAt(src, i, scan)
                assertEquals(src, plain?.let { it.textEnd to it.end }, cached?.let { it.textEnd to it.end })
            }
        }
    }

    @Test
    fun `an indented list item is a nested item, not code`() {
        assertEquals(listOf(RichBlock(RichAttr(RichKind.ORDERED, depth = 2, number = 1), "a")), blocks("    1. a"))
    }
}
