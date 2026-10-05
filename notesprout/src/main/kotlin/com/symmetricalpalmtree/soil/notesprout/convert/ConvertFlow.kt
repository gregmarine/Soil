package com.symmetricalpalmtree.soil.notesprout.convert

import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.markdown.rich.RichBlock
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.data.NotebookStore
import com.symmetricalpalmtree.soil.notesprout.data.PageRef
import com.symmetricalpalmtree.soil.notesprout.recognition.SeamRecognizerPort
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.ink.InkWire
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerCallException
import com.symmetricalpalmtree.soil.paper.recognition.RecognizerReadiness
import com.symmetricalpalmtree.soil.paper.recognition.RecognizingOverlay
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamItem
import com.symmetricalpalmtree.soil.seam.SeamShared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * **Convert**: a page, or the whole notebook, made into a new document beside the notebook
 * (design §4). The handwriting is read by the recogniser Soil relays to, behind the consent flow
 * every recognition uses; headings and text objects come across as they are ([ConvertPlan]); and
 * Soil has the document's own app make the document from the Markdown.
 *
 * **Done once.** The document is a copy of what the pages said at this moment, and nothing keeps
 * the two in step afterwards. The notebook is not changed.
 *
 * Nothing is half-made: a page whose ink cannot be read stops the whole convert with the reason,
 * and no document is left behind. Nothing read is ever logged.
 */
object ConvertFlow {

    private const val TAG = "ConvertFlow"

    private var busyOwner: WeakReference<AppCompatActivity>? = null
    private fun busy(): Boolean = busyOwner?.get()?.let { !it.isFinishing && !it.isDestroyed } ?: false

    /**
     * [pages] with the number each shows, in order. [name] is what the document is called;
     * [onOpen] opens the item Soil made.
     */
    fun run(
        activity: AppCompatActivity,
        port: SeamRecognizerPort,
        store: NotebookStore,
        notebookId: String,
        pages: List<Pair<PageRef, Int>>,
        name: String,
        onOpen: (itemId: String) -> Unit,
    ) {
        if (busy() || pages.isEmpty()) return
        busyOwner = WeakReference(activity)
        RecognizerReadiness.ensureReady(
            activity, port,
            onReady = { try { convert(activity, port, store, notebookId, pages, name, onOpen) } finally { busyOwner = null } },
            onGaveUp = { busyOwner = null },
        )
    }

    private suspend fun convert(
        activity: AppCompatActivity,
        port: SeamRecognizerPort,
        store: NotebookStore,
        notebookId: String,
        pages: List<Pair<PageRef, Int>>,
        name: String,
        onOpen: (String) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val started = System.currentTimeMillis()
        val blocks = ArrayList<RichBlock>()
        var strokesRead = 0
        try {
            for ((index, entry) in pages.withIndex()) {
                val (page, number) = entry
                RecognizingOverlay.show(activity, if (pages.size == 1) activity.getString(R.string.convert_reading_page) else activity.getString(R.string.convert_reading_pages, index + 1, pages.size))
                val content = withContext(Dispatchers.IO) { store.readPage(page) }
                for (piece in ConvertPlan.pieces(content)) {
                    when (piece) {
                        is ConvertPlan.Piece.Words -> blocks += piece.blocks
                        is ConvertPlan.Piece.Ink -> {
                            if (!InkWire.withinLimits(piece.strokes)) {
                                RecognizingOverlay.hide(activity)
                                problem(activity, activity.getString(R.string.convert_too_dense_body, number))
                                return
                            }
                            blocks += ConvertPlan.paragraphs(port.recognizePage(piece.strokes, page.width.coerceAtLeast(1f), page.height.coerceAtLeast(1f)))
                            strokesRead += piece.strokes.size
                        }
                    }
                }
            }
            val markdown = ConvertPlan.markdown(blocks)
            if (markdown == null) {
                RecognizingOverlay.hide(activity)
                problem(activity, activity.getString(R.string.convert_nothing_body))
                return
            }
            RecognizingOverlay.show(activity, activity.getString(R.string.convert_making))
            val made = port.soil { it.makeItemFromFile(notebookId, name, "md", SeamShared.write(markdown.toByteArray(Charsets.UTF_8))) }
            Slog.d(TAG) { "converted ${pages.size} page(s), $strokesRead strokes, into ${blocks.size} blocks in ${System.currentTimeMillis() - started} ms" }
            RecognizingOverlay.hide(activity)
            done(activity, made, onOpen)
        } catch (e: RecognizerCallException) {
            Log.w(TAG, "a page was not read")
            RecognizingOverlay.hide(activity)
            val body = when {
                e.tooLarge -> PaperR.string.recognize_too_dense
                e.notReady -> PaperR.string.recognize_still_downloading
                e.noRecognizer -> PaperR.string.recognize_no_recognizer
                else -> PaperR.string.recognize_failed
            }
            problem(activity, activity.getString(body))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the document was not made: ${e.javaClass.simpleName}")
            RecognizingOverlay.hide(activity)
            problem(
                activity,
                activity.getString(
                    when (e.message) {
                        Seam.MAKE_NO_APP -> R.string.convert_no_app_body
                        Seam.INGEST_TOO_LARGE -> R.string.convert_too_long_body
                        else -> R.string.convert_failed_body
                    },
                ),
            )
        } finally {
            RecognizingOverlay.hide(activity)
        }
    }

    private fun problem(activity: AppCompatActivity, body: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.problem(activity, activity.getString(R.string.convert_title), body)
    }

    /** It worked, and where it went matters: the name, and a way straight to it. */
    private fun done(activity: AppCompatActivity, made: SeamItem, onOpen: (String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.convert_done_title)
                .setMessage(activity.getString(R.string.convert_done_body, made.name))
                .setPositiveButton(R.string.convert_open) { _, _ -> onOpen(made.id) }
                .setNegativeButton(PaperR.string.ok, null)
                .create(),
        ).show()
    }
}
