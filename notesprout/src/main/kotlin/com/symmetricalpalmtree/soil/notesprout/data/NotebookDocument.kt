package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.InkDocument
import com.symmetricalpalmtree.soil.paper.ink.InkPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The notebook in memory, over [NotebookStore]. The screen owns the paper and the chrome; this
 * owns **which** page is showing and the page list; **what is on the page** is [InkDocument]'s.
 *
 * Mutations from the pen are synchronous and run on Main. Everything that reaches the store is
 * `suspend` and hops to IO. The screen serialises the suspending half behind its page-op lock.
 *
 * [goTo] reads the target page **first** and flushes the departing one **second**, so the swap
 * itself has no suspension point for a commit to fall into.
 */
class NotebookDocument(private val store: NotebookStore, private val onPagesChanged: suspend (Int) -> Unit) : InkPage {

    private val ink = InkDocument(NotebookSql, TAG)

    var pages: List<PageRef> = emptyList()
        private set

    private var current: PageRef? = null

    override val pageId: String get() = ink.pageId
    override val strokes: List<Stroke> get() = ink.strokes
    override val pageWidth: Float get() = current?.width ?: 0f
    override val pageHeight: Float get() = current?.height ?: 0f

    val pageCount: Int get() = pages.size
    val pageIndex: Int get() = pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)
    val pageNumber: Int get() = pageIndex + 1

    // ── Loading ──────

    suspend fun load(loaded: NotebookStore.Loaded) {
        pages = loaded.pages
        val page = pages.first { it.id == loaded.currentId }
        applyPage(page, withContext(Dispatchers.IO) { store.readPage(page) })
    }

    suspend fun goTo(page: PageRef) {
        if (page.id == pageId) return
        val next = withContext(Dispatchers.IO) { store.readPage(page) }
        flushUntilClean()
        applyPage(page, next)
        withContext(Dispatchers.IO) { store.setLastOpened(page.id) }
    }

    suspend fun goToIndex(index: Int) {
        goTo(pages.getOrNull(index) ?: return)
    }

    // ── Structure ──────

    suspend fun insert(after: Boolean): NotebookAction.Page {
        flushUntilClean()
        val before = pages
        val beforeCurrent = pageId
        val (next, page) = withContext(Dispatchers.IO) { store.insertPage(before, beforeCurrent, after) }
        pages = next
        applyPage(page, com.symmetricalpalmtree.soil.paper.ink.PageInk(page.width, page.height, emptyList()))
        onPagesChanged(pages.size)
        return NotebookAction.Page(before, next, emptyList(), beforeCurrent, page.id)
    }

    suspend fun deleteCurrent(): NotebookAction.Page {
        flushUntilClean()
        val before = pages
        val victim = current ?: error("no page")
        val (next, landing, taken) = withContext(Dispatchers.IO) { store.deletePage(before, victim) }
        pages = next
        applyPage(landing, withContext(Dispatchers.IO) { store.readPage(landing) })
        onPagesChanged(pages.size)
        return NotebookAction.Page(before, next, taken, victim.id, landing.id)
    }

    /** Everything on the page goes; the page stays. Null when the page was empty. */
    suspend fun eraseCurrent(): NotebookAction.PageErased? {
        flushUntilClean()
        val page = current ?: return null
        val gone = withContext(Dispatchers.IO) { store.erasePage(page.id) }
        if (gone.isEmpty()) return null
        applyPage(page, com.symmetricalpalmtree.soil.paper.ink.PageInk(page.width, page.height, emptyList()))
        return NotebookAction.PageErased(page.id, gone)
    }

    // ── Mutations (Main, synchronous) ──────

    override fun addStroke(stroke: Stroke) = ink.addStroke(stroke)
    override fun erase(ids: Collection<String>): InkAction.Erased? = ink.erase(ids)
    override fun move(ids: Collection<String>, dx: Float, dy: Float): InkAction.Moved? = ink.move(ids, dx, dy)

    // ── Saving ──────

    override suspend fun flushUntilClean(maxPasses: Int): Boolean =
        ink.flushUntilClean(maxPasses = maxPasses) { statements ->
            withContext(Dispatchers.IO) { store.execAll(statements) }
        }

    // ── Undo / redo ──────

    /** Reverse [a]: land on its page, replay in memory, write. */
    suspend fun revert(a: NotebookAction) {
        when (a) {
            is NotebookAction.Ink -> {
                if (!goToLiving(a.action.pageId)) return
                ink.revert(a.action)
                flushUntilClean()
            }
            is NotebookAction.Page -> reconcile(a.before, restore = a.contentIds, delete = emptyList(), currentId = a.beforeCurrent)
            is NotebookAction.PageErased -> {
                if (!goToLiving(a.pageId)) return
                flushUntilClean()
                withContext(Dispatchers.IO) { store.restoreIds(a.ids) }
                reloadCurrent()
            }
        }
    }

    suspend fun reapply(a: NotebookAction) {
        when (a) {
            is NotebookAction.Ink -> {
                if (!goToLiving(a.action.pageId)) return
                ink.reapply(a.action)
                flushUntilClean()
            }
            is NotebookAction.Page -> reconcile(a.after, restore = emptyList(), delete = a.contentIds, currentId = a.afterCurrent)
            is NotebookAction.PageErased -> {
                if (!goToLiving(a.pageId)) return
                flushUntilClean()
                withContext(Dispatchers.IO) { store.softDeleteIds(a.ids) }
                reloadCurrent()
            }
        }
    }

    /** The page showing, read again: its ink has just changed underneath. */
    private suspend fun reloadCurrent() {
        val page = current ?: return
        applyPage(page, withContext(Dispatchers.IO) { store.readPage(page) })
    }

    private suspend fun goToLiving(id: String): Boolean {
        val page = pages.firstOrNull { it.id == id }
        if (page == null) {
            Slog.d(TAG) { "replay skipped: the page is gone" }
            return false
        }
        goTo(page)
        return true
    }

    private suspend fun reconcile(target: List<PageRef>, restore: List<String>, delete: List<String>, currentId: String) {
        flushUntilClean()
        withContext(Dispatchers.IO) { store.reconcile(pages, target, restore, delete, currentId) }
        pages = target
        val landing = target.first { it.id == currentId }
        // Forced: the landing page may be the one showing, with its ink just changed underneath.
        applyPage(landing, withContext(Dispatchers.IO) { store.readPage(landing) })
        onPagesChanged(pages.size)
    }

    private fun applyPage(page: PageRef, read: com.symmetricalpalmtree.soil.paper.ink.PageInk) {
        current = page
        ink.reset(page.id, read.strokes)
    }

    private companion object { const val TAG = "NotebookDocument" }
}
