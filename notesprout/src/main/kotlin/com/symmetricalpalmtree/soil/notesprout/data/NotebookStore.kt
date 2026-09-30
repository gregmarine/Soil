package com.symmetricalpalmtree.soil.notesprout.data

import android.util.Log
import com.symmetricalpalmtree.soil.paper.chrome.PageMath
import com.symmetricalpalmtree.soil.paper.ink.InkStore
import com.symmetricalpalmtree.soil.paper.ink.PageInk
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

    /** One page's ink, in writing order. */
    fun readPage(page: PageRef): PageInk = guard {
        val rows = store.query(NotebookSql.selectStrokes(page.id)).rows
        val strokes = ArrayList<Pair<Long, com.symmetricalpalmtree.gpaper.core.model.Stroke>>(rows.size)
        var dropped = 0
        for (row in rows) {
            val decoded = NotebookStrokeRows.decode(row)
            if (decoded == null) dropped++ else strokes += decoded
        }
        if (dropped > 0) Log.w(TAG, "a page had $dropped stroke row(s) that would not read")
        PageInk(page.width, page.height, strokes)
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
        if (under.isNotEmpty()) run(under.map { NotebookSql.softDelete(it, now) })
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
        for (id in aliveIds) if (id !in targetIds) statements += NotebookSql.softDelete(id, now)
        restoreIds.forEach { statements += NotebookSql.restore(it) }
        deleteIds.forEach { statements += NotebookSql.softDelete(it, now) }
        target.forEachIndexed { i, page -> statements += NotebookSql.setOrder(page.id, i, now) }
        statements += NotebookSql.setLastOpened(notebookId, currentId, now)
        execAll(statements)
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
