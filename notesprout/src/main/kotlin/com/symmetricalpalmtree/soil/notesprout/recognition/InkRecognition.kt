package com.symmetricalpalmtree.soil.notesprout.recognition

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.R
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerCallException
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerReadiness
import com.symmetricalpalmtree.soil.paper.recognition.RecognizingOverlay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Ink → words: the one flow behind H on ink, Make text and Tag on ink. The person lassoes
 * handwriting; this takes the strokes to the recogniser Soil relays to, behind the consent flow,
 * and comes back with text, or with a reason and nothing changed.
 *
 * The writing area is the **selection box**, not the page: the recogniser reads the area as the
 * scale of the writing. A heading or a tag is one line, so every run of whitespace collapses to
 * one space; a text object keeps the recogniser's line breaks ([multiLine]). Blank after that is
 * a failure the person hears about, and the ink is left exactly as it was.
 *
 * One flow at a time, guarded by a weak reference to the screen that started it. Nothing
 * recognised is ever logged.
 */
object InkRecognition {

    private const val TAG = "InkRecognition"
    private val WHITESPACE = Regex("\\s+")
    private val HORIZONTAL = Regex("[^\\S\\n]+")

    private var busyOwner: WeakReference<AppCompatActivity>? = null
    private fun busy(): Boolean = busyOwner?.get()?.let { !it.isFinishing && !it.isDestroyed } ?: false

    /** Exactly one of [onRecognized] (non-blank text) and [onGaveUp] runs, on Main. */
    fun run(
        activity: AppCompatActivity,
        port: SeamRecognizerPort,
        strokes: List<Stroke>,
        areaWidth: Float,
        areaHeight: Float,
        multiLine: Boolean = false,
        onRecognized: (String) -> Unit,
        onGaveUp: () -> Unit = {},
    ) {
        if (busy()) return
        val ink = strokes.filter { it.points.isNotEmpty() }
        if (ink.isEmpty()) { onGaveUp(); return }
        busyOwner = WeakReference(activity)
        RecognizerReadiness.ensureReady(
            activity, port,
            onReady = {
                try {
                    recognize(activity, port, ink, areaWidth, areaHeight, multiLine, onRecognized, onGaveUp)
                } finally {
                    busyOwner = null
                }
            },
            onGaveUp = { busyOwner = null; onGaveUp() },
        )
    }

    private suspend fun recognize(
        activity: AppCompatActivity,
        port: SeamRecognizerPort,
        ink: List<Stroke>,
        areaWidth: Float,
        areaHeight: Float,
        multiLine: Boolean,
        onRecognized: (String) -> Unit,
        onGaveUp: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) { onGaveUp(); return }
        RecognizingOverlay.show(activity)
        try {
            val t0 = System.currentTimeMillis()
            val raw = port.recognizeInk(ink, areaWidth.coerceAtLeast(1f), areaHeight.coerceAtLeast(1f), "")
            val text = if (multiLine) normalizeLines(raw) else oneLine(raw)
            Slog.d(TAG) { "recognised ${ink.size} strokes → ${text.length} chars in ${System.currentTimeMillis() - t0} ms" }
            RecognizingOverlay.hide(activity)
            if (activity.isFinishing || activity.isDestroyed) { onGaveUp(); return }
            if (text.isEmpty()) {
                Dialogs.problem(activity, R.string.recognize_problem_title, R.string.recognize_nothing)
                onGaveUp()
            } else {
                onRecognized(text)
            }
        } catch (e: RecognizerCallException) {
            Slog.d(TAG) { "recognise failed: ${e.message}" }
            RecognizingOverlay.hide(activity)
            val body = when {
                e.tooLarge -> R.string.recognize_too_dense
                e.notReady -> R.string.recognize_still_downloading
                e.noRecognizer -> R.string.recognize_no_recognizer
                else -> R.string.recognize_failed
            }
            Dialogs.problem(activity, R.string.recognize_problem_title, body)
            onGaveUp()
        } finally {
            RecognizingOverlay.hide(activity)
        }
    }

    /** One line: every run of whitespace a single space, trimmed. */
    fun oneLine(text: String): String = text.replace(WHITESPACE, " ").trim()

    /** Lines kept: horizontal runs collapsed, each line trimmed, blank runs reduced to one, the ends bare. */
    fun normalizeLines(raw: String): String {
        val lines = raw.split('\n').map { it.replace(HORIZONTAL, " ").trim() }
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            if (line.isEmpty() && (out.isEmpty() || out.last().isEmpty())) continue
            out.add(line)
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out.joinToString("\n")
    }
}
