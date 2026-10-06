package com.symmetricalpalmtree.soil.docsprout.editor

import android.content.ClipboardManager
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.docsprout.DocsproutApp
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.clip.BibleClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * **A passage on the clipboard, pasted into a document** (Greg's decision, 2026-10-05): the
 * reader's Copy puts a passage in the `bible` slot, and a paste here **asks each time** whether
 * the reference alone goes in, as a link, or the verses with it. The clipboard is not taken.
 * Ctrl+V pastes whichever was copied last, this passage, the clipboard's ink or the device's own
 * text ([newerThan]).
 */
internal class BiblePaste(
    private val activity: AppCompatActivity,
    private val usable: () -> Boolean,
    private val insertReference: (wire: String, label: String) -> Unit,
    private val insertVerses: (wire: String, markdown: String) -> Unit,
) {
    /** When the passage on the clipboard was copied, as last looked; null for none. Main only. */
    var copiedAt: Long? = null
        private set

    private var busy = false

    fun refresh() {
        activity.lifecycleScope.launch {
            copiedAt = withContext(Dispatchers.IO) {
                runCatching { (activity.application as DocsproutApp).soil.seam().clipHeader(BibleClip.SLOT)?.copiedAt }.getOrNull()
            }
        }
    }

    /** Whether the passage was copied after the clipboard's ink and the device's own text. */
    fun newerThan(inkCopiedAt: Long?): Boolean {
        val at = copiedAt ?: return false
        if (inkCopiedAt != null && inkCopiedAt > at) return false
        val description = runCatching { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClipDescription }.getOrNull()
        return description == null || at > description.timestamp
    }

    /** The question: the reference alone, or the verses with it. */
    fun prompt() {
        if (!usable() || busy) return
        busy = true
        activity.lifecycleScope.launch {
            val clip = withContext(Dispatchers.IO) {
                runCatching {
                    val seam = (activity.application as DocsproutApp).soil.seam()
                    BibleClip.decode(seam.clip(BibleClip.SLOT)?.let { SeamShared.readAndClose(it) })
                }.getOrNull()
            }
            busy = false
            if (activity.isFinishing || activity.isDestroyed || !usable()) return@launch
            if (clip == null) {
                copiedAt = null
                Dialogs.problem(activity, R.string.bible_paste_title, R.string.bible_paste_empty_body)
                return@launch
            }
            copiedAt = clip.copiedAt
            ActionSheetDialog(activity).title(clip.label)
                .addAction(PaperR.drawable.ic_link, activity.getString(R.string.bible_paste_reference)) { insertReference(clip.wire, clip.label) }
                .addAction(PaperR.drawable.ic_book, activity.getString(R.string.bible_paste_verses)) { insertVerses(clip.wire, clip.text) }
                .show()
        }
    }
}
