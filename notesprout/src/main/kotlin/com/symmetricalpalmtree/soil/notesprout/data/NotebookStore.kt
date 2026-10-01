package com.symmetricalpalmtree.soil.notesprout.data

import android.util.Log
import com.symmetricalpalmtree.soil.paper.chrome.PageMath
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import com.symmetricalpalmtree.soil.notesprout.objects.LinkRows
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import com.symmetricalpalmtree.soil.notesprout.objects.ObjectRows
import com.symmetricalpalmtree.soil.notesprout.objects.PageSticky
import com.symmetricalpalmtree.soil.notesprout.objects.PageText
import com.symmetricalpalmtree.soil.paper.ink.InkStore
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import java.util.UUID

/** One page as the notebook lists it. [templateId] is a template row's id, or `""` for blank. */
data class PageRef(val id: String, val width: Float, val height: Float, val templateId: String)

/**
 * The notebook table over its [RowStore], on `:paper`'s [InkStore] base. **Blocking**: every call
 * runs on `Dispatchers.IO`, never Main.
 *
 * Every SQL string lives in [NotebookSql]; every write goes through [execAll], one transaction.
 * Any failure at all becomes `StoreUnavailable`, which is what the screen answers to.
 */
class NotebookStore(store: RowStore, private val notebookId: String) : InkStore(store, TAG) {

    class Loaded(val pages: List<PageRef>, val currentId: String)

    /**
     * A notebook file that has never held a page is given its root row and one blank page, the
     * size of the surface. Only the flow that makes a notebook calls this; an open never does.
     */
    fun initialize(name: String, width: Float, height: Float): Loaded {
        val now = System.currentTimeMillis()
        val pageId = newId()
        execAll(
            listOf(
                NotebookSql.insertRoot(notebookId, name, now),
                NotebookSql.insertPage(pageId, notebookId, 0, width, height, "", now),
                NotebookSql.setLastOpened(notebookId, pageId, now),
            ),
        )
        return Loaded(listOf(PageRef(pageId, width, height, "")), pageId)
    }

    /**
     * The page list and the page last open. A notebook with no pages is refused: nothing is
     * fabricated here. A last-open page that is gone lands on the first.
     */
    fun load(): Loaded = guard {
        val pages = store.query(NotebookSql.selectPages(notebookId)).rows.map {
            PageRef(
                id = it.text("id"),
                width = it.realOrNull("width")?.toFloat() ?: 0f,
                height = it.realOrNull("height")?.toFloat() ?: 0f,
                templateId = it.textOrNull("refId").orEmpty(),
            )
        }
        if (pages.isEmpty()) throw NoPages()
        val last = store.query(NotebookSql.selectRoot(notebookId)).rows.firstOrNull()?.textOrNull("refId")
        Loaded(pages, if (pages.any { it.id == last }) last!! else pages[0].id)
    }

    /** One page: its ink in writing order, every object on it, and its links with what they wrap. */
    fun readPage(page: PageRef): PageContent = guard {
        val loose = readObjectsOf(page.id)
        PageContent(readStrokesOf(page.id), loose.headings, loose.texts, loose.stickies, readLinksOf(page.id))
    }

    private class Objects(val headings: List<Heading>, val texts: List<PageText>, val stickies: List<PageSticky>)

    /** The objects parented to [parentId]: a page's own, or what a link wraps. */
    private fun readObjectsOf(parentId: String): Objects {
        val headings = ArrayList<Heading>()
        val texts = ArrayList<PageText>()
        val stickies = ArrayList<PageSticky>()
        var dropped = 0
        for (row in store.query(NotebookSql.selectObjects(parentId)).rows) {
            val kept = when (row.text("type")) {
                NotebookSchema.TYPE_HEADING -> ObjectRows.toHeading(row)?.also { headings += it }
                NotebookSchema.TYPE_TEXT -> ObjectRows.toText(row)?.also { texts += it }
                NotebookSchema.TYPE_STICKY -> ObjectRows.toSticky(row)?.also { stickies += it }
                else -> null
            }
            if (kept == null) dropped++
        }
        if (dropped > 0) Log.w(TAG, "$dropped object row(s) would not read")
        return Objects(headings, texts, stickies)
    }

    /** The page's links in z-order, each with its wrapped ink and objects. A row that will not
     *  read is dropped, and the page still shows. */
    fun readLinksOf(pageId: String): List<PageLink> {
        val links = ArrayList<PageLink>()
        var dropped = 0
        for (row in store.query(NotebookSql.selectLinks(pageId)).rows) {
            val id = row.textOrNull("id") ?: run { dropped++; continue }
            val under = readObjectsOf(id)
            val link = LinkRows.toLink(row, readStrokesOf(id).map { it.second }, under.headings, under.texts, under.stickies)
            if (link == null) dropped++ else links += link
        }
        if (dropped > 0) Log.w(TAG, "$dropped link row(s) would not read")
        return links
    }

    /** The live strokes parented to [parentId]: a page's ink, or a sticky note's content. */
    fun readStrokesOf(parentId: String): List<Pair<Long, Stroke>> {
        val rows = store.query(NotebookSql.selectStrokes(parentId)).rows
        val strokes = ArrayList<Pair<Long, Stroke>>(rows.size)
        var dropped = 0
        for (row in rows) {
            val decoded = NotebookStrokeRows.decode(row)
            if (decoded == null) dropped++ else strokes += decoded
        }
        if (dropped > 0) Log.w(TAG, "$dropped stroke row(s) would not read")
        return strokes
    }

    /** Every live heading in the notebook with the id of what it hangs under, for the Contents. */
    fun allHeadings(): List<Pair<Heading, String>> = guard {
        store.query(NotebookSql.selectAllHeadings()).rows.mapNotNull { row ->
            ObjectRows.toHeading(row)?.let { it to row.text("parentId") }
        }
    }

    // ── Objects ──────

    private fun nextOrder(parentId: String, type: String): Int =
        store.query(NotebookSql.selectMaxOrder(parentId, type)).rows.firstOrNull()?.long("m")?.toInt()?.plus(1) ?: 0

    fun createHeading(pageId: String, h: Heading): Heading = guard {
        val placed = h.copy(order = nextOrder(pageId, NotebookSchema.TYPE_HEADING))
        run(listOf(NotebookSql.insertHeading(placed, pageId, placed.order, System.currentTimeMillis())))
        placed
    }

    fun createText(pageId: String, t: PageText): PageText = guard {
        val placed = t.copy(order = nextOrder(pageId, NotebookSchema.TYPE_TEXT))
        run(listOf(NotebookSql.insertText(placed, pageId, placed.order, System.currentTimeMillis())))
        placed
    }

    fun createSticky(pageId: String, st: PageSticky): PageSticky = guard {
        val placed = st.copy(order = nextOrder(pageId, NotebookSchema.TYPE_STICKY))
        run(listOf(NotebookSql.insertSticky(placed, pageId, placed.order, System.currentTimeMillis())))
        placed
    }

    /** A row that was soft-deleted revives in place; one that is gone (a paste's undo) is put back. */
    fun restoreHeading(pageId: String, h: Heading) =
        execAll(listOf(NotebookSql.insertHeading(h, pageId, h.order, System.currentTimeMillis()), NotebookSql.restore(h.id)))

    fun restoreText(pageId: String, t: PageText) =
        execAll(listOf(NotebookSql.insertText(t, pageId, t.order, System.currentTimeMillis()), NotebookSql.restore(t.id)))

    /** The icon revives (or returns), and the snapshot's children with it. */
    fun restoreSticky(pageId: String, st: PageSticky) = execAll(
        listOf(NotebookSql.insertSticky(st, pageId, st.order, System.currentTimeMillis()), NotebookSql.restore(st.id)) +
            st.childIds.map { NotebookSql.restore(it) },
    )

    fun moveBy(ids: Collection<String>, dx: Float, dy: Float) {
        if (ids.isEmpty() || (dx == 0f && dy == 0f)) return
        val now = System.currentTimeMillis()
        execAll(ids.map { NotebookSql.moveBy(it, dx, dy, now) })
    }

    fun setHeadingContent(h: Heading) = execAll(listOf(NotebookSql.setHeadingContent(h, System.currentTimeMillis())))

    fun setTextContent(t: PageText) = execAll(listOf(NotebookSql.setTextContent(t, System.currentTimeMillis())))

    /** A note's content in writing order, in its own space. */
    fun stickyContent(stickyId: String): List<Stroke> = guard { readStrokesOf(stickyId).map { it.second } }

    /** Every sticky in [stickies] with its content read: the snapshot a delete carries. */
    fun withContent(stickies: List<PageSticky>): List<PageSticky> = guard {
        stickies.map { it.copy(strokes = readStrokesOf(it.id).map { s -> s.second }) }
    }

    /**
     * Soft-delete [ids]; for a sticky among them, its content too; for a link among [linkIds],
     * everything under it at any depth, and its mirror row. One transaction.
     */
    fun deleteObjects(ids: Collection<String>, stickyIds: Collection<String>, linkIds: Collection<String> = emptyList()) = guard {
        val now = System.currentTimeMillis()
        val children = stickyIds.flatMap { id ->
            store.query(NotebookSql.selectLiveChildIds(id, NotebookSchema.TYPE_STROKE)).rows.map { it.text("id") }
        } + linkIds.flatMap { id ->
            store.query(NotebookSql.selectLiveDescendantIds(id)).rows.map { it.text("id") }
        }
        val all = (ids + children).distinct()
        val statements = all.map { NotebookSql.softDelete(it, now) } + linkIds.map { NotebookSql.mirrorDrop(it) }
        if (statements.isNotEmpty()) run(statements)
    }

    // ── Links ──────

    /**
     * Wrap: the link row at the next z-order and every child re-parented to it, with the mirror
     * row, in one transaction. The children keep their ids and their page coordinates.
     */
    fun createLink(pageId: String, l: PageLink): PageLink = guard {
        val now = System.currentTimeMillis()
        val placed = l.copy(order = nextOrder(pageId, NotebookSchema.TYPE_LINK))
        run(
            listOf(NotebookSql.insertLink(placed, pageId, placed.order, now)) +
                placed.childIds.map { NotebookSql.reparent(it, placed.id, now) } +
                NotebookSql.mirror(placed, pageId, notebookId),
        )
        placed
    }

    /** Unwrap: the children are the page's again, the link row is soft-deleted, its mirror row goes. */
    fun unlink(pageId: String, l: PageLink) {
        val now = System.currentTimeMillis()
        execAll(
            l.childIds.map { NotebookSql.reparent(it, pageId, now) } +
                NotebookSql.softDelete(l.id, now) + NotebookSql.mirrorDrop(l.id),
        )
    }

    /** The redo of a wrap, or the undo of an unlink: the row revives in place at the order it
     *  held (or is put back), and the same children come under it again. */
    fun relink(pageId: String, l: PageLink) {
        val now = System.currentTimeMillis()
        execAll(
            listOf(NotebookSql.insertLink(l, pageId, l.order, now), NotebookSql.restore(l.id)) +
                l.childIds.map { NotebookSql.reparent(it, l.id, now) } +
                NotebookSql.mirror(l, pageId, notebookId),
        )
    }

    /** Where the link points, rewritten, and its mirror row with it. */
    fun setLinkPayload(pageId: String, l: PageLink) = execAll(
        listOf(NotebookSql.setLinkPayload(l.id, l.payload, System.currentTimeMillis()), NotebookSql.mirror(l, pageId, notebookId)),
    )

    /**
     * Translate links by id, row and wrapped children alike: an undo replay has only ids. Box
     * children shift by their columns; wrapped ink lives in a blob and is read, moved and put
     * again. A wrapped sticky's content is in its own space and does not move.
     */
    fun moveLinks(linkIds: Collection<String>, dx: Float, dy: Float) = guard {
        if (linkIds.isEmpty() || (dx == 0f && dy == 0f)) return@guard
        val now = System.currentTimeMillis()
        val statements = ArrayList<Statement>()
        for (linkId in linkIds) {
            statements += NotebookSql.moveBy(linkId, dx, dy, now)
            for ((order, stroke) in readStrokesOf(linkId)) statements += NotebookSql.putStroke(linkId, order, stroke.translated(dx, dy), now)
            for (row in store.query(NotebookSql.selectObjects(linkId)).rows) statements += NotebookSql.moveBy(row.text("id"), dx, dy, now)
        }
        run(statements)
    }

    /** Make the page's mirror rows exactly its live links: after a restore brought links back. */
    fun remirrorPage(pageId: String) = guard {
        val links = readLinksOf(pageId)
        run(listOf(NotebookSql.mirrorDropPage(pageId)) + links.map { NotebookSql.mirror(it, pageId, notebookId) })
    }

    /** Every link in [links] with each wrapped sticky's content read: the snapshot a delete carries. */
    fun linksWithContent(links: List<PageLink>): List<PageLink> = guard {
        links.map { l -> if (l.stickies.isEmpty()) l else l.copy(stickies = withContent(l.stickies)) }
    }

    /**
     * Make [strokes] the note's whole content: live children not in the set are soft-deleted,
     * every stroke in the set is put in the note's space with `"order"` = its index, one that
     * exists reviving in place. One transaction: a note is never seen half-written.
     */
    fun setStickyContent(stickyId: String, strokes: List<Stroke>) = guard {
        val now = System.currentTimeMillis()
        val keep = strokes.mapTo(HashSet()) { it.id }
        val gone = store.query(NotebookSql.selectLiveChildIds(stickyId, NotebookSchema.TYPE_STROKE)).rows
            .map { it.text("id") }.filter { it !in keep }
        val statements = ArrayList<Statement>(gone.size + strokes.size)
        gone.forEach { statements += NotebookSql.softDelete(it, now) }
        strokes.forEachIndexed { i, s -> statements += NotebookSql.putStroke(stickyId, i.toLong(), s, now) }
        run(statements)
    }

    fun setLastOpened(pageId: String) =
        execAll(listOf(NotebookSql.setLastOpened(notebookId, pageId, System.currentTimeMillis())))

    fun setTitle(name: String) =
        execAll(listOf(NotebookSql.setTitle(notebookId, name, System.currentTimeMillis())))

    // ── Structure ──────

    /**
     * A blank page next to [currentId], inheriting its paper and size so the notebook stays one
     * consistent paper. Answers the new list and the new page.
     */
    fun insertPage(pages: List<PageRef>, currentId: String, after: Boolean): Pair<List<PageRef>, PageRef> {
        val i = pages.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val from = pages[i]
        val page = PageRef(newId(), from.width, from.height, from.templateId)
        val pos = PageMath.insertPosition(i, after)
        val next = pages.toMutableList().also { it.add(pos, page) }
        val now = System.currentTimeMillis()
        execAll(
            listOf(NotebookSql.insertPage(page.id, notebookId, pos, page.width, page.height, page.templateId, now)) +
                renumber(next, now) + NotebookSql.setLastOpened(notebookId, page.id, now),
        )
        return next to page
    }

    /**
     * Soft-delete [victim] and everything alive under it, and land on the page before it. The
     * only page is replaced by a fresh blank one, so a notebook always has a page. Answers the
     * new list, the landing page, and the ids taken with the page, which an undo brings back.
     */
    fun deletePage(pages: List<PageRef>, victim: PageRef): Triple<List<PageRef>, PageRef, List<String>> = guard {
        val now = System.currentTimeMillis()
        val under = store.query(NotebookSql.selectLiveDescendantIds(victim.id)).rows.map { it.text("id") }
        val statements = ArrayList<Statement>(under.size + pages.size + 4)
        statements += NotebookSql.softDelete(victim.id, now)
        under.forEach { statements += NotebookSql.softDelete(it, now) }
        statements += NotebookSql.mirrorDropPage(victim.id)
        val next: List<PageRef>
        val landing: PageRef
        if (pages.size <= 1) {
            landing = PageRef(newId(), victim.width, victim.height, victim.templateId)
            next = listOf(landing)
            statements += NotebookSql.insertPage(landing.id, notebookId, 0, landing.width, landing.height, landing.templateId, now)
        } else {
            val i = pages.indexOfFirst { it.id == victim.id }
            next = pages.filter { it.id != victim.id }
            landing = next[PageMath.indexAfterDelete(i, pages.size)]
            statements += renumber(next, now)
        }
        statements += NotebookSql.setLastOpened(notebookId, landing.id, now)
        run(statements)
        Triple(next, landing, under)
    }

    /** Everything alive under [pageId], soft-deleted. The page stays. Answers what went. */
    fun erasePage(pageId: String): List<String> = guard {
        val now = System.currentTimeMillis()
        val under = store.query(NotebookSql.selectLiveDescendantIds(pageId)).rows.map { it.text("id") }
        if (under.isNotEmpty()) run(under.map { NotebookSql.softDelete(it, now) } + NotebookSql.mirrorDropPage(pageId))
        under
    }

    fun restoreIds(ids: List<String>) {
        if (ids.isNotEmpty()) execAll(ids.map { NotebookSql.restore(it) })
    }

    fun softDeleteIds(ids: List<String>) {
        val now = System.currentTimeMillis()
        if (ids.isNotEmpty()) execAll(ids.map { NotebookSql.softDelete(it, now) })
    }

    /**
     * Make the live page set exactly [target], in that order, restore and delete the given
     * content ids with it, and land on [currentId]. The one primitive behind both directions of
     * a page insert or delete's replay. A row is restored **in place**: it keeps its id and its
     * order, so nothing that pointed at it is stranded.
     */
    fun reconcile(alive: List<PageRef>, target: List<PageRef>, restoreIds: List<String>, deleteIds: List<String>, currentId: String) {
        val now = System.currentTimeMillis()
        val aliveIds = alive.map { it.id }.toSet()
        val targetIds = target.map { it.id }.toSet()
        val statements = ArrayList<Statement>()
        for (page in target) if (page.id !in aliveIds) {
            // Gone from the file or soft-deleted: an insert of a row that exists is ignored, and
            // the restore lifts the mark either way.
            statements += NotebookSql.insertPage(page.id, notebookId, 0, page.width, page.height, page.templateId, now)
            statements += NotebookSql.restore(page.id)
        }
        for (id in aliveIds) if (id !in targetIds) {
            statements += NotebookSql.softDelete(id, now)
            statements += NotebookSql.mirrorDropPage(id)
        }
        restoreIds.forEach { statements += NotebookSql.restore(it) }
        deleteIds.forEach { statements += NotebookSql.softDelete(it, now) }
        target.forEachIndexed { i, page -> statements += NotebookSql.setOrder(page.id, i, now) }
        statements += NotebookSql.setLastOpened(notebookId, currentId, now)
        execAll(statements)
        // A page brought back brings its links back: their mirror rows are written again.
        for (page in target) if (page.id !in aliveIds) remirrorPage(page.id)
    }

    private fun renumber(pages: List<PageRef>, now: Long): List<Statement> =
        pages.mapIndexed { i, page -> NotebookSql.setOrder(page.id, i, now) }

    /** The file holds no page: it is not a notebook this app can show. */
    class NoPages : IllegalStateException("the notebook has no pages")

    companion object {
        private const val TAG = "NotebookStore"
        fun newId(): String = UUID.randomUUID().toString()
    }
}
