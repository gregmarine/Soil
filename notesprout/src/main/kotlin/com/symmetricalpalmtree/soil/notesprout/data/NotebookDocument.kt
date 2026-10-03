package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.notesprout.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.notesprout.clip.ObjectClip
import com.symmetricalpalmtree.soil.notesprout.clip.ObjectPlacement
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import com.symmetricalpalmtree.soil.notesprout.objects.PageSticky
import com.symmetricalpalmtree.soil.notesprout.objects.PageText
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.InkDocument
import com.symmetricalpalmtree.soil.paper.ink.InkPage
import com.symmetricalpalmtree.soil.paper.templates.BuiltInTemplates
import com.symmetricalpalmtree.soil.paper.templates.PagePaper
import com.symmetricalpalmtree.soil.paper.templates.PageTemplate
import com.symmetricalpalmtree.soil.paper.templates.PaperSource
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
class NotebookDocument(private val store: NotebookStore, private val onPagesChanged: suspend (List<PageRef>) -> Unit) : InkPage {

    private val ink = InkDocument(NotebookSql, TAG)

    var pages: List<PageRef> = emptyList()
        private set

    private var current: PageRef? = null

    /** The showing page's objects, each kind in z-order. Read on Main. */
    val headings: MutableMap<String, Heading> = linkedMapOf()
    val texts: MutableMap<String, PageText> = linkedMapOf()
    val stickies: MutableMap<String, PageSticky> = linkedMapOf()
    val links: MutableMap<String, PageLink> = linkedMapOf()

    /** Runs on Main after every change to the object maps: the screen re-hands them to the renderers. */
    var onObjectsChanged: () -> Unit = {}

    /** Size is derived, position authored: a heading or a text read from the file is measured
     *  again for this device as it is loaded. The screen supplies the measure; the row is
     *  corrected whenever the object is next written. */
    var measureHeading: ((Heading) -> Heading)? = null
    var measureText: ((PageText, Float) -> PageText)? = null

    /** The screen's density, for a link's underline band. */
    var density: Float = 1f

    override val pageId: String get() = ink.pageId
    override val strokes: List<Stroke> get() = ink.strokes
    override val pageWidth: Float get() = current?.width ?: 0f
    override val pageHeight: Float get() = current?.height ?: 0f
    val currentPage: PageRef? get() = current

    val pageCount: Int get() = pages.size
    val pageIndex: Int get() = pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)
    val pageNumber: Int get() = pageIndex + 1

    fun holdsObject(id: String): Boolean = id in headings || id in texts || id in stickies || id in links

    /** A sticky on the page, loose or wrapped in a link: a note is opened either way. */
    fun stickyById(id: String): PageSticky? = stickies[id] ?: links.values.firstNotNullOfOrNull { l -> l.stickies.firstOrNull { it.id == id } }

    /** The topmost sticky under ([x], [y]): a loose one sits above everything, a wrapped one draws with its link. */
    fun stickyAt(x: Float, y: Float): PageSticky? =
        stickies.values.lastOrNull { it.bounds.contains(x, y) }
            ?: links.values.reversed().firstNotNullOfOrNull { l -> l.stickies.lastOrNull { it.bounds.contains(x, y) } }

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
        onPagesChanged(pages)
        return NotebookAction.Page(before, next, emptyList(), beforeCurrent, page.id)
    }

    /**
     * A blank page beside [anchorId] (before or after it; at the end with no anchor) without
     * leaving the showing page: what the link picker's New page does. Not undoable; the caller
     * clears its history, since every page snapshot in it now names a list that is gone.
     */
    suspend fun insertPageQuietly(anchorId: String?, before: Boolean): PageRef {
        flushUntilClean()
        val before0 = pages
        val anchor = before0.firstOrNull { it.id == anchorId } ?: before0.last()
        val showing = pageId
        val (next, page) = withContext(Dispatchers.IO) {
            store.insertPage(before0, anchor.id, after = anchorId == null || !before).also { store.setLastOpened(showing) }
        }
        pages = next
        onPagesChanged(pages)
        return page
    }

    suspend fun deleteCurrent(): NotebookAction.Page {
        flushUntilClean()
        val before = pages
        val victim = current ?: error("no page")
        val (next, landing, taken) = withContext(Dispatchers.IO) { store.deletePage(before, victim) }
        pages = next
        applyPage(landing, withContext(Dispatchers.IO) { store.readPage(landing) })
        onPagesChanged(pages)
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

    // ── The clipboard ──────

    /** The showing page and everything on it, flushed first. Null when its row has gone. */
    suspend fun copyPage(): ClipEnvelope? {
        flushUntilClean()
        val page = current ?: return null
        return withContext(Dispatchers.IO) { store.capturePage(page) }
    }

    /** Paste [env]'s page before or after the showing one, and land on it. */
    suspend fun pastePage(env: ClipEnvelope, before: Boolean): NotebookAction.PagePasted {
        flushUntilClean()
        val before0 = pages
        val beforeCurrent = pageId
        val (next, page, contentIds) = withContext(Dispatchers.IO) { store.pasteAt(before0, beforeCurrent, env, before) }
        pages = next
        applyPage(page, withContext(Dispatchers.IO) { store.readPage(page) })
        onPagesChanged(pages)
        return NotebookAction.PagePasted(before0, next, contentIds, beforeCurrent, page.id)
    }

    /** The selection as rows, flushed first: the selected ink and objects of this page. */
    suspend fun copyObjects(strokeIds: Collection<String>, contentIds: Collection<String>): ClipEnvelope? {
        flushUntilClean()
        val strokeSet = strokeIds.toHashSet()
        val top = strokes.filter { it.id in strokeSet }.map { it.id } + contentIds.filter { holdsObject(it) }
        if (top.isEmpty()) return null
        return withContext(Dispatchers.IO) { store.captureObjects(top) }
    }

    /** Paste [env]'s objects onto the showing page, placed by [place]; the page is read again. */
    suspend fun pasteObjects(env: ClipEnvelope, place: (Bounds) -> ObjectPlacement.Offset): ObjectClip.Plan? {
        flushUntilClean()
        val page = pageId
        val plan = withContext(Dispatchers.IO) { store.pasteObjects(page, env, place) } ?: return null
        reloadCurrent()
        return plan
    }

    /** Ink from the pad onto the showing page; the page is read again. Answers the ids written. */
    suspend fun pasteStrokes(strokes: List<Stroke>): List<String> {
        flushUntilClean()
        val page = pageId
        withContext(Dispatchers.IO) { store.pasteStrokes(page, strokes) }
        reloadCurrent()
        return strokes.map { it.id }
    }

    // ── Paper ──────

    /** The showing page's paper as its token, `""` for blank, or null when its row has gone. */
    suspend fun currentTemplateToken(): String? {
        val page = current ?: return null
        return withContext(Dispatchers.IO) { PageTemplate.tokenOf(store.templateDigests(), page.templateId) }
    }

    /** The pixels under the showing page, or null for blank or a row that will not read. */
    suspend fun templateBlobOf(templateId: String): ByteArray? = withContext(Dispatchers.IO) { store.templateBlob(templateId) }

    class PaperRenderFailed : IllegalStateException("the paper would not draw")

    /**
     * Re-paper the showing page: reuse a row this file already holds for this paper at the
     * page's size, else render and mint one, then point the page at it. Null when the page
     * already shows that paper: a true no-op, no undo step. Paper that will not draw throws
     * [PaperRenderFailed] and writes nothing: the paper on the glass is never wiped for it.
     */
    suspend fun changeTemplate(paper: PaperSource, dpi: Float): NotebookAction.TemplateChanged? {
        val page = current ?: return null
        val token = PagePaper.token(paper)
        val target = if (token.isEmpty()) "" else withContext(Dispatchers.IO) {
            val w = page.width.toInt()
            val h = page.height.toInt()
            PageTemplate.reusableId(store.templateDigests(), token, w, h, prefer = page.templateId)?.also { Slog.d(TAG) { "re-paper reuses a template row" } }
                ?: run {
                    val bitmap = PagePaper.render(paper, w, h, dpi) ?: throw PaperRenderFailed()
                    val blob = try { BuiltInTemplates.toWebp(bitmap) } finally { bitmap.recycle() }
                    Slog.d(TAG) { "re-paper mints a template row (${blob.size} B)" }
                    store.mintTemplate(token, w, h, blob)
                }
        }
        if (target == page.templateId) return null
        applyTemplate(page.id, target)
        return NotebookAction.TemplateChanged(page.id, page.templateId, target)
    }

    /** Point [pageId] at [templateId] (`""` = blank) in the file and in the page list. The
     *  screen reloads the paper on its next show. */
    suspend fun applyTemplate(pageId: String, templateId: String) {
        withContext(Dispatchers.IO) { store.setPageTemplate(pageId, templateId) }
        pages = pages.map { if (it.id == pageId) it.copy(templateId = templateId) else it }
        if (current?.id == pageId) current = current?.copy(templateId = templateId)
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
     *  sticky's content read first (a wrapped one's too) so an undo can bring it back. */
    suspend fun deleteObjects(contentIds: Collection<String>): DeletedObjects {
        val headingIds = contentIds.filter { it in headings }
        val textIds = contentIds.filter { it in texts }
        val stickyIcons = contentIds.mapNotNull { stickies[it] }
        val linked = contentIds.mapNotNull { links[it] }
        val gone = withContext(Dispatchers.IO) {
            val full = store.withContent(stickyIcons)
            val fullLinks = store.linksWithContent(linked)
            store.deleteObjects(headingIds + textIds + stickyIcons.map { it.id } + linked.map { it.id }, stickyIcons.map { it.id }, linked.map { it.id })
            DeletedObjects(headingIds, textIds, full, fullLinks)
        }
        headingIds.forEach { headings.remove(it) }
        textIds.forEach { texts.remove(it) }
        stickyIcons.forEach { stickies.remove(it.id) }
        linked.forEach { links.remove(it.id) }
        onObjectsChanged()
        return gone
    }

    /** The objects among [contentIds], by kind. */
    class Moved(val headingIds: List<String>, val textIds: List<String>, val stickyIds: List<String>, val linkIds: List<String> = emptyList()) {
        val isEmpty: Boolean get() = headingIds.isEmpty() && textIds.isEmpty() && stickyIds.isEmpty() && linkIds.isEmpty()
        /** The ids whose rows shift by their own columns; a link's children are the store's. */
        val boxIds: List<String> get() = headingIds + textIds + stickyIds
    }

    /** The in-memory half of a finished drag, on Main, synchronous: the working copies shift so the
     *  engine's next record shows them where they landed. [writeMove] follows on IO. */
    fun translateObjects(contentIds: Collection<String>, dx: Float, dy: Float): Moved {
        val moved = Moved(
            contentIds.filter { it in headings }, contentIds.filter { it in texts }, contentIds.filter { it in stickies },
            contentIds.filter { it in links },
        )
        if (!moved.isEmpty && !(dx == 0f && dy == 0f)) translateObjects(moved.headingIds, moved.textIds, moved.stickyIds, moved.linkIds, dx, dy)
        return moved
    }

    /** The row half of [translateObjects]. */
    suspend fun writeMove(moved: Moved, dx: Float, dy: Float) {
        if (moved.isEmpty || (dx == 0f && dy == 0f)) return
        withContext(Dispatchers.IO) {
            store.moveBy(moved.boxIds, dx, dy)
            store.moveLinks(moved.linkIds, dx, dy)
        }
    }

    private fun translateObjects(headingIds: List<String>, textIds: List<String>, stickyIds: List<String>, linkIds: List<String>, dx: Float, dy: Float) {
        headingIds.forEach { id -> headings[id]?.let { headings[id] = it.translated(dx, dy) } }
        textIds.forEach { id -> texts[id]?.let { texts[id] = it.translated(dx, dy) } }
        stickyIds.forEach { id -> stickies[id]?.let { stickies[id] = it.translated(dx, dy) } }
        linkIds.forEach { id -> links[id]?.let { links[id] = it.translated(dx, dy) } }
        onObjectsChanged()
    }

    // ── Links ──────

    /**
     * Wrap a selection of the showing page into a link pointing at [payload]. The working copies
     * are gathered here, on Main; the rows are re-parented on IO; then the page is read again,
     * since the wrapped ink leaves the page's own ink. Null when the selection holds nothing of
     * this page's.
     */
    suspend fun wrap(strokeIds: Collection<String>, contentIds: Collection<String>, payload: String): PageLink? {
        val strokeSet = strokeIds.toHashSet()
        val wrappedStrokes = strokes.filter { it.id in strokeSet }
        val wrappedHeadings = contentIds.mapNotNull { headings[it] }
        val wrappedTexts = contentIds.mapNotNull { texts[it] }
        val wrappedStickies = contentIds.mapNotNull { stickies[it] }
        val b = PageLink.unionBounds(wrappedStrokes, wrappedHeadings, wrappedTexts, wrappedStickies, density) ?: return null
        val link = PageLink(
            id = NotebookStore.newId(), payload = payload, chrome = LinkPayload.chromeOf(payload),
            x = b.left, y = b.top, width = b.right - b.left, height = b.bottom - b.top, order = 0,
            strokes = wrappedStrokes, headings = wrappedHeadings, texts = wrappedTexts, stickies = wrappedStickies,
        )
        flushUntilClean()
        val placed = withContext(Dispatchers.IO) { store.createLink(pageId, link) }
        reloadCurrent()
        return placed
    }

    suspend fun unlink(link: PageLink) {
        flushUntilClean()
        withContext(Dispatchers.IO) { store.unlink(pageId, link) }
        reloadCurrent()
    }

    suspend fun setLinkPayload(link: PageLink, payload: String) {
        val after = link.copy(payload = payload, chrome = LinkPayload.chromeOf(payload))
        withContext(Dispatchers.IO) { store.setLinkPayload(pageId, after) }
        links[after.id] = after
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
                if (a.objects.links.isNotEmpty()) store.remirrorPage(a.pageId)
            }
            is NotebookAction.Moved -> objects(a.pageId) {
                a.ink?.let { ink.revert(it) }
                store.moveBy(a.headingIds + a.textIds + a.stickyIds, -a.dx, -a.dy)
                store.moveLinks(a.linkIds, -a.dx, -a.dy)
            }
            is NotebookAction.HeadingCreated -> objects(a.pageId) { store.deleteObjects(listOf(a.heading.id), emptyList()) }
            is NotebookAction.HeadingEdited -> objects(a.pageId) { store.setHeadingContent(a.before) }
            is NotebookAction.TextCreated -> objects(a.pageId) { store.deleteObjects(listOf(a.text.id), emptyList()) }
            is NotebookAction.TextEdited -> objects(a.pageId) { store.setTextContent(a.before) }
            is NotebookAction.StickyInserted -> objects(a.pageId) { store.deleteObjects(listOf(a.sticky.id), listOf(a.sticky.id)) }
            is NotebookAction.StickyContentEdited -> objects(a.pageId) { store.setStickyContent(a.stickyId, a.before) }
            is NotebookAction.LinkCreated -> objects(a.pageId) { store.unlink(a.pageId, a.link) }
            is NotebookAction.LinkUnlinked -> objects(a.pageId) { store.relink(a.pageId, a.link) }
            is NotebookAction.LinkEdited -> objects(a.pageId) { setPayloadOf(a.linkId, a.before) }
            is NotebookAction.TemplateChanged -> if (goToLiving(a.pageId)) applyTemplate(a.pageId, a.from)
            is NotebookAction.PageErased -> objects(a.pageId) { store.restoreIds(a.ids); store.remirrorPage(a.pageId) }
            is NotebookAction.Page -> reconcile(a.before, restore = a.contentIds, delete = emptyList(), currentId = a.beforeCurrent)
            is NotebookAction.PagePasted -> reconcile(a.before, restore = emptyList(), delete = a.contentIds, currentId = a.beforeCurrent)
            is NotebookAction.ObjectsPasted -> objects(a.pageId) { store.softDeleteIds(a.contentIds); store.remirrorPage(a.pageId) }
        }
    }

    suspend fun reapply(a: NotebookAction) {
        when (a) {
            is NotebookAction.Ink -> ink(a.action.pageId) { ink.reapply(a.action) }
            is NotebookAction.Deleted -> objects(a.pageId) {
                a.ink?.let { ink.reapply(it) }
                store.deleteObjects(a.objects.ids, emptyList(), a.objects.links.map { it.id })
            }
            is NotebookAction.Moved -> objects(a.pageId) {
                a.ink?.let { ink.reapply(it) }
                store.moveBy(a.headingIds + a.textIds + a.stickyIds, a.dx, a.dy)
                store.moveLinks(a.linkIds, a.dx, a.dy)
            }
            is NotebookAction.HeadingCreated -> objects(a.pageId) { store.restoreHeading(a.pageId, a.heading) }
            is NotebookAction.HeadingEdited -> objects(a.pageId) { store.setHeadingContent(a.after) }
            is NotebookAction.TextCreated -> objects(a.pageId) { store.restoreText(a.pageId, a.text) }
            is NotebookAction.TextEdited -> objects(a.pageId) { store.setTextContent(a.after) }
            is NotebookAction.StickyInserted -> objects(a.pageId) { store.restoreSticky(a.pageId, a.sticky) }
            is NotebookAction.StickyContentEdited -> objects(a.pageId) { store.setStickyContent(a.stickyId, a.after) }
            is NotebookAction.LinkCreated -> objects(a.pageId) { store.relink(a.pageId, a.link) }
            is NotebookAction.LinkUnlinked -> objects(a.pageId) { store.unlink(a.pageId, a.link) }
            is NotebookAction.LinkEdited -> objects(a.pageId) { setPayloadOf(a.linkId, a.after) }
            is NotebookAction.TemplateChanged -> if (goToLiving(a.pageId)) applyTemplate(a.pageId, a.to)
            is NotebookAction.PageErased -> objects(a.pageId) { store.softDeleteIds(a.ids); store.remirrorPage(a.pageId) }
            is NotebookAction.Page -> reconcile(a.after, restore = emptyList(), delete = a.contentIds, currentId = a.afterCurrent)
            is NotebookAction.PagePasted -> reconcile(a.after, restore = a.contentIds, delete = emptyList(), currentId = a.afterCurrent)
            is NotebookAction.ObjectsPasted -> objects(a.pageId) { store.restoreIds(a.contentIds); store.remirrorPage(a.pageId) }
        }
    }

    /** A replay of a payload edit: the link is on the page by now (the replay landed there). */
    private fun setPayloadOf(linkId: String, payload: String) {
        val link = links[linkId] ?: return
        store.setLinkPayload(pageId, link.copy(payload = payload, chrome = LinkPayload.chromeOf(payload)))
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
        onPagesChanged(pages)
    }

    private fun applyPage(page: PageRef, read: PageContent) {
        current = page
        ink.reset(page.id, read.strokes)
        headings.clear(); read.headings.forEach { headings[it.id] = measureHeading?.invoke(it) ?: it }
        texts.clear(); read.texts.forEach { texts[it.id] = measureText?.invoke(it, page.width) ?: it }
        stickies.clear(); read.stickies.forEach { stickies[it.id] = it }
        links.clear()
        read.links.forEach { l ->
            links[l.id] = l.remeasured(
                { h -> measureHeading?.invoke(h) ?: h },
                { t -> measureText?.invoke(t, page.width) ?: t },
                density,
            )
        }
        onObjectsChanged()
    }

    private companion object { const val TAG = "NotebookDocument" }
}
