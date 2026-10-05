package com.symmetricalpalmtree.soil.docsprout.editor

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.docsprout.DocsproutApp
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.markdown.MarkdownReflow
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.ink.InkWire
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerCallException
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerPort
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerReadiness
import com.symmetricalpalmtree.soil.paper.recognition.RecognizingOverlay
import com.symmetricalpalmtree.soil.seam.ISoilSeam
import com.symmetricalpalmtree.soil.seam.SeamClip
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.SeamUnavailable
import com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.seamkit.clip.InkClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * **Ink on the clipboard, pasted into a document as words** (decision 2026-10-04). The library
 * has one clipboard for ink: what the Scratch Pad copied, or a lasso in a notebook. A notebook
 * pastes it as ink; a document has it read, by the recogniser Soil relays to and behind the same
 * consent flow a notebook's recognition uses, and puts the words in at the cursor.
 *
 * - **It pastes as often as it is asked.** The clipboard is not taken, and is replaced only by
 *   the next copy or by Clear clipboard.
 * - **The words are read once.** They are kept beside the ink, in a slot of their own that names
 *   the ink they belong to by its copy time, so a second paste, in this document or another, or
 *   after a restart, does not read again. Words kept for other ink are stale and are not used.
 * - **Ctrl+V pastes whichever was copied last**, this ink or the text on the device's own
 *   clipboard ([newerThanText]); the Paste tool on the bar always means this ink. Clear
 *   clipboard empties both, so that nothing is left to paste.
 *
 * When the words cannot be had (no recogniser, the model not there yet, nothing legible), the
 * person is told and nothing changes: the ink is still on the clipboard. Nothing read is logged.
 */
internal class InkPaste(
    private val activity: AppCompatActivity,
    private val usable: () -> Boolean,
    /** The words to put in at the cursor: one string a paragraph. */
    private val insert: (paragraphs: List<String>) -> Unit,
) {

    private var busy = false

    /** When the ink on the clipboard was copied, as last looked; null for none. Main only. */
    private var copiedAt: Long? = null

    private val port = object : RecognizerPort {
        override suspend fun status(): Int = call { it.recognizerStatus() }
        override suspend fun prepare() = call { it.prepareRecognizer() }
    }

    /** Look again at what the clipboard holds: whenever the screen comes to the front, since the
     *  pad or a notebook may have copied meanwhile. */
    fun refresh() {
        activity.lifecycleScope.launch {
            copiedAt = withContext(Dispatchers.IO) { runCatching { seam().clipHeader(InkClip.SLOT)?.copiedAt }.getOrNull() }
        }
    }

    /**
     * Whether the ink was copied after the text on the device's own clipboard: what Ctrl+V and
     * the text menu's Paste ask, so that they paste the last thing copied, whichever it was.
     * With nothing on the device's clipboard, ink is the last thing copied.
     */
    fun newerThanText(): Boolean {
        val ink = copiedAt ?: return false
        val description = runCatching { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClipDescription }.getOrNull()
        return description == null || ink > description.timestamp
    }

    /** The Paste tool: what the clipboard holds, as a choice of paste it or clear it. Empty, it says so. */
    fun prompt() {
        if (!usable() || busy) return
        if (copiedAt == null) {
            Dialogs.problem(activity, R.string.ink_paste_title, R.string.ink_paste_empty_body)
            return
        }
        ActionSheetDialog(activity).title(activity.getString(R.string.ink_paste_title))
            .addAction(PaperR.drawable.ic_clipboard, activity.getString(R.string.ink_paste_action)) { paste() }
            .addAction(PaperR.drawable.ic_trash, activity.getString(R.string.ink_clear_action)) { clear() }
            .show()
    }

    /** Put the clipboard's handwriting in at the cursor, as words. */
    fun paste() {
        if (!usable() || busy) return
        busy = true
        activity.lifecycleScope.launch {
            val clip = withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
            if (clip == null) {
                busy = false
                copiedAt = null
                Dialogs.problem(activity, R.string.ink_paste_title, R.string.ink_paste_empty_body)
                return@launch
            }
            copiedAt = clip.copiedAt
            if (clip.words != null) {
                busy = false
                deliver(clip.words)
                return@launch
            }
            if (clip.strokes.isEmpty()) {
                busy = false
                Dialogs.problem(activity, R.string.ink_paste_title, R.string.ink_paste_no_ink_body)
                return@launch
            }
            RecognizerReadiness.ensureReady(
                activity, port,
                onReady = { try { recognise(clip) } finally { busy = false } },
                onGaveUp = { busy = false },
            )
        }
    }

    /**
     * Clear clipboard means there is nothing left to paste (decision 2026-10-04): the library's
     * ink, the words kept for it, and the text on the device's own clipboard, which Ctrl+V would
     * otherwise go on pasting.
     */
    private fun clear() {
        copiedAt = null
        runCatching { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.clearPrimaryClip() }
            .onFailure { Log.w(TAG, "the device's clipboard was not cleared: ${it.javaClass.simpleName}") }
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { seam().clearClip(InkClip.SLOT) }
                runCatching { seam().clearClip(InkClip.WORDS_SLOT) }
            }
        }
    }

    // ── The clipboard ──────

    /** The ink, and its words when they have been read before. */
    private class Clip(val copiedAt: Long, val strokes: List<Stroke>, val words: List<String>?)

    private suspend fun seam(): ISoilSeam = (activity.application as DocsproutApp).soil.seam()

    /** What the clipboard holds, or null for nothing. Blocking: IO. */
    private suspend fun read(): Clip? {
        val seam = seam()
        val header = seam.clipHeader(InkClip.SLOT) ?: return null
        val kept = seam.clipHeader(InkClip.WORDS_SLOT)?.takeIf { it.copiedAt == header.copiedAt && it.payloadKind == InkClip.WORDS_KIND }
        if (kept != null) {
            val words = seam.clip(InkClip.WORDS_SLOT)?.let { String(SeamShared.readAndClose(it), Charsets.UTF_8) }?.let(::linesOf)
            if (!words.isNullOrEmpty()) return Clip(header.copiedAt, emptyList(), words)
        }
        val envelope = ClipEnvelope.decode(seam.clip(InkClip.SLOT)?.let { SeamShared.readAndClose(it) }) ?: return null
        return Clip(header.copiedAt, InkClip.strokesOf(envelope), null)
    }

    private suspend fun recognise(clip: Clip) {
        if (activity.isFinishing || activity.isDestroyed) return
        RecognizingOverlay.show(activity)
        try {
            val started = System.currentTimeMillis()
            // The recogniser reads the area as the scale of the writing: the ink's own extent.
            var right = 1f
            var bottom = 1f
            for (s in clip.strokes) { right = maxOf(right, s.bounds.right); bottom = maxOf(bottom, s.bounds.bottom) }
            val raw = call { it.recognizePage(SeamShared.write(InkWire.encode(clip.strokes, right, bottom)), right, bottom) }
            val paragraphs = paragraphsOf(raw)
            Slog.d(TAG) { "read ${clip.strokes.size} strokes into ${paragraphs.size} paragraph(s) in ${System.currentTimeMillis() - started} ms" }
            RecognizingOverlay.hide(activity)
            if (paragraphs.isEmpty()) {
                if (!activity.isFinishing && !activity.isDestroyed) Dialogs.problem(activity, PaperR.string.recognize_problem_title, PaperR.string.recognize_nothing)
                return
            }
            // Kept beside the ink for the next paste. Never worth failing this one for.
            withContext(Dispatchers.IO) {
                runCatching {
                    seam().putClip(InkClip.WORDS_SLOT, SeamClip(InkClip.WORDS_KIND, "", clip.copiedAt), SeamShared.write(paragraphs.joinToString("\n").toByteArray(Charsets.UTF_8)))
                }.onFailure { Log.w(TAG, "the words were not kept: ${it.javaClass.simpleName}") }
            }
            deliver(paragraphs)
        } catch (e: RecognizerCallException) {
            Log.w(TAG, "the clipboard's ink was not read")
            RecognizingOverlay.hide(activity)
            val body = when {
                e.tooLarge -> PaperR.string.recognize_too_dense
                e.notReady -> PaperR.string.recognize_still_downloading
                e.noRecognizer -> PaperR.string.recognize_no_recognizer
                else -> PaperR.string.recognize_failed
            }
            if (!activity.isFinishing && !activity.isDestroyed) Dialogs.problem(activity, PaperR.string.recognize_problem_title, body)
        } finally {
            RecognizingOverlay.hide(activity)
        }
    }

    private fun deliver(paragraphs: List<String>) {
        if (activity.isFinishing || activity.isDestroyed || !usable()) return
        insert(paragraphs)
    }

    private suspend fun <T> call(block: (ISoilSeam) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(seam())
        } catch (e: SeamUnavailable) {
            throw RecognizerCallException(SeamLimits.RECOGNITION_FAILED, e)
        } catch (e: IllegalStateException) {
            throw RecognizerCallException(e.message ?: SeamLimits.RECOGNITION_FAILED, e)
        } catch (e: IllegalArgumentException) {
            throw RecognizerCallException(SeamLimits.INK_TOO_LARGE, e)
        } catch (e: SecurityException) {
            throw RecognizerCallException(SeamLimits.RECOGNITION_FAILED, e)
        } catch (e: android.os.RemoteException) {
            throw RecognizerCallException(SeamLimits.RECOGNITION_FAILED, e)
        }
    }

    companion object {
        private const val TAG = "InkPaste"
        private val HORIZONTAL = Regex("[^\\S\\n]+")

        /**
         * What the recogniser read, as paragraphs. It answers a line for each line of
         * handwriting, and a hand wraps where the page ends, not where the sentence does: the
         * lines of a paragraph are joined ([MarkdownReflow], which leaves a line that looks like
         * a list item or a heading on its own), and each line left after that is a paragraph.
         * Pure.
         */
        fun paragraphsOf(raw: String): List<String> {
            val lines = raw.split('\n').joinToString("\n") { it.replace(HORIZONTAL, " ").trim() }
            return linesOf(MarkdownReflow.reflow(lines))
        }

        /** The kept words as they were kept: a paragraph a line. Pure. */
        fun linesOf(text: String): List<String> = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
