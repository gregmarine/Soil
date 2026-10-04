package com.symmetricalpalmtree.soil.docsprout.editor

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.docsprout.databinding.ActivityDocumentBinding
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichOps
import com.symmetricalpalmtree.soil.markdown.TextSearch

/**
 * The find-and-replace bar: two rows, `[find][n of m][‹][›][✕]` over
 * `[replace][Replace][All]`, wired to the shared engine's [TextSearch].
 *
 * It lives in its own file rather than in the Activity for the reason the module's size rule
 * exists — the editor screen is already the biggest thing here, and this is a self-contained piece
 * of chrome with its own state (a query, a replacement, a count).
 *
 * Two decisions, both about honesty on e-ink:
 *
 * - **There are no highlight spans.** The current match *is* the editor's selection, which the
 *   platform already paints and which e-ink renders as a real inversion. A custom span would be a
 *   second kind of highlight that has to be kept in step with the first.
 * - **Matches are recomputed per action**, never painted live. A find bar that repainted the whole
 *   document on every keystroke would ghost, and the count is the only live thing worth having.
 *
 * Replaces go through the `Editable`, so undo takes them back, and *replace all* is exactly one
 * step in either surface, so one Ctrl+Z brings the whole document back.
 *
 * **The query is user content and is never logged**, on any path.
 */
internal class FindReplaceBar(
    private val context: Context,
    private val binding: ActivityDocumentBinding,
    /** The surface in use: the rendered document or the Markdown source. */
    private val editor: () -> EditText,
    private val rendered: () -> Boolean,
    private val keepCaretVisible: () -> Unit,
    /** A replace-all is a deliberate act on the whole document — it goes down now, not in two
     *  seconds' time. */
    private val onReplacedAll: () -> Unit,
) {

    /** Listeners and hints. Called once, from the Activity's chrome build. */
    fun install() {
        binding.btnFindPrev.setOnClickListener { step(backwards = true) }
        binding.btnFindNext.setOnClickListener { step(backwards = false) }
        binding.btnFindClose.setOnClickListener { close() }
        binding.btnReplace.setOnClickListener { replaceCurrent() }
        binding.btnReplaceAll.setOnClickListener { replaceAll() }
        for (button in listOf(
            binding.btnFindPrev, binding.btnFindNext, binding.btnFindClose,
            binding.btnReplace, binding.btnReplaceAll,
        )) {
            TooltipCompat.setTooltipText(button, button.contentDescription)
        }

        // The count is the one live thing: it says whether the query is worth stepping through
        // before the writer has stepped anywhere.
        binding.findField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateCount()
        })
        // Enter finds the next match from the find field, and replaces from the replace field —
        // the whole bar drivable without leaving the keyboard.
        binding.findField.setOnEditorActionListener { _, _, _ -> step(backwards = false); true }
        binding.replaceField.setOnEditorActionListener { _, _, _ -> replaceCurrent(); true }
    }

    fun isOpen(): Boolean = binding.findBar.visibility == View.VISIBLE

    /** Show the bar and take the focus. Seeded from the selection when it looks like a query. */
    fun open() {
        binding.findBar.visibility = View.VISIBLE
        val text = editor().text
        val start = editor().selectionStart
        val end = editor().selectionEnd
        if (text != null && end > start) {
            // A short single-line selection is almost always the thing being searched for.
            val selection = text.subSequence(start, end).toString()
            if (selection.isNotBlank() && !selection.contains('\n') && selection.length <= MAX_SEED_CHARS) {
                binding.findField.setText(selection)
            }
        }
        binding.findField.requestFocus()
        binding.findField.setSelection(binding.findField.text?.length ?: 0)
        updateCount()
    }

    /** Put the bar away and give the caret back to the document. */
    fun close() {
        binding.findBar.visibility = View.GONE
        editor().requestFocus()
    }

    /** Move the selection to the next / previous match — wrapping, and scrolled into view. */
    fun step(backwards: Boolean) {
        val matches = currentMatches()
        if (matches.isEmpty()) {
            updateCount()
            // Silent on an empty query: nothing was asked for, so nothing failed.
            if (query().isNotEmpty()) toast(context.getString(R.string.find_no_matches))
            return
        }
        // selectionEnd forward / selectionStart backward is what makes repeated steps advance when
        // the current selection IS a match.
        val index = if (backwards) {
            TextSearch.previousFrom(matches, editor().selectionStart.coerceAtLeast(0))
        } else {
            TextSearch.nextFrom(matches, editor().selectionEnd.coerceAtLeast(0))
        }
        val match = matches[index]
        editor().requestFocus()
        editor().setSelection(match.start, match.end)
        keepCaretVisible()
        binding.findCount.text = context.getString(R.string.find_count, index + 1, matches.size)
    }

    /** Replace the selection when it is a match (case-insensitively), then step to the next. */
    fun replaceCurrent() {
        val text = editor().text ?: return
        val query = query()
        if (query.isEmpty()) return
        val start = editor().selectionStart
        val end = editor().selectionEnd
        if (end > start && text.subSequence(start, end).toString().equals(query, ignoreCase = true)) {
            val replacement = replacement()
            text.replace(start, end, replacement)
            editor().setSelection((start + replacement.length).coerceAtMost(text.length))
        }
        step(backwards = false)
    }

    /** Replace every match in one `Editable` edit — one Ctrl+Z brings it all back. Returns how
     *  many were replaced. */
    fun replaceAll(): Int {
        val text = editor().text ?: return 0
        val query = query()
        if (query.isEmpty()) return 0
        val count = if (rendered()) {
            // Match by match, so every style and block around the matches stays as it is.
            RichOps.replaceAll(binding.rich, query, replacement())
        } else {
            val result = TextSearch.replaceAll(text.toString(), query, replacement(), editor().selectionEnd.coerceAtLeast(0))
            if (result.count > 0) {
                text.replace(0, text.length, result.text)
                editor().setSelection(result.caret.coerceIn(0, text.length))
            }
            result.count
        }
        if (count == 0) {
            toast(context.getString(R.string.find_no_matches))
            return 0
        }
        updateCount()
        toast(context.getString(R.string.find_replaced, count))
        onReplacedAll()
        return count
    }

    /** The count field as it reads now — `""`, a bare total, or `n of m`. */
    fun countLabel(): String = binding.findCount.text?.toString().orEmpty()

    fun matchCount(): Int = currentMatches().size

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun query(): String = binding.findField.text?.toString().orEmpty()

    private fun replacement(): String = binding.replaceField.text?.toString().orEmpty()

    private fun currentMatches(): List<TextSearch.Match> =
        TextSearch.matches(editor().text?.toString().orEmpty(), query())

    private fun updateCount() {
        val query = query()
        binding.findCount.text = if (query.isEmpty()) "" else currentMatches().size.toString()
    }

    private fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private companion object {
        /** Longer than this, or spanning a line, and the selection is a passage rather than a
         *  query — seeding the field with it would be a worse guess than leaving it alone. */
        const val MAX_SEED_CHARS = 64
    }
}
