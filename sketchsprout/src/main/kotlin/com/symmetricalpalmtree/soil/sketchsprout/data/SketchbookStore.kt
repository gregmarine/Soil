package com.symmetricalpalmtree.soil.sketchsprout.data

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.soil.paper.ink.InkStore
import com.symmetricalpalmtree.soil.paper.chrome.PageMath
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterRows
import java.util.UUID

/** One page as the sketchbook lists it. [templateId] is a template row's id, or `""` for blank. */
data class SketchPage(val id: String, val width: Float, val height: Float, val templateId: String)

/**
 * The sketchbook table over its [RowStore], on `:paper`'s [InkStore] base for its one-rule error
 * mapping (every failure is `StoreUnavailable`). **Blocking**: every call runs on
 * `Dispatchers.IO`, never Main.
 *
 * Every SQL string lives in [SketchbookSql]; every write goes through [execAll], one transaction.
 */
class SketchbookStore(store: RowStore, private val sketchbookId: String) : InkStore(store, TAG) {

    class Loaded(val pages: List<SketchPage>, val currentId: String)

    /** A file Soil made and nobody has written yet is not a store that failed: it is a sketchbook
     *  waiting for its first page. */
    class NoPages : Exception("no pages")

    /** A picture the seam will not carry: refused here, before a statement is built, so the
     *  raster stays dirty on the glass and the person hears about it at the exit. */
    class RasterTooLarge(bytes: Int) : Exception("a raster of $bytes bytes is over the ${RasterRows.MAX_BYTES}-byte cap")

    /**
     * A sketchbook file that has never held a page is given its root row and one blank page, the
     * size of the surface. Only the flow that makes a sketchbook calls this; an open never does.
     */
    fun initialize(name: String, width: Float, height: Float): Loaded {
        val now = System.currentTimeMillis()
        val pageId = newId()
        execAll(
            listOf(
                SketchbookSql.insertRoot(sketchbookId, name, now),
                SketchbookSql.insertPage(pageId, sketchbookId, 0, width, height, "", now),
                SketchbookSql.setLastOpened(sketchbookId, pageId, now),
            ),
        )
        return Loaded(listOf(SketchPage(pageId, width, height, "")), pageId)
    }

    /**
     * The page list and the page last open. A sketchbook with no pages is refused: nothing is
     * fabricated here. A last-open page that is gone lands on the first.
     */
    fun load(): Loaded {
        val pages = guard {
            store.query(SketchbookSql.selectPages(sketchbookId)).rows.map {
                SketchPage(
                    id = it.text("id"),
                    width = it.realOrNull("width")?.toFloat() ?: 0f,
                    height = it.realOrNull("height")?.toFloat() ?: 0f,
                    templateId = it.textOrNull("refId").orEmpty(),
                )
            }
        }
        if (pages.isEmpty()) throw NoPages()
        val last = guard { store.query(SketchbookSql.selectRoot(sketchbookId)).rows.firstOrNull()?.textOrNull("refId") }
        return Loaded(pages, if (pages.any { it.id == last }) last!! else pages[0].id)
    }

    fun setLastOpened(pageId: String) =
        execAll(listOf(SketchbookSql.setLastOpened(sketchbookId, pageId, System.currentTimeMillis())))

    // ── Pages ──────

    /** A blank page the size and paper of the current one, before or after it. Answers the new
     *  list and the page, which is the new last-open. */
    fun insertPage(pages: List<SketchPage>, currentId: String, after: Boolean): Pair<List<SketchPage>, SketchPage> {
        val i = pages.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val from = pages[i]
        val page = SketchPage(newId(), from.width, from.height, from.templateId)
        val pos = PageMath.insertPosition(i, after)
        val next = pages.toMutableList().also { it.add(pos, page) }
        val now = System.currentTimeMillis()
        execAll(
            listOf(SketchbookSql.insertPage(page.id, sketchbookId, pos, page.width, page.height, page.templateId, now)) +
                renumber(next, now) + SketchbookSql.setLastOpened(sketchbookId, page.id, now),
        )
        return next to page
    }

    /**
     * Soft-delete [victim] and everything alive under it, and land on the page before it. The
     * only page is replaced by a fresh blank one, so a sketchbook always has a page. Answers the
     * new list, the landing page, and the ids taken with the page, which an undo brings back.
     */
    fun deletePage(pages: List<SketchPage>, victim: SketchPage): Triple<List<SketchPage>, SketchPage, List<String>> = guard {
        val now = System.currentTimeMillis()
        val under = store.query(SketchbookSql.selectLiveDescendantIds(victim.id)).rows.map { it.text("id") }
        val statements = ArrayList<Statement>(under.size + pages.size + 3)
        statements += SketchbookSql.softDelete(victim.id, now)
        under.forEach { statements += SketchbookSql.softDelete(it, now) }
        val next: List<SketchPage>
        val landing: SketchPage
        if (pages.size <= 1) {
            landing = SketchPage(newId(), victim.width, victim.height, victim.templateId)
            next = listOf(landing)
            statements += SketchbookSql.insertPage(landing.id, sketchbookId, 0, landing.width, landing.height, landing.templateId, now)
        } else {
            val i = pages.indexOfFirst { it.id == victim.id }
            next = pages.filter { it.id != victim.id }
            landing = next[PageMath.indexAfterDelete(i, pages.size)]
            statements += renumber(next, now)
        }
        statements += SketchbookSql.setLastOpened(sketchbookId, landing.id, now)
        run(statements)
        Triple(next, landing, under)
    }

    /**
     * Make the live page set exactly [target], in that order, restore and soft-delete the given
     * content ids with it, and land on [currentId]. The one primitive behind both directions of a
     * page insert or delete's replay. A row is restored **in place**: it keeps its id and its
     * order, so a page that comes back comes back with its pictures.
     */
    fun reconcile(alive: List<SketchPage>, target: List<SketchPage>, restoreIds: List<String>, deleteIds: List<String>, currentId: String) {
        val now = System.currentTimeMillis()
        val aliveIds = alive.map { it.id }.toSet()
        val targetIds = target.map { it.id }.toSet()
        val statements = ArrayList<Statement>()
        for (page in target) if (page.id !in aliveIds) {
            statements += SketchbookSql.insertPage(page.id, sketchbookId, 0, page.width, page.height, page.templateId, now)
            statements += SketchbookSql.restore(page.id)
        }
        for (id in aliveIds) if (id !in targetIds) statements += SketchbookSql.softDelete(id, now)
        restoreIds.forEach { statements += SketchbookSql.restore(it) }
        deleteIds.forEach { statements += SketchbookSql.softDelete(it, now) }
        statements += renumber(target, now)
        statements += SketchbookSql.setLastOpened(sketchbookId, currentId, now)
        execAll(statements)
    }

    private fun renumber(pages: List<SketchPage>, now: Long): List<Statement> =
        pages.mapIndexed { i, page -> SketchbookSql.setOrder(page.id, i, now) }

    /** The page's picture of one raster, as stored, or null for a layer nothing has been drawn on
     *  (no row, or a row carrying nothing). The bytes are not checked here: [RasterRows.fitsPage]
     *  is the screen's guard, run before any decode. */
    fun readRaster(pageId: String, layer: RasterLayer): ByteArray? = guard {
        val row = store.query(SketchbookSql.selectRaster(pageId, RasterRows.typeFor(layer))).rows.firstOrNull() ?: return@guard null
        row.blobOrNull("blob")?.takeIf { it.isNotEmpty() }
    }

    /**
     * One raster's next picture: inserted under the page the first time, replaced in place after
     * that. **Empty bytes are a blank layer** and soft-delete the row — a page of nothing is not
     * stored. A picture over the seam's value cap is refused ([RasterTooLarge]) before anything
     * is sent.
     */
    fun writeRaster(pageId: String, layer: RasterLayer, bytes: ByteArray) {
        if (bytes.size > RasterRows.MAX_BYTES) throw RasterTooLarge(bytes.size)
        val type = RasterRows.typeFor(layer)
        guard {
            val now = System.currentTimeMillis()
            val existing = store.query(SketchbookSql.selectRasterId(pageId, type)).rows.firstOrNull()?.textOrNull("id")
            val statement = when {
                bytes.isEmpty() -> existing?.let { SketchbookSql.softDelete(it, now) } ?: return@guard
                existing != null -> SketchbookSql.updateRaster(existing, bytes, now)
                else -> SketchbookSql.insertRaster(newId(), pageId, type, bytes, now)
            }
            run(listOf(statement))
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "SketchbookStore"
    }
}
