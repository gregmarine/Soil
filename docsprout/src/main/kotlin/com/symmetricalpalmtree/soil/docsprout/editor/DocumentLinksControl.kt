package com.symmetricalpalmtree.soil.docsprout.editor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.docsprout.BuildConfig
import com.symmetricalpalmtree.soil.docsprout.DocsproutApp
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.docsprout.data.DocTrail
import com.symmetricalpalmtree.soil.docsprout.data.DocsproutPrefs
import com.symmetricalpalmtree.soil.docsprout.data.DocumentSchema
import com.symmetricalpalmtree.soil.docsprout.databinding.ActivityDocumentBinding
import com.symmetricalpalmtree.soil.docsprout.editor.rich.LinkSpan
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichOps
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamBacklink
import com.symmetricalpalmtree.soil.seam.SeamItem
import com.symmetricalpalmtree.soil.seam.SoilAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.symmetricalpalmtree.soil.paper.R as PaperR

/**
 * **A document's links**, as the writer meets them in the rendered document:
 *
 *  - **A tap on a link follows it** (decision 2026-10-04). Into the library, Soil opens the
 *    target in the app for its kind: a notebook opens over this document, and closing it comes
 *    back; another document takes this one's place, so where the writer came from is put on a
 *    trail first, and a Back button in the bar returns along it. Anywhere else, the address is
 *    handed to whatever on the device opens such addresses.
 *  - **A long press on a link** shows a sheet: Open, Edit link, Remove link.
 *  - **Choose from library** in the Link dialog is Soil's own picker, started for a result.
 *  - **Links to this document** is a button that is only there when something links here.
 *
 * In the Markdown source a link is its characters and nothing follows. Nothing here logs an
 * address or a name.
 */
internal class DocumentLinksControl(
    private val activity: AppCompatActivity,
    private val binding: ActivityDocumentBinding,
    private val prefs: DocsproutPrefs,
    private val itemId: () -> String?,
    private val usable: () -> Boolean,
    /** The words are written before the screen is left for a link's target. */
    private val saveNow: () -> Unit,
    private val editLink: () -> Unit,
    private val pickerLauncher: ActivityResultLauncher<Intent>,
    /** A Bible link taken off by the sheet's Remove: its words and its wire, to be remembered. */
    private val unlinkBible: (words: String, wire: String) -> Unit,
) {

    private var busy = false

    /** What Choose from library answers into, between the picker's start and its result. */
    private var pendingPick: ((url: String, words: String) -> Unit)? = null

    fun install() {
        binding.btnTrailBack.setOnClickListener { walkBack() }
        binding.btnBacklinks.setOnClickListener { showBacklinks() }
        for (button in listOf(binding.btnTrailBack, binding.btnBacklinks)) TooltipCompat.setTooltipText(button, button.contentDescription)
        binding.rich.onLinkLongPress = { link -> if (usable()) showSheet(link) }
    }

    // ── Arriving ──────

    /**
     * The document that just opened. One a link (or the way back) was heading for goes on with
     * the trail; any other was opened afresh, and that is a new story.
     */
    fun arrived(documentId: String) {
        if (prefs.arriving == documentId) prefs.arriving = null else { prefs.arriving = null; prefs.trail = emptyList() }
        binding.btnTrailBack.visibility = if (prefs.trail.isEmpty()) View.GONE else View.VISIBLE
        refreshBacklinks()
    }

    /** Looked for again whenever the screen comes back: a link may have been made meanwhile. */
    fun refreshBacklinks() {
        val id = itemId() ?: return
        activity.lifecycleScope.launch {
            val into = backlinks(id)
            binding.btnBacklinks.visibility = if (into.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // ── Following ──────

    /** A tap on character [index] of the rendered document. Answers whether a link took it. */
    fun followAtChar(index: Int): Boolean {
        val link = binding.rich.linkAtChar(index) ?: return false
        follow(link.url)
        return true
    }

    private fun follow(url: String) {
        if (!usable() || busy) return
        BibleLinks.wireOfAddress(url)?.let { wire -> saveNow(); handToSoil(wire); return }
        val address = SoilAddress.decode(url)
        if (address == null) { openOutside(url); return }
        val me = itemId() ?: return
        // A link never points at its own home; one that does (typed by hand) goes nowhere.
        if (address.itemId == me) return
        busy = true
        saveNow()
        activity.lifecycleScope.launch {
            try {
                val item = item(address.itemId)
                if (item == null) { Dialogs.problem(activity, R.string.link_failed_title, R.string.link_gone_body); return@launch }
                // Another document takes this one's place: remember the way back.
                if (item.kind == DocumentSchema.KIND) {
                    prefs.trail = DocTrail.push(prefs.trail, me)
                    prefs.arriving = item.id
                }
                if (!handToSoil(item.id, address.pageId) && item.kind == DocumentSchema.KIND) {
                    prefs.trail = DocTrail.pop(prefs.trail).second
                    prefs.arriving = null
                }
            } finally {
                busy = false
            }
        }
    }

    /** Back along the trail: the document the last link was followed from. */
    private fun walkBack() {
        if (!usable() || busy) return
        val (back, rest) = DocTrail.pop(prefs.trail)
        if (back == null) { binding.btnTrailBack.visibility = View.GONE; return }
        busy = true
        saveNow()
        prefs.trail = rest
        prefs.arriving = back
        // A document that is gone is explained by Soil, and this one stays, one step shorter of trail.
        handToSoil(back, null)
        binding.btnTrailBack.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        busy = false
    }

    /** A link into the Bible: Soil opens the reader on the passage, over this document. */
    private fun handToSoil(wire: String): Boolean {
        val started = runCatching {
            activity.startActivity(Intent(Seam.ACTION_FOLLOW).setPackage(BuildConfig.SOIL_PACKAGE).putExtra(Seam.EXTRA_BIBLE_WIRE, wire))
        }.isSuccess
        if (!started) Dialogs.problem(activity, R.string.link_failed_title, R.string.link_no_soil_body)
        return started
    }

    private fun handToSoil(targetId: String, pageId: String?): Boolean {
        val started = runCatching {
            activity.startActivity(
                Intent(Seam.ACTION_FOLLOW).setPackage(BuildConfig.SOIL_PACKAGE)
                    .putExtra(Seam.EXTRA_ITEM_ID, targetId)
                    .putExtra(Seam.EXTRA_PAGE_ID, pageId),
            )
        }.isSuccess
        if (!started) Dialogs.problem(activity, R.string.link_failed_title, R.string.link_no_soil_body)
        return started
    }

    /** An address that is not into the library: whatever on the device opens it. */
    private fun openOutside(url: String) {
        val opened = runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (!opened) {
            Log.w(TAG, "nothing opens a link's address")
            Dialogs.problem(activity, R.string.link_failed_title, R.string.link_outside_body)
        }
    }

    // ── The long-press sheet ──────

    private fun showSheet(link: LinkSpan) {
        activity.lifecycleScope.launch {
            val address = SoilAddress.decode(link.url)
            val title = when {
                BibleLinks.labelOf(link.url) != null -> BibleLinks.labelOf(link.url)!!
                address == null -> link.url
                else -> item(address.itemId)?.let { activity.getString(if (address.pageId == null) R.string.link_sheet_library else R.string.link_sheet_library_page, it.name) }
                    ?: activity.getString(R.string.link_sheet_gone)
            }
            if (activity.isFinishing || activity.isDestroyed || !usable()) return@launch
            ActionSheetDialog(activity).title(title)
                .addAction(R.drawable.ic_external_link, activity.getString(R.string.link_open)) { follow(link.url) }
                .addAction(PaperR.drawable.ic_pencil, activity.getString(R.string.link_edit)) { if (caretInto(link)) editLink() }
                .addAction(PaperR.drawable.ic_trash, activity.getString(R.string.link_remove)) { remove(link) }
                .show()
        }
    }

    /** The link taken off its words. A Bible link is remembered as taken off, so the pass that
     *  links references as they are typed never puts it back. */
    private fun remove(link: LinkSpan) {
        val wire = BibleLinks.wireOfAddress(link.url)
        val words = binding.rich.rangeOf(link)?.let { (a, b) -> binding.rich.text?.subSequence(a, b)?.toString() }
        if (!caretInto(link)) return
        RichOps.setLink(binding.rich, "")
        if (wire != null && words != null) unlinkBible(words, wire)
    }

    /** The caret put inside [link], which is how the Link tool knows which link is meant. */
    private fun caretInto(link: LinkSpan): Boolean {
        val (start, end) = binding.rich.rangeOf(link) ?: return false
        binding.rich.requestFocus()
        binding.rich.setSelection(if (end - start > 1) start + 1 else start)
        return true
    }

    // ── Choose from library ──────

    /** Soil's item picker, for a notebook, a page of one, or another document. */
    fun chooseFromLibrary(apply: (url: String, words: String) -> Unit) {
        pendingPick = apply
        val intent = Intent(Seam.ACTION_PICK_ITEM).setPackage(BuildConfig.SOIL_PACKAGE)
            .putExtra(Seam.EXTRA_PICK_PAGE, true)
            .putExtra(Seam.EXTRA_EXCLUDE_ITEM_ID, itemId())
        try {
            pickerLauncher.launch(intent)
        } catch (e: Exception) {
            pendingPick = null
            Log.w(TAG, "Soil's picker would not open: ${e.javaClass.simpleName}")
            Dialogs.problem(activity, R.string.link_failed_title, R.string.link_picker_failed_body)
        }
    }

    /** The picker's answer, from the screen's result callback. */
    fun onPicked(resultCode: Int, data: Intent?) {
        val apply = pendingPick
        pendingPick = null
        val id = data?.getStringExtra(Seam.EXTRA_ITEM_ID)
        if (apply == null || resultCode != Activity.RESULT_OK || id.isNullOrEmpty()) return
        val address = runCatching { SoilAddress(id, data.getStringExtra(Seam.EXTRA_PAGE_ID)?.takeIf { it.isNotEmpty() }) }.getOrNull() ?: return
        apply(address.encode(), data.getStringExtra(Seam.EXTRA_ITEM_NAME).orEmpty())
    }

    // ── Links to here ──────

    private fun showBacklinks() {
        val id = itemId() ?: return
        if (!usable()) return
        activity.lifecycleScope.launch {
            val into = backlinks(id)
            if (into.isEmpty()) { binding.btnBacklinks.visibility = View.GONE; return@launch }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val sheet = ActionSheetDialog(activity).title(activity.getString(R.string.backlinks_title))
            // One row a place a link was made: an item, or a page of a notebook. Several pages of
            // one notebook are told apart by a count, since only the notebook can name its pages.
            val seen = HashMap<String, Int>()
            for (b in into.distinctBy { it.sourceItemId to it.sourcePageId }) {
                val n = (seen[b.sourceItemId] ?: 0) + 1
                seen[b.sourceItemId] = n
                val label = if (n == 1) b.sourceName else activity.getString(R.string.backlink_again, b.sourceName, n)
                sheet.addAction(null, label) { follow(runCatching { SoilAddress(b.sourceItemId, b.sourcePageId.takeIf { it.isNotEmpty() }).encode() }.getOrNull() ?: return@addAction) }
            }
            sheet.show()
        }
    }

    private suspend fun backlinks(id: String): List<SeamBacklink> = withContext(Dispatchers.IO) {
        runCatching { (activity.application as DocsproutApp).soil.seam().backlinks(id) }.getOrNull().orEmpty()
    }

    private suspend fun item(id: String): SeamItem? = withContext(Dispatchers.IO) {
        runCatching { (activity.application as DocsproutApp).soil.seam().item(id) }.getOrNull()
    }

    private companion object {
        const val TAG = "DocumentLinks"
    }
}
