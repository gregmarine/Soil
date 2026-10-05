package com.symmetricalpalmtree.soil.docsprout.editor

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.docsprout.DocsproutApp
import com.symmetricalpalmtree.soil.markdown.MarkdownReflow
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.ink.InkWire
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerCallException
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerPort
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerReadiness
import com.symmetricalpalmtree.soil.paper.recognition.RecognizingOverlay
import com.symmetricalpalmtree.soil.seam.ISoilSeam
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.SeamUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * **What the Scratch Pad sent, as words in the document.** The pad sends ink, always; a notebook
 * takes it as ink, and a document has it read. As the document comes back to the front it asks
 * Soil for anything sent, has the recogniser Soil relays to read it as a page (behind the same
 * consent flow a notebook's recognition uses), and hands the words to the screen to put in at
 * the cursor.
 *
 * The pad keeps its ink: Send is a copy. So when the words cannot be had (no recogniser, the
 * model not there yet, nothing legible), the person is told, nothing in the document changes,
 * and what they wrote is still on the pad. Nothing recognised is ever logged.
 */
internal class PadWords(
    private val activity: AppCompatActivity,
    private val usable: () -> Boolean,
    /** The words to put in at the cursor: one string a paragraph. */
    private val insert: (paragraphs: List<String>) -> Unit,
) {

    private var busy = false

    private val port = object : RecognizerPort {
        override suspend fun status(): Int = call { it.recognizerStatus() }
        override suspend fun prepare() = call { it.prepareRecognizer() }
    }

    /** Ask Soil for anything the pad sent, and read it. Nothing sent is the usual answer. */
    fun takeIfSent() {
        if (!usable() || busy) return
        busy = true
        activity.lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { (activity.application as DocsproutApp).soil.seam().takeIncomingInk()?.let { SeamShared.readAndClose(it) } }.getOrNull()
            }
            if (bytes == null) { busy = false; return@launch }
            val bundle = InkWire.decode(bytes)
            if (bundle == null || bundle.strokes.none { it.points.isNotEmpty() }) {
                busy = false
                Dialogs.problem(activity, PaperR.string.recognize_problem_title, PaperR.string.recognize_nothing)
                return@launch
            }
            RecognizerReadiness.ensureReady(
                activity, port,
                onReady = { try { read(bundle) } finally { busy = false } },
                onGaveUp = { busy = false },
            )
        }
    }

    private suspend fun read(bundle: InkWire.Bundle) {
        if (activity.isFinishing || activity.isDestroyed) return
        RecognizingOverlay.show(activity)
        try {
            val started = System.currentTimeMillis()
            val raw = call { it.recognizePage(SeamShared.write(InkWire.encode(bundle.strokes, bundle.pageWidth, bundle.pageHeight)), bundle.pageWidth.coerceAtLeast(1f), bundle.pageHeight.coerceAtLeast(1f)) }
            val paragraphs = paragraphsOf(raw)
            Slog.d(TAG) { "read ${bundle.strokes.size} strokes into ${paragraphs.size} paragraph(s) in ${System.currentTimeMillis() - started} ms" }
            RecognizingOverlay.hide(activity)
            if (activity.isFinishing || activity.isDestroyed || !usable()) return
            if (paragraphs.isEmpty()) Dialogs.problem(activity, PaperR.string.recognize_problem_title, PaperR.string.recognize_nothing)
            else insert(paragraphs)
        } catch (e: RecognizerCallException) {
            Log.w(TAG, "the pad's ink was not read")
            RecognizingOverlay.hide(activity)
            val body = when {
                e.tooLarge -> PaperR.string.recognize_too_dense
                e.notReady -> PaperR.string.recognize_still_downloading
                e.noRecognizer -> PaperR.string.recognize_no_recognizer
                else -> PaperR.string.recognize_failed
            }
            Dialogs.problem(activity, PaperR.string.recognize_problem_title, body)
        } finally {
            RecognizingOverlay.hide(activity)
        }
    }

    private suspend fun <T> call(block: (ISoilSeam) -> T): T = withContext(Dispatchers.IO) {
        try {
            block((activity.application as DocsproutApp).soil.seam())
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
        private const val TAG = "PadWords"
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
            return MarkdownReflow.reflow(lines).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
