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
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.docsprout.editor.ProofreadController
import com.symmetricalpalmtree.soil.docsprout.editor.ProofreadFlagSpan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.symmetricalpalmtree.soil.docsprout.editor.rich.BlockSpan
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichCodec
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
        if (intent.getBooleanExtra("pdf", false)) {
            report.text = runCatching { pdfProbe() }.getOrElse { "pdf probe failed: ${it.javaClass.simpleName}" }
            Log.i(TAG, "PDF ${report.text}")
            return
        }
        view.post {
            runCatching { run() }.onFailure { fail("the run threw ${it.javaClass.simpleName} at ${it.stackTrace.firstOrNull { e -> e.className.contains("docsprout") }}") }
            lifecycleScope.launch {
                runCatching { proofread() }.onFailure { fail("proofread threw ${it.javaClass.simpleName}") }
                finish(report)
            }
        }
    }

    /**
     * Proofread over the rendered document, with the dictionary this APK ships: misspelled prose
     * is flagged, and the same letters in code and in a raw line are not. It reads this device's
     * on/off switch and writes nothing; switched off, it says so and checks nothing.
     */
    private suspend fun proofread() {
        val controller = ProofreadController.install(this, listOf(view), { view }, lifecycleScope)
        load("Ths is a tset of it.\n\nAnd `tset` in code.\n\n| tset |\n")
        controller.checkDocument()
        var waited = 0
        while (waited < 40_000 && (!controller.ready || text().getSpans(0, text().length, ProofreadFlagSpan::class.java).isEmpty())) {
            if (!controller.enabled && waited > 1_000) break
            delay(250)
            waited += 250
        }
        if (!controller.enabled) {
            lines += "skip proofread is switched off on this device: not checked"
            return
        }
        // The pass may still be landing its last flags.
        delay(500)
        val flagged = text().getSpans(0, text().length, ProofreadFlagSpan::class.java).map { text().subSequence(text().getSpanStart(it), text().getSpanEnd(it)).toString() }.sorted()
        checkTrue("proofread flags misspelled prose and leaves code and raw lines alone (after ${waited} ms)", flagged == listOf("Ths", "tset"))
    }

    private fun finish(report: TextView) {
        val summary = "RESULT passed=$passed failed=$failed"
        lines.add(0, summary)
        Log.i(TAG, summary)
        report.text = lines.joinToString("\n")
        // Left showing something a person can look at: every kind of block there is.
        load("# Heading one\n\n## Heading two\n\nA paragraph with **bold**, _italic_, ~~struck~~, `code` and a [link](http://example.com), long enough to wrap onto a second line of the page so the wrapping can be seen.\n\n- a bullet\n  - nested\n    - and again\n- [ ] a task\n- [x] a done task\n1. one\n2. two\n\n> A quote, which also runs long enough to wrap so that its stripe can be seen beside both of its lines.\n\n---\n\n| a | table |\n|---|---|\n\nThe end, with a mispeled word and and a repeat.")
    }

    /**
     * The export's layout, written to this app's cache for the Mac to pull and read: a document
     * of many paragraphs laid out at each page size, as a PDF of text and as its first page's
     * picture. It opens no document of the library's.
     */
    private fun pdfProbe(): String {
        val words = StringBuilder("# Export probe\n\n")
        for (n in 1..40) words.append("Paragraph $n with **bold**, _italic_ and a [link](http://example.com), long enough to wrap onto a second line of the page so that the cut between pages falls on a line.\n\n")
        words.append("- a bullet\n  - nested\n- [x] a done task\n1. one\n2. two\n\n> A quote.\n\n---\n\nΕλληνικά, עברית, and the last line.")
        val doc = RichParse.parse(words.toString()).doc
        val out = StringBuilder()
        for (size in listOf("letter", "a4", "screen")) {
            val layout = com.symmetricalpalmtree.soil.docsprout.export.PageLayout(doc, com.symmetricalpalmtree.soil.docsprout.export.PageSpec.of(this, size, 16f))
            java.io.File(cacheDir, "probe_$size.pdf").outputStream().use { layout.writePdf(it) }
            java.io.File(cacheDir, "probe_$size.png").writeBytes(layout.png(0))
            out.append("$size: ${layout.pages.size} pages, picture ${layout.spec.widthPx}x${layout.spec.heightPx}; ")
        }
        return out.toString()
    }

    // ── The harness ──────

    private fun load(markdown: String) = view.load(RichParse.parse(markdown).doc)
    private fun markdown(): String = RichWrite.write(view.document()).text
    private fun text() = view.text!!
    private fun at(needle: String, after: Boolean = false): Int = text().toString().indexOf(needle).also { require(it >= 0) { "no '$needle'" } } + if (after) needle.length else 0
    private fun type(at: Int, words: String) { view.setSelection(at); text().insert(at, words) }
    private fun delete(from: Int, to: Int) { text().delete(from, to) }
    /** One character after another at the caret, as a keyboard sends them. */
    private fun keys(words: String) { for (c in words) text().insert(view.selectionEnd, c.toString()) }
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
        val rule = text().toString().indexOf(RichCodec.RULE_CHAR)
        type(rule, "x")
        check("a rule that is typed into is a paragraph", "a\n\nx\n\nb\n")

        load("a\n\n---\n\nb\n")
        val rule2 = text().toString().indexOf(RichCodec.RULE_CHAR)
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

        load("go\n")
        type(2, " ")
        view.setSelection(3)
        RichOps.setLink(view, "soil:22222222-2222-2222-2222-222222222222", "My Notebook")
        check("a link with nothing to carry it is carried by the words given", "go [My Notebook](soil:22222222-2222-2222-2222-222222222222)\n")
        val linked = at("My Notebook")
        checkTrue("the link is found under each of its characters", view.linkAtChar(linked) != null && view.linkAtChar(linked + 10) != null)
        checkTrue("and not under the characters beside it", view.linkAtChar(linked - 1) == null && view.linkAtChar(linked + 11) == null)
        checkTrue("and says where it stands", view.linkAtChar(linked)?.let { view.rangeOf(it) } == (linked to linked + 11))

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

        rawBlocks()
        inserts()
        typeToFormat()
        bibleLinks()
    }

    /** Fences of every kind and indented code read and are written as they were. */
    private fun rawBlocks() {
        for (md in listOf("~~~\n**x**\n```\n~~~\n\nafter\n", "````md\n```\n````\n", "a\n\n    code *x*\n\n    more\n\nb\n")) {
            load(md)
            check("raw blocks are written back as they were read: ${md.lineSequence().first()}", md)
        }

        load("| a |\n")
        delete(0, 1)
        check("a raw line edited out of its shape is written as its words", "a |\n")

        load("```\na\u200Bb\n```\n")
        check("a zero-width space in a raw line is kept", "```\na\u200Bb\n```\n")

        load("a\n\n---\n\nb\n")
        checkTrue("a rule holds its stand-in character", text().toString().indexOf(RichCodec.RULE_CHAR) >= 0)
    }

    /** Words put in by a tool are plain, and a link put over them is the only one there. */
    private fun inserts() {
        load("**bold**\n")
        view.setSelection(at("bold", after = true))
        RichOps.insertText(view, " plain", 6, 6)
        check("words put in at the end of a style do not take it", "**bold** plain\n")

        load("**ab**\n")
        view.setSelection(at("b"))
        RichOps.insertText(view, "x\ny", 3, 3)
        check("lines put in inside a style leave it on both sides and off the new lines", "**a**x\n\ny**b**\n")

        load("[a](u)\n")
        val end = at("a", after = true)
        view.setSelection(end)
        RichOps.insertText(view, "J", 1, 1)
        RichOps.linkOver(text(), end, end + 1, "v")
        check("a link put in beside another is its own", "[a](u)[J](v)\n")
    }

    /** The reference pass on the rendered surface: a span over the words, the caret untouched, one step to undo. */
    private fun bibleLinks() {
        load("see John 3:16 today\n")
        view.setSelection(0)
        val text = view.text!!.toString()
        val plan = com.symmetricalpalmtree.soil.docsprout.editor.bible.ReferenceLinker.plan(
            text, com.symmetricalpalmtree.soil.docsprout.editor.proofread.ProofreadCheck.Region(0, text.length), BooleanArray(text.length), emptySet(), 0,
        )
        com.symmetricalpalmtree.soil.docsprout.editor.bible.BibleLinkController.applyRendered(view, plan.hits)
        check("a reference found by the pass is a link over its words", "see [John 3:16](bible:JHN:3:16-3:16) today\n")
        if (view.selectionStart != 0) fail("the caret moved to ${view.selectionStart}")
        var removed: Set<Pair<String, String>> = emptySet()
        var added: Set<Pair<String, String>> = emptySet()
        var restored = 0
        view.onRestored = { r, a -> removed = r; added = a; restored++ }
        view.undo()
        check("one undo takes the link off and keeps the words", "see John 3:16 today\n")
        checkTrue("the undo says which link it took off, so the pass skips it", removed == setOf("John 3:16" to "bible:JHN:3:16-3:16") && added.isEmpty())
        view.redo()
        checkTrue("a redo says which link it put back, so the pass allows it again", added == setOf("John 3:16" to "bible:JHN:3:16-3:16") && removed.isEmpty())

        load("see John 3:16\n")
        type(at("16", after = true), " x")
        view.undo()
        val again = view.text!!.toString()
        val relinked = com.symmetricalpalmtree.soil.docsprout.editor.bible.ReferenceLinker.plan(
            again, com.symmetricalpalmtree.soil.docsprout.editor.proofread.ProofreadCheck.Region(0, again.length), BooleanArray(again.length), emptySet(), null,
        )
        com.symmetricalpalmtree.soil.docsprout.editor.bible.BibleLinkController.applyRendered(view, relinked.hits)
        val before = restored
        checkTrue("a link the pass makes after an undo leaves the undone typing to redo", view.redo())
        check("and the redo puts it back", "see John 3:16 x\n")
        checkTrue("and the redo asks for a read, so the pass links the reference again", restored == before + 1)
        view.onRestored = null
    }

    private fun typeToFormat() {
        load("")
        keys("# Title")
        check("a typed heading marker makes a heading", "# Title\n")
        keys("\n- one\ntwo\n\n1. first\nsecond")
        check("typed list markers make lists that go on", "# Title\n\n- one\n- two\n1. first\n2. second\n")

        load("")
        keys("- [ ] todo")
        check("a typed box makes a task", "- [ ] todo\n")

        load("")
        keys("> said")
        check("a typed quote marker makes a quote", "> said\n")

        load("words\n")
        view.setSelection(0)
        keys("## ")
        check("a marker typed in front of words makes them the block", "## words\n")

        load("")
        keys("a **bold** b")
        check("a typed bold pair makes bold, and what follows is not", "a **bold** b\n")
        checkTrue("and no marker is left in the text", !text().toString().contains('*'))

        load("")
        keys("an *it* and _it_ and ~~gone~~ and `code` end")
        check("typed pairs of every kind", "an _it_ and _it_ and ~~gone~~ and `code` end\n")

        load("")
        keys("2 * 3 * 4 and snake_case_word")
        check("what only looks like a pair stays as typed", "2 \\* 3 * 4 and snake\\_case_word\n")

        load("`code`\n")
        view.setSelection(at("de"))
        keys("*x*")
        check("a pair typed into code is characters", "`co*x*de`\n")

        load("")
        keys("# T")
        view.undo()
        view.undo()
        checkTrue("one undo takes the typing after the marker, the next puts the marker's characters back", text().toString() == "# \n" && markdown() == "#\n")

        load("")
        keys("**b**")
        view.undo()
        checkTrue("undo puts a pair's markers back", text().toString() == "**b**\n")

        load("")
        keys("a `code`\nnext\nlast")
        check("a line after a typed code pair is not code", "a `code`\n\nnext\n\nlast\n")

        load("")
        keys("a `code` more\nnext")
        check("nor after words that follow the pair", "a `code` more\n\nnext\n")

        load("")
        keys("a **bold**\nnext")
        check("a line after a typed bold pair is not bold", "a **bold**\n\nnext\n")

        load("a `code`\n")
        view.setSelection(at("code", after = true))
        keys("\nnext")
        check("Enter at the end of a code run does not carry code to the next line", "a `code`\n\nnext\n")

        load("a **bold** b\n")
        view.setSelection(at("ld"))
        keys("\n")
        check("Enter inside a styled run leaves both halves styled", "a **bo**\n\n**ld** b\n")

        load("")
        keys("a `code`")
        view.setSelection(0)
        view.setSelection(text().length - 1)
        keys(" x\nnext")
        check("a typed pair stays closed after the caret has been away and back", "a `code` x\n\nnext\n")

        load("| a |\n")
        view.setSelection(0)
        keys("# ")
        // Never converted, and, no longer a table row, written as its words.
        check("a raw line is never converted", "\\# | a |\n")

        load("`x *y` z\n")
        view.setSelection(at("z", after = true))
        keys("*")
        check("a marker inside code does not open a pair", "`x *y` z*\n")

        load("[a *b](u) c\n")
        view.setSelection(at("c", after = true))
        keys("*")
        check("nor one inside a link's words", "[a *b](u) c*\n")
    }

    private companion object {
        const val TAG = "DocsproutSelfTest"
    }
}
