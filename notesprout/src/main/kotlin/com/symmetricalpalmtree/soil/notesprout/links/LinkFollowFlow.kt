package com.symmetricalpalmtree.soil.notesprout.links

import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.objects.LinkNav
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import com.symmetricalpalmtree.soil.notesprout.objects.TrailCodec
import com.symmetricalpalmtree.soil.notesprout.objects.TrailEntry
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.SeamItem
import com.symmetricalpalmtree.soil.seamkit.SeamConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The notebook screen's side of **following** a link: a finger tap on a link goes somewhere, and
 * a swipe up walks the story home. [LinkNav] decides what kind of hop a payload asks for; this
 * decides whether the hop is still possible, and asks **before going**: the item alive in the
 * library, and for a page of another notebook, the page itself still there. An item that is not
 * a notebook is handed to Soil, which knows the app for it and says so when there is none. [LinkTrail] remembers where each hop came from.
 *
 * Two directions, two rules that differ on purpose:
 * - A **follow** that cannot land explains itself: the dead-target dialog, which offers to
 *   retarget the link in the picker or to remove it (an unlink, undoable). Left alone, the link
 *   row is never touched: a target gone today may be back from a backup tomorrow.
 * - A **walk back** that meets a dead entry skips it in silence and keeps popping. The person
 *   asked to go back, not to be told about a page they deleted.
 *
 * One door ([busy]) guards both. A hop that leaves the screen keeps it shut: the hand-off is
 * asynchronous, and a second tap in that gap must do nothing.
 */
class LinkFollowFlow(
    private val activity: AppCompatActivity,
    private val soil: SeamConnection,
    private val itemId: () -> String?,
    private val displayedPageId: () -> String,
    private val pageIds: () -> List<String>,
    /** The displayed page's links in z-order, topmost last. */
    private val liveLinks: () -> Collection<PageLink>,
    private val alive: () -> Boolean,
    private val navigateToPage: (String) -> Unit,
    /** Leave this notebook for another, at a page or at its own remembered one. */
    private val leaveFor: (itemId: String, pageId: String?) -> Unit,
    /** Open an item that is not a notebook, in its own app, over this screen: Soil's to do.
     *  This notebook stays where it is, and closing what opened comes back to it. */
    private val openElsewhere: (itemId: String) -> Unit,
    /** A passage: Soil opens the Bible's reader on the wire, over this notebook. */
    private val openBible: (wire: String) -> Unit,
    private val editLink: (PageLink) -> Unit,
    /** Unwrap the link, its content kept: the other way out of a dead end. Undoable. */
    private val removeLink: (PageLink) -> Unit,
) {

    private var busy = false

    /** A finger tap at ([x], [y]) in paper coordinates. Answers whether it hit a live link. */
    fun followAt(x: Float, y: Float): Boolean {
        if (!alive() || busy) return false
        val link = liveLinks().lastOrNull { it.bounds.contains(x, y) } ?: return false
        busy = true
        follow(link)
        return true
    }

    private fun follow(link: PageLink) {
        val me = itemId() ?: run { busy = false; return }
        activity.lifecycleScope.launch {
            when (val plan = LinkNav.planFollow(link.payload, me)) {
                LinkNav.Follow.NoOp -> {
                    busy = false
                    Slog.d(TAG) { "follow: a link to the notebook it is in; nothing to do" }
                }
                LinkNav.Follow.Dead -> {
                    busy = false
                    deadTarget(link, R.string.link_target_unreadable_body)
                }
                is LinkNav.Follow.SamePage -> {
                    if (plan.pageId == displayedPageId()) {
                        Slog.d(TAG) { "follow: the displayed page; nothing to do" }
                    } else if (plan.pageId !in pageIds()) {
                        deadTarget(link, R.string.link_target_page_gone_body)
                    } else {
                        pushOrigin(me)
                        navigateToPage(plan.pageId)
                    }
                    busy = false
                }
                is LinkNav.Follow.OtherItem -> followOut(link, me, plan)
                is LinkNav.Follow.Bible -> {
                    busy = false
                    if (alive()) {
                        Slog.d(TAG) { "follow: a passage, handed to Soil" }
                        openBible(plan.wire)
                    }
                }
            }
        }
    }

    /** Another item: alive, of a kind with an app, and for a page target, the page itself alive. */
    private suspend fun followOut(link: PageLink, me: String, plan: LinkNav.Follow.OtherItem) {
        val item = aliveItem(plan.itemId)
        if (item == null) {
            busy = false
            deadTarget(link, R.string.link_target_notebook_gone_body)
            return
        }
        if (item.kind != NotebookSchema.KIND) {
            busy = false
            // A whole item of another kind (a document) opens in its own app. A page of one is
            // nothing this app can name: only a notebook has pages.
            if (plan.pageId == null && alive()) {
                Slog.d(TAG) { "follow: an item of another kind, handed to Soil" }
                openElsewhere(item.id)
            } else {
                deadTarget(link, activity.getString(R.string.link_target_other_kind_body, item.kind))
            }
            return
        }
        if (plan.pageId != null && !foreignPageAlive(plan.itemId, plan.pageId)) {
            busy = false
            deadTarget(link, R.string.link_target_page_gone_body)
            return
        }
        if (!alive()) { busy = false; return }
        pushOrigin(me)
        Slog.d(TAG) { "follow: leaving for another notebook" }
        leaveFor(plan.itemId, plan.pageId)   // busy stays set: this screen is on its way out
    }

    /** Swipe up: pop until something lands. Bounded by the trail's own cap. */
    fun walkBack(onEmpty: () -> Unit) {
        if (!alive() || busy) return
        val me = itemId() ?: return
        busy = true
        activity.lifecycleScope.launch {
            val trail = LinkTrail(activity)
            repeat(TrailCodec.MAX_ENTRIES) {
                val entry = trail.pop() ?: run { busy = false; onEmpty(); return@launch }
                when (val plan = LinkNav.planBack(entry.itemId, entry.pageId, me)) {
                    is LinkNav.Back.SamePage -> if (plan.pageId in pageIds()) {
                        navigateToPage(plan.pageId)
                        busy = false
                        return@launch
                    }
                    is LinkNav.Back.OtherItem -> {
                        val item = aliveItem(plan.itemId)
                        if (item != null && item.kind == NotebookSchema.KIND && foreignPageAlive(plan.itemId, plan.pageId)) {
                            if (!alive()) { busy = false; return@launch }
                            Slog.d(TAG) { "back: leaving for another notebook" }
                            leaveFor(plan.itemId, plan.pageId)
                            return@launch
                        }
                    }
                }
                Slog.d(TAG) { "back: skipping a dead trail entry" }
            }
            busy = false
            onEmpty()
        }
    }

    /** Where the person is standing right now, pushed before every successful hop. */
    private fun pushOrigin(me: String) = LinkTrail(activity).push(TrailEntry(me, displayedPageId()))

    private suspend fun aliveItem(id: String): SeamItem? =
        withContext(Dispatchers.IO) { runCatching { soil.seam().item(id) }.getOrNull() }

    /** A one-shot read of another notebook's page list, its session closed at once. */
    private suspend fun foreignPageAlive(itemId: String, pageId: String): Boolean {
        val foreign = ForeignNotebook(soil, itemId)
        return try {
            foreign.pages().any { it.id == pageId }
        } finally {
            foreign.close()
        }
    }

    private fun deadTarget(link: PageLink, @StringRes bodyRes: Int) = deadTarget(link, activity.getString(bodyRes))

    /** Why the tap did nothing, and the two things worth offering: retarget the link, or remove it. */
    private fun deadTarget(link: PageLink, body: CharSequence) {
        if (activity.isFinishing || activity.isDestroyed) return
        Slog.d(TAG) { "follow: no reachable target" }
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.link_target_gone_title)
                .setMessage(body)
                .setPositiveButton(R.string.link_edit_action) { _, _ -> editLink(link) }
                .setNeutralButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .setNegativeButton(R.string.link_remove_action) { _, _ -> removeLink(link) }
                .create(),
        ).show()
    }

    private companion object { const val TAG = "LinkFollowFlow" }
}
