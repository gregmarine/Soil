package com.symmetricalpalmtree.soil.markdown.rich

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class RichWriteTest {

    private fun write(vararg blocks: RichBlock) = RichWrite.write(RichDoc(blocks.toList())).text
    private fun p(text: String, vararg spans: RichSpan) = RichBlock(RichAttr.PARAGRAPH, text, spans.toList())

    @Test
    fun `an empty document writes nothing`() {
        assertEquals("", RichWrite.write(RichDoc.EMPTY).text)
        assertEquals("", write(p(""), p("   ")))
    }

    @Test
    fun `blocks are written in the canonical hand`() {
        assertEquals(
            "# Title\n\nwords\n\n- a\n  - b\n- [ ] c\n- [x] d\n\n1. e\n2. f\n\n> quote\n\n---\n\nend\n",
            write(
                RichBlock(RichAttr.heading(1), "Title"),
                p("words"),
                RichBlock(RichAttr(RichKind.BULLET), "a"),
                RichBlock(RichAttr(RichKind.BULLET, depth = 1), "b"),
                RichBlock(RichAttr(RichKind.TASK), "c"),
                RichBlock(RichAttr(RichKind.TASK, checked = true), "d"),
                RichBlock(RichAttr(RichKind.ORDERED, number = 1), "e"),
                RichBlock(RichAttr(RichKind.ORDERED, number = 9), "f"),
                RichBlock(RichAttr(RichKind.QUOTE), "quote"),
                RichBlock(RichAttr(RichKind.RULE)),
                p("end"),
            ),
        )
    }

    @Test
    fun `styles are written nested in one order`() {
        assertEquals("**bold** _it_ ~~gone~~ `code` [link](http://x)\n", write(p(
            "bold it gone code link",
            RichSpan(0, 4, RichStyle.BOLD), RichSpan(5, 7, RichStyle.ITALIC), RichSpan(8, 12, RichStyle.STRIKE),
            RichSpan(13, 17, RichStyle.CODE), RichSpan(18, 22, RichStyle.LINK, "http://x"),
        )))
        assertEquals("**_both_**\n", write(p("both", RichSpan(0, 4, RichStyle.ITALIC), RichSpan(0, 4, RichStyle.BOLD))))
        // Bold over "ab", italic over "bc": the runs are closed and reopened, never crossed.
        assertEquals("**a_b_**_c_\n", write(p("abc", RichSpan(0, 2, RichStyle.BOLD), RichSpan(1, 3, RichStyle.ITALIC))))
    }

    @Test
    fun `plain text with no partner for its markers is written as it reads`() {
        assertEquals("snake_case, 2 * 3, a [b, c] d, ~x, it's 100%!\n", write(p("snake_case, 2 * 3, a [b, c] d, ~x, it's 100%!")))
    }

    @Test
    fun `a marker with its partner after it is escaped`() {
        assertEquals("""a\*b*c""" + "\n", write(p("a*b*c")))
        assertEquals("""snake\_case_word""" + "\n", write(p("snake_case_word")))
        assertEquals("""\[x](y)""" + "\n", write(p("[x](y)")))
        assertEquals("""a\~~b""" + "\n", write(p("a~~b")))
        assertEquals("""C:\\dir""" + "\n", write(p("""C:\dir""")))
        assertEquals("""\*x **b**""" + "\n", write(p("*x b", RichSpan(3, 4, RichStyle.BOLD))))
    }

    @Test
    fun `a paragraph that would read as another block has its start escaped`() {
        assertEquals("""\# x""" + "\n", write(p("# x")))
        assertEquals("""\- x""" + "\n", write(p("- x")))
        assertEquals("""\+ x""" + "\n", write(p("+ x")))
        assertEquals("""\> x""" + "\n", write(p("> x")))
        assertEquals("""12\. x""" + "\n", write(p("12. x")))
        assertEquals("""\---""" + "\n", write(p("---")))
        assertEquals("""\| a |""" + "\n", write(p("| a |")))
    }

    @Test
    fun `an ordered list and a bullet list are a blank line apart, and read back as two lists`() {
        val doc = RichDoc(listOf(
            RichBlock(RichAttr(RichKind.ORDERED, number = 1), "one"),
            RichBlock(RichAttr(RichKind.ORDERED, number = 2), "two"),
            RichBlock(RichAttr(RichKind.BULLET), "a"),
            RichBlock(RichAttr(RichKind.BULLET), "b"),
        ))
        val written = RichWrite.write(doc).text
        assertEquals("1. one\n2. two\n\n- a\n- b\n", written)
        assertEquals(doc.normalized(), RichParse.parse(written).doc)
    }

    @Test
    fun `raw lines are written as they are, tight together`() {
        val raw = RichAttr(RichKind.RAW)
        assertEquals("a\n\n```\n  x *y*\n\n```\n\nb\n", write(p("a"), RichBlock(raw, "```"), RichBlock(raw, "  x *y*"), RichBlock(raw, ""), RichBlock(raw, "```"), p("b")))
    }

    @Test
    fun `a raw line that would not read back as raw is written as a paragraph of its words`() {
        val raw = RichAttr(RichKind.RAW)
        // A line edited out of its table, and one that would read as a heading.
        assertEquals("| a |\n\nfoo\n\n\\# bar\n\n| b |\n", write(RichBlock(raw, "| a |"), RichBlock(raw, "foo"), RichBlock(raw, "# bar"), RichBlock(raw, "| b |")))
        // A fence whose closing line is gone, with words after it, does not swallow them.
        assertEquals("\\`\\``\n\nx\n\nend\n", write(RichBlock(raw, "```"), RichBlock(raw, "  x"), p("end")))
        // A fence never closed at the end of the document is kept as it was read.
        assertEquals("a\n\n```\n  x\n", write(p("a"), RichBlock(raw, "```"), RichBlock(raw, "  x")))
    }

    @Test
    fun `indented code and tilde fences are written as they are`() {
        val raw = RichAttr(RichKind.RAW)
        assertEquals("a\n\n    x\n\n    y\n~~~\n*z*\n~~~\n", write(p("a"), RichBlock(raw, "    x"), RichBlock(raw, ""), RichBlock(raw, "    y"), RichBlock(raw, "~~~"), RichBlock(raw, "*z*"), RichBlock(raw, "~~~")))
        // Under a list the indent would be the list's.
        assertEquals("- a\n\nx\n", write(RichBlock(RichAttr(RichKind.BULLET), "a"), RichBlock(raw, "    x")))
    }

    @Test
    fun `a strike around a tilde at the start of a paragraph is not a fence`() {
        assertEquals("~~\\~a~~\n", write(p("~a", RichSpan(0, 2, RichStyle.STRIKE))))
        roundTrip(RichDoc(listOf(p("~~", RichSpan(0, 2, RichStyle.STRIKE)))))
    }

    @Test
    fun `an image is written as it is held`() {
        assertEquals("see ![a_b](http://x/y_z.png) and _it_\n", write(p("see ![a_b](http://x/y_z.png) and it", RichSpan(33, 35, RichStyle.ITALIC))))
    }

    @Test
    fun `every block reports where it was written, a dropped one where the next is`() {
        val result = RichWrite.write(RichDoc(listOf(RichBlock(RichAttr.heading(1), "T"), p(""), p("a"), p(""))))
        assertEquals("# T\n\na\n", result.text)
        assertEquals(listOf(0, 5, 5, 7), result.offsets.toList())
    }

    // ── What is written reads back ──────

    private fun roundTrip(doc: RichDoc) {
        val normal = doc.normalized()
        val written = RichWrite.write(doc).text
        assertEquals("the document written as:\n$written", normal, RichParse.parse(written).doc)
        // And writing what was read changes nothing.
        assertEquals(written, RichWrite.write(RichParse.parse(written).doc).text)
    }

    @Test
    fun `awkward text reads back as written`() {
        val texts = listOf(
            "plain", "a*b*c", "*", "**", "***", "_", "__x__", "a_b_c_d", "`", "a`b`c", "~~", "~~~", "a~~b~~c", "[", "]", "[]", "[a](b)", "![a](b)", "!",
            "\\", "\\*", "a\\", "# x", "#", "- x", "-", "* x", "+ x", "> x", ">", "1. x", "1.", "---", "***", "___", "- - -", "| x", "```", "``` x",
            "<b>html</b>", "a  b", "x!", "end*", "*start", "100% _sure_ *really* `ok`", "a ] b [ c", "tab\there", "é ü — “q”", "[x] y", "[ ] y",
        )
        for (text in texts) {
            for (kind in listOf(RichKind.PARAGRAPH, RichKind.HEADING, RichKind.BULLET, RichKind.ORDERED, RichKind.TASK, RichKind.QUOTE)) {
                val attr = RichAttr(kind, level = 2, number = 1).canonical()
                roundTrip(RichDoc(listOf(RichBlock(attr, text))))
                if (text.length >= 2) {
                    for (style in listOf(RichStyle.BOLD, RichStyle.ITALIC, RichStyle.STRIKE)) {
                        roundTrip(RichDoc(listOf(RichBlock(attr, text, listOf(RichSpan(0, 1, style))))))
                        roundTrip(RichDoc(listOf(RichBlock(attr, text, listOf(RichSpan(1, text.length, style))))))
                        roundTrip(RichDoc(listOf(RichBlock(attr, text, listOf(RichSpan(0, text.length, style))))))
                    }
                }
            }
        }
    }

    @Test
    fun `raw documents read back as written`() {
        val raw = RichAttr(RichKind.RAW)
        fun r(t: String) = RichBlock(raw, t)
        val docs = listOf(
            listOf(p("a"), r("~~~"), r("x"), r(""), r("```"), r("~~~"), p("b")),
            listOf(r("````"), r("```"), r("````"), r(""), r("| t |")),
            listOf(p("a"), r("    code"), r(""), r("\tmore"), p("b"), r("    again")),
            listOf(r("| a |"), r("foo"), r("    x"), r("```"), p("end")),
            listOf(RichBlock(RichAttr(RichKind.ORDERED, number = 1), "a"), r("    x"), r("| t |"), r(""), r("    y")),
        )
        for (blocks in docs) {
            val written = RichWrite.write(RichDoc(blocks)).text
            // Read back, then written again, nothing changes.
            assertEquals(written, RichWrite.write(RichParse.parse(written).doc).text)
        }
        roundTrip(RichDoc(listOf(p("a"), r("~~~"), r("x"), r(""), r("```"), r("~~~"), p("b"))))
        roundTrip(RichDoc(listOf(p("a"), r("    code"), r(""), r("\tmore"), p("b"))))
    }

    @Test
    fun `a style may sit beside the character that marks it`() {
        roundTrip(RichDoc(listOf(p("a_b", RichSpan(0, 1, RichStyle.ITALIC)))))
        roundTrip(RichDoc(listOf(p("_ab", RichSpan(1, 2, RichStyle.ITALIC)))))
        roundTrip(RichDoc(listOf(p("a*b*", RichSpan(1, 3, RichStyle.BOLD)))))
        roundTrip(RichDoc(listOf(p("~a~", RichSpan(1, 2, RichStyle.STRIKE)))))
        roundTrip(RichDoc(listOf(p("!link", RichSpan(1, 5, RichStyle.LINK, "u")))))
        roundTrip(RichDoc(listOf(p("a]b[c", RichSpan(0, 5, RichStyle.LINK, "http://x/a_b*c")), p("x_y", RichSpan(0, 3, RichStyle.ITALIC), RichSpan(0, 3, RichStyle.LINK, "u_v")))))
        roundTrip(RichDoc(listOf(p("code*x", RichSpan(0, 4, RichStyle.CODE), RichSpan(0, 6, RichStyle.BOLD)))))
    }

    @Test
    fun `whole documents read back as written`() {
        val random = Random(20261004)
        val words = listOf("word", "a", "snake_case", "2*3", "x_", "*", "~", "[b]", "`", "C:\\d", "end.", "#1", "1.", "-", ">", "!", "(p)", "é")
        repeat(400) {
            val blocks = ArrayList<RichBlock>()
            repeat(random.nextInt(1, 9)) {
                val kind = RichKind.values()[random.nextInt(RichKind.values().size)]
                when (kind) {
                    RichKind.RULE -> blocks += RichBlock(RichAttr(kind))
                    RichKind.RAW -> {
                        blocks += RichBlock(RichAttr(kind), "| " + words[random.nextInt(words.size)] + " |")
                    }
                    else -> {
                        val text = (0 until random.nextInt(1, 6)).joinToString(" ") { words[random.nextInt(words.size)] }
                        val spans = ArrayList<RichSpan>()
                        repeat(random.nextInt(0, 4)) {
                            val a = random.nextInt(0, text.length)
                            val b = random.nextInt(a + 1, text.length + 1)
                            val style = listOf(RichStyle.BOLD, RichStyle.ITALIC, RichStyle.STRIKE, RichStyle.LINK)[random.nextInt(4)]
                            // One link over a stretch: two with different addresses over the same
                            // characters cannot both be written.
                            if (style != RichStyle.LINK || spans.none { it.style == RichStyle.LINK }) spans += RichSpan(a, b, style, if (style == RichStyle.LINK) "http://x/a_b" else "")
                        }
                        val attr = RichAttr(kind, level = random.nextInt(1, 7), depth = random.nextInt(0, 3), checked = random.nextBoolean(), number = random.nextInt(1, 5)).canonical()
                        blocks += RichBlock(attr, text, spans)
                    }
                }
            }
            roundTrip(RichDoc(blocks))
        }
    }
}
