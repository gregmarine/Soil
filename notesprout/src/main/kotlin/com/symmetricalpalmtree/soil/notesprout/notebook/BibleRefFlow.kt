package com.symmetricalpalmtree.soil.notesprout.notebook

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.bibleref.ReferenceText
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.data.NotebookAction
import com.symmetricalpalmtree.soil.notesprout.data.NotebookDocument
import com.symmetricalpalmtree.soil.notesprout.objects.FreePlacement
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import com.symmetricalpalmtree.soil.notesprout.objects.PageText
import com.symmetricalpalmtree.soil.notesprout.recognition.InkRecognition
import com.symmetricalpalmtree.soil.notesprout.recognition.SeamRecognizerPort
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import java.util.UUID
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * **A Bible reference on the page**, the three ways it comes into being or changes: **convert**
 * (the lasso bar's Bible), **insert** (the Insert bar's) and **edit** (Edit link on a lone Bible
 * link). Kept out of [NotebookActivity], as the other flows are.
 *
 * What a Bible reference *is* on the page is an ordinary text object holding **the user's own
 * words**, wrapped in an ordinary link whose payload is [LinkPayload.KIND_BIBLE] and whose
 * target is the passage those words parse to. Everything a link already does (render, underline,
 * move, delete, copy, undo, unlink) is reused untouched.
 *
 *  - **The dialog comes first, the store last.** A convert recognises and then prefills the
 *    dialog; an insert opens it empty; an edit opens it on the text's own words. Nothing is
 *    written until the words read as a reference.
 *  - **A dead Bible link cannot be created**: words that are not a reference are a problem
 *    dialog whose Edit reopens the dialog with the words kept. Cancel leaves the ink as it was.
 *  - **The reference is read here**, by `:bible-ref`: the parser and the canon's chapter counts.
 *    Whether a verse exists only the reader knows, and it says so when the link is followed.
 *  - **One act, one undo step** ([NotebookAction.BibleRefCreated], [NotebookAction.BibleRefEdited]).
 *  - **Nothing typed, recognised or parsed is ever logged**: counts only.
 */
class BibleRefFlow(private val activity: AppCompatActivity, private val host: Host) {

    /** What the flow needs of [NotebookActivity], and nothing more. */
    interface Host {
        val alive: Boolean
        val document: NotebookDocument?
        val recognizerPort: SeamRecognizerPort
        val density: Float
        val scaledDensity: Float
        fun occupied(): List<Bounds>

        /** Ink, a text and a link made as one step: the strokes erased (none for an insert), the
         *  text written, the link wrapped around it and landed selected. Everything on Main, in
         *  one page operation. */
        fun landReference(pageId: String, strokeIds: List<String>, text: PageText, payload: String, label: String)

        /** An edit landed: the text and the payload rewritten together, one step. */
        fun relandReference(pageId: String, link: PageLink, before: PageText, after: PageText, payload: String, label: String)
    }

    // ── The three doors ──────

    /** The lasso bar's **Bible**: recognise the lassoed handwriting as one line, then offer it in
     *  the dialog to correct before it is read. The ink is captured now: the selection may die
     *  before recognition answers. A recognition that gives up creates nothing. */
    fun convert(sel: Selection) {
        if (!host.alive) return
        val doc = host.document ?: return
        val pageId = doc.pageId
        val strokes: List<Stroke> = doc.strokes.filter { it.id in sel.strokeIds }
        if (strokes.isEmpty()) return
        val bounds = sel.bounds
        val strokeIds = strokes.map { it.id }
        InkRecognition.run(activity, host.recognizerPort, strokes, bounds.width, bounds.height, multiLine = false, onRecognized = { recognised ->
            dialog(InkRecognition.oneLine(recognised)) { typed, wire -> createFromConversion(pageId, strokeIds, bounds, typed, wire) }
        })
    }

    /** The Insert bar's **Bible reference**: the dialog empty, and what it reads lands at the
     *  page centre. Nothing exists until the words read as a reference. */
    fun insertAtCentre() {
        if (!host.alive) return
        val pageId = host.document?.pageId ?: return
        dialog("") { typed, wire -> insert(pageId, typed, wire) }
    }

    /** **Edit link** on a lone Bible link: the reference dialog on the wrapped text's words,
     *  never the page picker. A Bible link wraps exactly one text by construction; one that does
     *  not is a row no flow of ours wrote, and it is explained rather than guessed at. */
    fun edit(link: PageLink) {
        if (!host.alive) return
        val text = link.texts.singleOrNull()
        if (text == null || link.strokes.isNotEmpty() || link.headings.isNotEmpty() || link.stickies.isNotEmpty()) {
            Slog.d(TAG) { "edit: not a single-text Bible link" }
            Dialogs.problem(activity, R.string.bible_reference_problem_title, R.string.bible_reference_unwrappable)
            return
        }
        val pageId = host.document?.pageId ?: return
        dialog(text.text) { typed, wire -> applyEdit(pageId, link.id, typed, wire) }
    }

    // ── The dialog, and the one parse behind it ──────

    /** Ask for the words, then read them. Words that are not a reference are a problem dialog
     *  that keeps the words: its Edit reopens this dialog prefilled. */
    private fun dialog(prefill: String, onRead: (typed: String, wire: String) -> Unit) {
        if (!host.alive) return
        BibleRefDialog.show(activity, prefill) { typed ->
            val wire = ReferenceText.wireOf(typed)
            if (wire == null) notAReference(typed, onRead) else onRead(typed, wire)
        }
    }

    private fun notAReference(typed: String, onRead: (String, String) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        Slog.d(TAG) { "${typed.length} chars are not a reference" }
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.bible_reference_unknown_title)
                .setMessage(activity.getString(R.string.bible_reference_unknown_body, typed))
                .setPositiveButton(R.string.bible_reference_edit_action) { _, _ -> dialog(typed, onRead) }
                .setNegativeButton(PaperR.string.cancel, null)
                .create(),
        ).show()
    }

    // ── Applying it ──────

    /** The success half of a conversion: the text anchored at the lassoed ink's top-left,
     *  measured for that anchor, the ink erased, the whole wrapped in a Bible link. */
    private fun createFromConversion(pageId: String, strokeIds: List<String>, inkBounds: Bounds, source: String, wire: String) {
        val doc = host.document ?: return
        if (!host.alive || doc.pageId != pageId) return
        val (w, h) = TextRenderer.measure(source, (doc.pageWidth - inkBounds.left).toInt().coerceAtLeast(1), host.density, host.scaledDensity)
        val text = PageText(UUID.randomUUID().toString(), source, inkBounds.left, inkBounds.top, w, h, 0)
        host.landReference(pageId, strokeIds, text, payload(wire), label(wire))
        Slog.d(TAG) { "converted ${strokeIds.size} strokes into a reference of ${source.length} chars" }
    }

    /** The success half of an insert: the centre when it is clear, else the nearest clear spot. */
    private fun insert(pageId: String, source: String, wire: String) {
        val doc = host.document ?: return
        if (!host.alive || doc.pageId != pageId) return
        val (w0, h0) = TextRenderer.measure(source, doc.pageWidth.toInt(), host.density, host.scaledDensity)
        val (x, y) = FreePlacement.nearCentre(doc.pageWidth, doc.pageHeight, w0, h0, host.occupied(), host.density)
        val (w, h) = if (doc.pageWidth - x < w0) TextRenderer.measure(source, (doc.pageWidth - x).toInt(), host.density, host.scaledDensity) else w0 to h0
        val text = PageText(UUID.randomUUID().toString(), source, x, y, w, h, 0)
        host.landReference(pageId, emptyList(), text, payload(wire), label(wire))
        Slog.d(TAG) { "inserted a reference of ${source.length} chars" }
    }

    /** The Link from an Edit: the words and the payload rewritten together. An unchanged text
     *  and an unchanged wire is a no-op: no write, no undo step. */
    private fun applyEdit(pageId: String, linkId: String, source: String, wire: String) {
        val doc = host.document ?: return
        if (!host.alive || doc.pageId != pageId) return
        val link = doc.links[linkId] ?: return
        val before = link.texts.singleOrNull() ?: return
        val newPayload = payload(wire)
        if (source == before.text && newPayload == link.payload) {
            Slog.d(TAG) { "edit: nothing changed" }
            return
        }
        val (w, h) = TextRenderer.measure(source, (doc.pageWidth - before.x).toInt().coerceAtLeast(1), host.density, host.scaledDensity)
        val after = before.copy(text = source, width = w, height = h)
        host.relandReference(pageId, link, before, after, newPayload, label(wire))
        Slog.d(TAG) { "edited a reference: ${source.length} chars" }
    }

    private fun payload(wire: String): String = LinkPayload.encode(LinkPayload.CHROME_UNDERLINE, LinkPayload.KIND_BIBLE, wire, null)

    private fun label(wire: String): String = ReferenceText.labelOf(wire) ?: wire

    /** One line at the bottom naming what the reference matched. */
    fun toastLinked(label: String) = Toast.makeText(activity, activity.getString(R.string.bible_reference_linked_toast, label), Toast.LENGTH_SHORT).show()

    private companion object {
        const val TAG = "BibleRefFlow"
    }
}
