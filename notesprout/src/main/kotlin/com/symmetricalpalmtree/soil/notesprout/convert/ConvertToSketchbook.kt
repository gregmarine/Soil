package com.symmetricalpalmtree.soil.notesprout.convert

import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.data.NotebookStore
import com.symmetricalpalmtree.soil.notesprout.data.PageRef
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.recognition.RecognizingOverlay
import com.symmetricalpalmtree.soil.seam.ISoilSeam
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamItem
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.seamkit.clip.InkClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * **Convert to sketchbook**: a page, or the whole notebook, made into a new sketchbook beside the
 * notebook (design §4, Greg 2026-10-06). The strokes — a page's own and those its links wrap —
 * travel as the notebook's own page envelope ([InkClip.pageEnvelopeOf]), paper and all, and Soil
 * has the sketchbook's app make the sketchbook from it, every stroke baked black into its ink
 * layer. **Done once**: nothing keeps the two in step afterwards, and the notebook is not changed
 * — unless the person asks for a link to the new sketchbook left on the page (Greg, 2026-10-07).
 *
 * Nothing is half-made: an envelope the seam will not carry stops the convert with the reason,
 * and no sketchbook is left behind.
 */
object ConvertToSketchbook {

    private const val TAG = "ConvertToSketchbook"
    private const val EXTENSION = "soilink"

    /** [pages] with the number each shows, in order; [onOpen] opens the item Soil made;
     *  [onLeaveLink] places a link to it on the page converted (the page showing). */
    fun run(
        activity: AppCompatActivity,
        seam: suspend () -> ISoilSeam,
        store: NotebookStore,
        notebookId: String,
        pages: List<Pair<PageRef, Int>>,
        name: String,
        onOpen: (itemId: String) -> Unit,
        onLeaveLink: (itemId: String, itemName: String) -> Unit,
    ) {
        if (pages.isEmpty()) return
        activity.lifecycleScope.launch { convert(activity, seam, store, notebookId, pages, name, onOpen, onLeaveLink) }
    }

    private suspend fun convert(
        activity: AppCompatActivity,
        seam: suspend () -> ISoilSeam,
        store: NotebookStore,
        notebookId: String,
        pages: List<Pair<PageRef, Int>>,
        name: String,
        onOpen: (String) -> Unit,
        onLeaveLink: (String, String) -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val started = System.currentTimeMillis()
        try {
            RecognizingOverlay.show(activity, activity.getString(R.string.convert_sketch_making))
            val bytes = withContext(Dispatchers.IO) {
                val ink = pages.map { (page, _) ->
                    val content = store.readPage(page)
                    val strokes = content.strokes + content.links.flatMap { link -> link.strokes.map { 0L to it } }
                    InkClip.PageInk(page.width, page.height, store.templateBlob(page.templateId), strokes)
                }
                val env = InkClip.pageEnvelopeOf(ink, System.currentTimeMillis()) { UUID.randomUUID().toString() } ?: return@withContext null
                ClipEnvelope.encode(env)
            }
            if (bytes == null) {
                RecognizingOverlay.hide(activity)
                problem(activity, activity.getString(R.string.convert_sketch_too_large_body))
                return
            }
            val made = withContext(Dispatchers.IO) {
                val file = SeamShared.write(bytes)
                try {
                    seam().makeItemFromFile(notebookId, name, EXTENSION, file)
                } finally {
                    file.memory.close()
                }
            }
            Slog.d(TAG) { "converted ${pages.size} page(s) (${bytes.size} B) into a sketchbook in ${System.currentTimeMillis() - started} ms" }
            RecognizingOverlay.hide(activity)
            done(activity, made, onOpen, onLeaveLink)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the sketchbook was not made: ${e.javaClass.simpleName}")
            RecognizingOverlay.hide(activity)
            problem(
                activity,
                activity.getString(
                    when (e.message) {
                        Seam.MAKE_NO_APP -> R.string.convert_sketch_no_app_body
                        Seam.INGEST_TOO_LARGE, com.symmetricalpalmtree.soil.seam.SeamLimits.VALUE_TOO_LARGE -> R.string.convert_sketch_too_large_body
                        else -> R.string.convert_sketch_failed_body
                    },
                ),
            )
        } finally {
            RecognizingOverlay.hide(activity)
        }
    }

    private fun problem(activity: AppCompatActivity, body: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.problem(activity, activity.getString(R.string.convert_sketch_title), body)
    }

    /** It worked: Open · Leave a link · Done (Greg, 2026-10-07). */
    private fun done(activity: AppCompatActivity, made: SeamItem, onOpen: (String) -> Unit, onLeaveLink: (String, String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.convert_sketch_done_title)
                .setMessage(activity.getString(R.string.convert_sketch_done_body, made.name))
                .setPositiveButton(R.string.convert_open) { _, _ -> onOpen(made.id) }
                .setNeutralButton(R.string.convert_leave_link) { _, _ -> onLeaveLink(made.id, made.name) }
                .setNegativeButton(PaperR.string.ok, null)
                .create(),
        ).show()
    }
}
