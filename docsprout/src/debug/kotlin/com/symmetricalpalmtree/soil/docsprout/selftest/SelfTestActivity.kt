package com.symmetricalpalmtree.soil.docsprout.selftest

import android.graphics.Color
import android.os.Bundle
import android.text.Spanned
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.soil.docsprout.editor.rich.BlockSpan
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichEditText
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichOps
import com.symmetricalpalmtree.soil.markdown.rich.RichKind
import com.symmetricalpalmtree.soil.markdown.rich.RichParse
import com.symmetricalpalmtree.soil.markdown.rich.RichStyle
import com.symmetricalpalmtree.soil.markdown.rich.RichWrite

/**
 * **The rendered editor's rules against a real `Editable`.** The JVM has no `android.text`, so
 * what `RichEditText` does to its own text is proved here: each case loads Markdown, edits the
 * text the way a keyboard does (inserts and deletes through the `Editable`, with the view's
 * watcher running), and compares the Markdown that comes out. After every case the blocks are
 * checked: one span a paragraph, over exactly that paragraph.
 *
 * It opens no document and reaches Soil for nothing. The result is on the screen and in the log
 * under `DocsproutSelfTest`.
 */
class SelfTestActivity : AppCompatActivity() {

    private lateinit var view: RichEditText
    private val lines = ArrayList<String>()
    private var failed = 0
    private var passed = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = RichEditText(this).apply { setTextColor(Color.BLACK); textSize = 16f }
        val report = TextView(this).apply { setTextColor(Color.BLACK); textSize = 12f; setPadding(24, 24, 24, 24) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(ScrollView(context).apply { addView(report) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(column)
        view.post {
            runCatching { run() }.onFailure { fail("the run threw ${it.javaClass.simpleName} at ${it.stackTrace.firstOrNull { e -> e.className.contains("docsprout") }}") }
            val summary = "RESULT passed=$passed failed=$failed"
            lines.add(0, summary)
            Log.i(TAG, summary)
            report.text = lines.joinToString("\n")
            // Left showing something a person can look at: every kind of block there is.
            load("# Heading one\n\n## Heading two\n\nA paragraph with **bold**, _italic_, ~~struck~~, `code` and a [link](http://example.com), long enough to wrap onto a second line of the page so the wrapping can be seen.\n\n- a bullet\n  - nested\n    - and again\n- [ ] a task\n- [x] a done task\n1. one\n2. two\n\n> A quote, which also runs long enough to wrap so that its stripe can be seen beside both of its lines.\n\n---\n\n| a | table |\n|---|---|\n\nThe end.")
        }
    }

    // ── The harness ──────

    private fun load(markdown: String) = view.load(RichParse.parse(markdown).doc)
    private fun markdown(): String = RichWrite.write(view.document()).text
    private fun text() = view.text!!
    private fun at(needle: String, after: Boolean = false): Int = text().toString().indexOf(needle).also { require(it >= 0) { "no '$needle'" } } + if (after) needle.length else 0
    private fun type(at: Int, words: String) { view.setSelection(at); text().insert(at, words) }
    private fun delete(from: Int, to: Int) { text().delete(from, to) }
    private fun select(needle: String) { val a = at(needle); view.setSelection(a, a + needle.length) }

    private fun fail(what: String) { failed++; lines += "FAIL $what"; Log.w(TAG, "FAIL $what") }

    private fun check(name: String, expected: String) {
        val got = markdown()
        val broken = brokenBlocks()
        when {
            got != expected -> fail("$name\n   expected: ${expected.replace("\n", "⏎")}\n   got:      ${got.replace("\n", "⏎")}")
            broken != null -> fail("$name: $broken")
            else -> { passed++; lines += "ok   $name" }
        }
    }

    private fun checkTrue(name: String, ok: Boolean) { if (ok) { passed++; lines += "ok   $name" } else fail(name) }

    /** Every paragraph under exactly one block span that covers exactly it; the text ending in its line break. */
    private fun brokenBlocks(): String? {
        val s: Spanned = text()
        if (s.isEmpty()) return if (s.getSpans(0, 0, BlockSpan::class.java).isEmpty()) null else "a block span on no text"
        if (s[s.length - 1] != '\n') return "the text does not end in a line break"
        val spans = s.getSpans(0, s.length, BlockSpan::class.java).sortedBy { s.getSpanStart(it) }
        var p = 0
        var i = 0
        while (p < s.length) {
            val nl = s.toString().indexOf('\n', p)
            val end = nl + 1
            val span = spans.getOrNull(i) ?: return "paragraph at $p has no block"
            if (s.getSpanStart(span) != p || s.getSpanEnd(span) != end) return "block $i is over ${s.getSpanStart(span)}..${s.getSpanEnd(span)}, its paragraph is $p..$end"
            p = end
            i++
        }
        return if (i == spans.size) null else "${spans.size - i} block span(s) with no paragraph"
    }

    // ── The cases ──────

    private fun run() {
        load("# T\n\npara **b** _i_\n\n- a\n- b\n")
        check("what is loaded is written back", "# T\n\npara **b** _i_\n\n- a\n- b\n")

        load("")
        type(0, "h")
        type(1, "i")
        check("typing into an empty document makes its first paragraph", "hi\n")
        checkTrue("the caret stays in front of the last line break", view.selectionEnd == 2)

        load("- a\n")
        type(at("a", after = true), "\n")
        type(view.selectionEnd, "b")
        check("Enter in a list item goes on as the list", "- a\n- b\n")

        load("1. a\n2. c\n")
        type(at("a", after = true), "\n")
        type(view.selectionEnd, "b")
        check("an item put in the middle of a numbered list renumbers what follows", "1. a\n2. b\n3. c\n")

        load("- a\n")
        type(at("a", after = true), "\n")
        type(view.selectionEnd, "\n")
        type(view.selectionEnd, "after")
        check("Enter on an empty item ends the list", "- a\n\nafter\n")

        load("- a\n  - b\n")
        type(at("b", after = true), "\n")
        type(view.selectionEnd, "\n")
        type(view.selectionEnd, "c")
        check("Enter on an empty nested item moves it out a level", "- a\n  - b\n- c\n")

        load("# Title\n")
        type(at("Title", after = true), "\n")
        type(view.selectionEnd, "body")
        check("Enter at the end of a heading starts a paragraph", "# Title\n\nbody\n")

        load("# Title\n")
        type(at("Title"), "\n")
        check("Enter at the start of a heading moves the heading down", "# Title\n")
        type(0, "above")
        check("and the line above it is a paragraph", "above\n\n# Title\n")

        load("# Title\n")
        type(at("tle"), "\n")
        check("Enter inside a heading leaves the rest as a paragraph", "# Ti\n\ntle\n")

        load("- [x] done\n")
        type(at("done", after = true), "\n")
        type(view.selectionEnd, "next")
        check("a task goes on as an unticked task", "- [x] done\n- [ ] next\n")

        load("one\n\n## two\n")
        delete(at("one", after = true), at("two"))
        check("a join keeps the block the words were joined to", "onetwo\n")

        load("# one\n\ntwo\n\n- three\n")
        delete(at("ne"), at("hree"))
        check("a delete across blocks leaves the first block", "# ohree\n")

        load("one\n\ntwo\n")
        view.setSelection(0, text().length)
        checkTrue("select-all stops in front of the last line break", view.selectionEnd == text().length - 1)
        delete(view.selectionStart, view.selectionEnd)
        check("deleting everything leaves an empty document", "")
        type(0, "x")
        check("which can be typed into", "x\n")

        load("a\n")
        type(at("a", after = true), "\nb\nc")
        check("pasted lines are paragraphs", "a\n\nb\n\nc\n")

        load("- a\n")
        type(at("a", after = true), "\nb\nc")
        check("lines pasted into a list are items", "- a\n- b\n- c\n")

        load("some words here\n")
        select("words")
        RichOps.toggleInline(view, RichStyle.BOLD)
        check("bold over a selection", "some **words** here\n")
        select("words")
        RichOps.toggleInline(view, RichStyle.ITALIC)
        check("and italic over the same", "some **_words_** here\n")
        select("or")
        RichOps.toggleInline(view, RichStyle.BOLD)
        check("bold off the middle of a bold run", "some **_w_**_or_**_ds_** here\n")

        load("some words here\n")
        view.setSelection(at("ords"))
        RichOps.toggleInline(view, RichStyle.STRIKE)
        check("with nothing selected a tool takes the word at the caret", "some ~~words~~ here\n")

        load("go\n")
        type(2, " ")
        view.setSelection(3)
        RichOps.toggleInline(view, RichStyle.BOLD)
        type(3, "n")
        type(4, "o")
        type(5, "w")
        check("with no word at the caret a tool styles what is typed next", "go **now**\n")
        view.setSelection(6)
        RichOps.toggleInline(view, RichStyle.BOLD)
        type(6, "!")
        check("and stops styling it when pressed again", "go **now**!\n")

        load("**bold** x\n")
        view.setSelection(at("ld"))
        RichOps.toggleInline(view, RichStyle.BOLD)
        check("a caret inside a styled word takes the style off the word", "bold x\n")

        load("**bold**\n")
        type(at("bold", after = true), "er")
        check("typing at the end of a styled run carries the style on", "**bolder**\n")
        type(0, "un")
        check("typing in front of it does not", "un**bolder**\n")

        load("one\n\ntwo\n")
        view.setSelection(1, at("two") + 1)
        RichOps.setBlock(view, RichKind.BULLET)
        check("a block tool takes every block the selection touches", "- one\n- two\n")
        RichOps.setBlock(view, RichKind.ORDERED)
        check("and one kind of list becomes another", "1. one\n2. two\n")
        RichOps.setBlock(view, RichKind.ORDERED)
        check("and pressed again gives paragraphs back", "one\n\ntwo\n")

        load("")
        RichOps.setBlock(view, RichKind.HEADING, 2)
        type(0, "T")
        check("a block tool on an empty document makes its first block", "## T\n")

        load("- a\n- b\n")
        view.setSelection(at("b"))
        RichOps.indent(view, 1)
        check("a list item moves in", "- a\n  - b\n")
        RichOps.indent(view, -1)
        check("and out", "- a\n- b\n")

        load("one\n\ntwo\n")
        view.setSelection(at("ne"))
        RichOps.insertRule(view)
        check("a rule goes under the block the caret is in", "one\n\n---\n\ntwo\n")
        checkTrue("and the caret goes on after it", view.selectionEnd == at("two"))

        load("one\n")
        view.setSelection(1)
        RichOps.insertRule(view)
        type(view.selectionEnd, "x")
        check("a rule at the end leaves a paragraph to go on in", "one\n\n---\n\nx\n")

        load("a\n\n---\n\nb\n")
        val rule = text().toString().indexOf('\u200B')
        type(rule, "x")
        check("a rule that is typed into is a paragraph", "a\n\nx\n\nb\n")

        load("a\n\n---\n\nb\n")
        val rule2 = text().toString().indexOf('\u200B')
        delete(rule2, rule2 + 1)
        check("a rule whose character is deleted is gone", "a\n\nb\n")

        load("see here\n")
        select("here")
        RichOps.setLink(view, "http://x/a_b")
        check("a link over a selection", "see [here](http://x/a_b)\n")
        view.setSelection(at("ere"))
        checkTrue("the link at the caret is found", RichOps.linkAt(view) == "http://x/a_b")
        RichOps.setLink(view, "")
        check("and taken off", "see here\n")

        load("a cat and a **cat**\n\n- cat\n")
        checkTrue("replace all answers its count", RichOps.replaceAll(view, "cat", "dog") == 3)
        check("replace all keeps every style and block", "a dog and a **dog**\n\n- dog\n")
        view.undo()
        check("and is one step to undo", "a cat and a **cat**\n\n- cat\n")
        view.redo()
        check("and to redo", "a dog and a **dog**\n\n- dog\n")

        load("one\n")
        type(3, "!")
        select("one")
        RichOps.toggleInline(view, RichStyle.BOLD)
        check("before undo", "**one**!\n")
        view.undo()
        check("undo takes a tool back", "one!\n")
        view.undo()
        check("and then the typing", "one\n")
        checkTrue("and then there is nothing left to undo", !view.undo())

        load("| a |\n|---|\n")
        type(at("| a |", after = true), "\n| b |")
        check("a raw line goes on as raw lines", "| a |\n| b |\n|---|\n")

        load("> quote\n")
        type(at("quote", after = true), "\n")
        type(view.selectionEnd, "\n")
        type(view.selectionEnd, "out")
        check("Enter on an empty quote line ends the quote", "> quote\n\nout\n")
    }

    private companion object {
        const val TAG = "DocsproutSelfTest"
    }
}
