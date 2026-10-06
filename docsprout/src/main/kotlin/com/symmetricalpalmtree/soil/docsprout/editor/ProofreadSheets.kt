package com.symmetricalpalmtree.soil.docsprout.editor

import android.app.Activity
import android.util.Log
import android.widget.Toast
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * Everything the writer taps in proofread — the feature's own sheet, the two flag
 * popups, and the dictionary manager.
 *
 * They live together, and outside [ProofreadController], because the pass and the chrome over it are
 * two different jobs. Every row here ends in one call back into the controller, which is what
 * actually changes the text or the stored state.
 *
 * **GONE, never greyed:** when proofread is off the sheet offers only *Turn on* — a disabled control
 * is visually silent on e-ink, and turning the feature on checks the document anyway.
 *
 * **Nothing here logs.** The only things it could report are a word and a line of the document.
 */
internal class ProofreadSheets(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val controller: ProofreadController,
) {

    /** The feature's sheet: an on-demand full pass, the user dictionary, and the on/off switch. */
    fun promptProofread() {
        val sheet = ActionSheetDialog(activity).title(activity.getString(R.string.proofread_title))
        if (controller.enabled) {
            sheet.addAction(R.drawable.ic_text_spellcheck, activity.getString(R.string.proofread_check)) {
                // The pass is not lost — the load completes into exactly it — but on e-ink a tap
                // that paints nothing reads as broken, so the wait is stated rather than toasted.
                if (!controller.ready) {
                    Dialogs.problem(
                        activity, R.string.proofread_loading_title, R.string.proofread_loading_body,
                    )
                }
                controller.checkDocument()
            }
            sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_book, activity.getString(R.string.proofread_dictionary)) {
                promptUserDictionary()
            }
            sheet.addAction(PaperR.drawable.ic_eye_off, activity.getString(R.string.proofread_off)) {
                controller.setEnabled(false)
            }
        } else {
            sheet.addAction(PaperR.drawable.ic_eye, activity.getString(R.string.proofread_on)) {
                controller.setEnabled(true)
            }
        }
        sheet.show()
    }

    /** A flagged word: what to put in its place, and the two ways to stop being told about it. */
    fun showSpelling(span: ProofreadFlagSpan, word: String, suggestions: List<String>) {
        val sheet = ActionSheetDialog(activity)
        sheet.title(
            when {
                // Read at show time — an index that finished during the lookup stops apologizing.
                suggestions.isEmpty() && !controller.suggestionsReady ->
                    activity.getString(R.string.proofread_word_loading, word)

                suggestions.isEmpty() -> activity.getString(R.string.proofread_word_none, word)
                else -> activity.getString(R.string.proofread_word, word)
            }
        )
        for (suggestion in suggestions) {
            sheet.addAction(null, suggestion) { controller.replaceFlag(span, suggestion) }
        }
        sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_book, activity.getString(R.string.proofread_add)) {
            controller.addToDictionary(word)
        }
        sheet.addAction(PaperR.drawable.ic_eye_off, activity.getString(R.string.proofread_ignore)) {
            controller.ignoreWord(word)
        }
        sheet.show()
    }

    /**
     * A grammar finding: what the rule saw, its one-tap fix when it has one, and a session-scoped
     * mute. The caller has already checked that the span's text still reads as it was flagged.
     */
    fun showGrammar(span: GrammarFlagSpan) {
        val sheet = ActionSheetDialog(activity)
        sheet.title(activity.getString(R.string.proofread_grammar_title, span.message, span.snippet))
        span.replacement?.let { fix ->
            sheet.addAction(PaperR.drawable.ic_check, activity.getString(R.string.proofread_fix, fix)) {
                controller.replaceFlag(span, fix)
            }
        }
        sheet.addAction(PaperR.drawable.ic_eye_off, activity.getString(R.string.proofread_ignore)) {
            controller.muteGrammar(span)
        }
        sheet.show()
    }

    /**
     * The minimal dictionary manager: every saved word, tap one to remove it.
     *
     * The store is read **fresh** rather than from the controller's mirror — that set only exists
     * once the engine has loaded, and this list must be truthful even before then. Both of the
     * nothing-to-show outcomes are dialogs: each explains why a tap produced no list.
     */
    fun promptUserDictionary() {
        scope.launch {
            val words = try {
                withContext(Dispatchers.IO) { controller.store.userWords() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "user dictionary unavailable", e)
                Dialogs.problem(
                    activity, R.string.proofread_dict_unavailable_title,
                    R.string.proofread_dict_unavailable_body,
                )
                return@launch
            }
            if (words.isEmpty()) {
                Dialogs.problem(
                    activity, R.string.proofread_dict_empty_title, R.string.proofread_dict_empty_body,
                )
                return@launch
            }
            val sheet = ActionSheetDialog(activity)
            sheet.title(activity.getString(R.string.proofread_dict_title))
            for (word in words) {
                sheet.addAction(PaperR.drawable.ic_trash, word) { removed(word) }
            }
            sheet.show()
        }
    }

    /** A toast, and one of the sanctioned ones: it confirms something that already happened. */
    private fun removed(word: String) {
        controller.removeFromDictionary(word)
        Toast.makeText(
            activity, activity.getString(R.string.proofread_removed, word), Toast.LENGTH_SHORT,
        ).show()
    }

    private companion object {
        const val TAG = "Proofread"
    }
}
