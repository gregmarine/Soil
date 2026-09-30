package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import com.symmetricalpalmtree.soil.notesprout.objects.PageSticky
import com.symmetricalpalmtree.soil.notesprout.objects.PageText
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.InkDocument
import com.symmetricalpalmtree.soil.paper.ink.InkPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The notebook in memory, over [NotebookStore]. The screen owns the paper and the chrome; this
 * owns **which** page is showing, the page list, and the objects on the page; **what ink is on
 * the page** is [InkDocument]'s.
 *
 * Ink mutations from the pen are synchronous and op-logged, flushed on the debounce. Object
 * mutations write their rows at once, on IO: an object is a row from the moment it is made. The
 * screen serialises everything that suspends behind its page-op lock.
 *
 * [goTo] reads the target page **first** and flushes the departing one **second**, so the swap
 * itself has no suspension point for a commit to fall into.
 */
class NotebookDocument(private val store: NotebookStore, private val onPagesChanged: suspend (Int) -> Unit) : InkPage {

    private val ink = InkDocument(NotebookSql, TAG)

    var pages: List<PageRef> = emptyList()
        private set

    private var current: PageRef? = null

    /** The showing page's objects, each kind in z-order. Read on Main. */
    val headings: MutableMap<String, Heading> = linkedMapOf()
    val texts: MutableMap<String, PageText> = linkedMapOf()
    val stickies: MutableMap<String, PageSticky> = linkedMapOf()

    /** Runs on Main after every change to the object maps: the screen re-hands them to the renderers. */
    var onObjectsChanged: () -> Unit = {}

    /** Size is derived, position authored: a heading or a text read from the file is measured
     *  again for this device as it is loaded. The screen supplies the measure; the row is
     *  corrected whenever the object is next written. */
    var measureHeading: ((Heading) -> Heading)? = null
    var measureText: ((PageText, Float) -> PageText)? = null

    override val pageId: String get() = ink.pageId
    override val strokes: List<Stroke> get() = ink.strokes
    override val pageWidth: Float get() = current?.width ?: 0f
    override val pageHeight: Float get() = current?.height ?: 0f
    val currentPage: PageRef? get() = current

    val pageCount: Int get() = pages.size
    val pageIndex: Int get() = pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)
    val pageNumber: Int get() = pageIndex + 1

    fun holdsObject(id: String): Boolean = id in headings || id in texts || id in stickies

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

    /** The page showing, read again: its rows have just changed underneath. */
    suspend fun reloadCurrent() {
        val page = current ?: return
        flushUntilClean()
        applyPage(page, withContext(Dispatchers.IO) { store.readPage(page) })
    }

    // ── Structure ──────

    suspend fun insert(after: Boolean): NotebookAction.Page {
        flushUntilClean()
        val before = pages
        val beforeCurrent = pageId
        val (next, page) = withContext(Dispatchers.IO) { store.insertPage(before, beforeCurrent, after) }
        pages = next
        applyPage(page, PageContent.EMPTY)
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
        applyPage(page, PageContent.EMPTY)
        return NotebookAction.PageErased(page.id, gone)
    }

    // ── Ink (Main, synchronous) ──────

    override fun addStroke(stroke: Stroke) = ink.addStroke(stroke)
    override fun erase(ids: Collection<String>): InkAction.Erased? = ink.erase(ids)
    override fun move(ids: Collection<String>, dx: Float, dy: Float): InkAction.Moved? = ink.move(ids, dx, dy)

    override suspend fun flushUntilClean(maxPasses: Int): Boolean =
        ink.flushUntilClean(maxPasses = maxPasses) { statements -> withContext(Dispatchers.IO) { store.execAll(statements) } }

    // ── Objects (suspend: the row is written at once, on IO) ──────

    suspend fun createHeading(h: Heading): Heading {
        val placed = withContext(Dispatchers.IO) { store.createHeading(pageId, h) }
        headings[placed.id] = placed
        onObjectsChanged()
        return placed
    }

    suspend fun createText(t: PageText): PageText {
        val placed = withContext(Dispatchers.IO) { store.createText(pageId, t) }
        texts[placed.id] = placed
        onObjectsChanged()
        return placed
    }

    suspend fun createSticky(st: PageSticky): PageSticky {
        val placed = withContext(Dispatchers.IO) { store.createSticky(pageId, st) }
        stickies[placed.id] = placed
        onObjectsChanged()
        return placed
    }

    suspend fun updateHeading(h: Heading) {
        withContext(Dispatchers.IO) { store.setHeadingContent(h) }
        headings[h.id] = h
        onObjectsChanged()
    }

    suspend fun updateText(t: PageText) {
        withContext(Dispatchers.IO) { store.setTextContent(t) }
        texts[t.id] = t
        onObjectsChanged()
    }

    /** Take [contentIds] off the page, whatever kinds they are. Answers what went, with each
     *  sticky's content read first so an undo can bring it back. */
    suspend fun deleteObjects(contentIds: Collection<String>): DeletedObjects {
        val headingIds = contentIds.filter { it in headings }
        val textIds = contentIds.filter { it in texts }
        val stickyIcons = contentIds.mapNotNull { stickies[it] }
        val gone = withContext(Dispatchers.IO) {
            val full = store.withContent(stickyIcons)
            store.deleteObjects(headingIds + textIds + stickyIcons.map { it.id }, stickyIcons.map { it.id })
            DeletedObjects(headingIds, textIds, full)
        }
        headingIds.forEach { headings.remove(it) }
        textIds.forEach { texts.remove(it) }
        stickyIcons.forEach { stickies.remove(it.id) }
        onObjectsChanged()
        return gone
    }

    /** The objects among [contentIds], by kind. */
    class Moved(val headingIds: List<String>, val textIds: List<String>, val stickyIds: List<String>) {
        val isEmpty: Boolean get() = headingIds.isEmpty() && textIds.isEmpty() && stickyIds.isEmpty()
        val ids: List<String> get() = headingIds + textIds + stickyIds
    }

    /** The in-memory half of a finished drag, on Main, synchronous: the working copies shift so the
     *  engine's next record shows them where they landed. [writeMove] follows on IO. */
    fun translateObjects(contentIds: Collection<String>, dx: Float, dy: Float): Moved {
        val moved = Moved(contentIds.filter { it in headings }, contentIds.filter { it in texts }, contentIds.filter { it in stickies })
        if (!moved.isEmpty && !(dx == 0f && dy == 0f)) translateObjects(moved.headingIds, moved.textIds, moved.stickyIds, dx, dy)
        return moved
    }

    /** The row half of [translateObjects]. */
    suspend fun writeMove(moved: Moved, dx: Float, dy: Float) {
        if (moved.isEmpty || (dx == 0f && dy == 0f)) return
        withContext(Dispatchers.IO) { store.moveBy(moved.ids, dx, dy) }
    }

    private fun translateObjects(headingIds: List<String>, textIds: List<String>, stickyIds: List<String>, dx: Float, dy: Float) {
        headingIds.forEach { id -> headings[id]?.let { headings[id] = it.translated(dx, dy) } }
        textIds.forEach { id -> texts[id]?.let { texts[id] = it.translated(dx, dy) } }
        stickyIds.forEach { id -> stickies[id]?.let { stickies[id] = it.translated(dx, dy) } }
        onObjectsChanged()
    }

    suspend fun stickyContent(stickyId: String): List<Stroke> = withContext(Dispatchers.IO) { store.stickyContent(stickyId) }

    suspend fun setStickyContent(stickyId: String, strokes: List<Stroke>) =
        withContext(Dispatchers.IO) { store.setStickyContent(stickyId, strokes) }

    suspend fun allHeadings(): List<Pair<Heading, String>> = withContext(Dispatchers.IO) { store.allHeadings() }

    // ── Undo / redo ──────

    /** Reverse [a]: land on its page, replay, write, and read the page again where objects changed. */
    suspend fun revert(a: NotebookAction) {
        when (a) {
            is NotebookAction.Ink -> ink(a.action.pageId) { ink.revert(a.action) }
            is NotebookAction.Deleted -> objects(a.pageId) {
                a.ink?.let { ink.revert(it) }
                store.restoreIds(a.objects.ids)
            }
            is NotebookAction.Moved -> objects(a.pageId) {
                a.ink?.let { ink.revert(it) }
                store.moveBy(a.headingIds + a.textIds + a.stickyIds, -a.dx, -a.dy)
            }
            is NotebookAction.HeadingCreated -> objects(a.pageId) { store.deleteObjects(listOf(a.heading.id), emptyList()) }
            is NotebookAction.HeadingEdited -> objects(a.pageId) { store.setHeadingContent(a.before) }
            is NotebookAction.TextCreated -> objects(a.pageId) { store.deleteObjects(listOf(a.text.id), emptyList()) }
            is NotebookAction.TextEdited -> objects(a.pageId) { store.setTextContent(a.before) }
            is NotebookAction.StickyInserted -> objects(a.pageId) { store.deleteObjects(listOf(a.sticky.id), listOf(a.sticky.id)) }
            is NotebookAction.StickyContentEdited -> objects(a.pageId) { store.setStickyContent(a.stickyId, a.before) }
            is NotebookAction.PageErased -> objects(a.pageId) { store.restoreIds(a.ids) }
            is NotebookAction.Page -> reconcile(a.before, restore = a.contentIds, delete = emptyList(), currentId = a.beforeCurrent)
        }
    }

    suspend fun reapply(a: NotebookAction) {
        when (a) {
            is NotebookAction.Ink -> ink(a.action.pageId) { ink.reapply(a.action) }
            is NotebookAction.Deleted -> objects(a.pageId) {
                a.ink?.let { ink.reapply(it) }
                store.softDeleteIds(a.objects.ids)
            }
            is NotebookAction.Moved -> objects(a.pageId) {
                a.ink?.let { ink.reapply(it) }
                store.moveBy(a.headingIds + a.textIds + a.stickyIds, a.dx, a.dy)
            }
            is NotebookAction.HeadingCreated -> objects(a.pageId) { store.restoreHeading(a.pageId, a.heading) }
            is NotebookAction.HeadingEdited -> objects(a.pageId) { store.setHeadingContent(a.after) }
            is NotebookAction.TextCreated -> objects(a.pageId) { store.restoreText(a.pageId, a.text) }
            is NotebookAction.TextEdited -> objects(a.pageId) { store.setTextContent(a.after) }
            is NotebookAction.StickyInserted -> objects(a.pageId) { store.restoreSticky(a.pageId, a.sticky) }
            is NotebookAction.StickyContentEdited -> objects(a.pageId) { store.setStickyContent(a.stickyId, a.after) }
            is NotebookAction.PageErased -> objects(a.pageId) { store.softDeleteIds(a.ids) }
            is NotebookAction.Page -> reconcile(a.after, restore = emptyList(), delete = a.contentIds, currentId = a.afterCurrent)
        }
    }

    /** An ink-only replay: in memory on the page, then flushed. */
    private suspend fun ink(pageId: String, replay: () -> Unit) {
        if (!goToLiving(pageId)) return
        replay()
        flushUntilClean()
    }

    /** A replay that touches rows: the ink half first (in memory), then the rows on IO, then the
     *  page read again so what shows is what a reopen would show. */
    private suspend fun objects(pageId: String, replay: suspend () -> Unit) {
        if (!goToLiving(pageId)) return
        flushUntilClean()
        withContext(Dispatchers.IO) { replay() }
        reloadCurrent()
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
        applyPage(landing, withContext(Dispatchers.IO) { store.readPage(landing) })
        onPagesChanged(pages.size)
    }

    private fun applyPage(page: PageRef, read: PageContent) {
        current = page
        ink.reset(page.id, read.strokes)
        headings.clear(); read.headings.forEach { headings[it.id] = measureHeading?.invoke(it) ?: it }
        texts.clear(); read.texts.forEach { texts[it.id] = measureText?.invoke(it, page.width) ?: it }
        stickies.clear(); read.stickies.forEach { stickies[it.id] = it }
        onObjectsChanged()
    }

    private companion object { const val TAG = "NotebookDocument" }
}
