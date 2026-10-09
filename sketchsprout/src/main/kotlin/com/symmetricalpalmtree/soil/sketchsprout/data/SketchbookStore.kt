package com.symmetricalpalmtree.soil.sketchsprout.data

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.soil.paper.ink.InkStore
import com.symmetricalpalmtree.soil.paper.chrome.PageMath
import com.symmetricalpalmtree.soil.paper.store.RowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.paper.templates.BuiltInTemplates
import com.symmetricalpalmtree.soil.paper.templates.PagePaper
import com.symmetricalpalmtree.soil.paper.templates.PageTemplate
import com.symmetricalpalmtree.soil.paper.templates.PaperSource
import com.symmetricalpalmtree.soil.paper.templates.TemplateDigest
import com.symmetricalpalmtree.soil.sketchsprout.clip.SketchPageClip
import com.symmetricalpalmtree.soil.sketchsprout.raster.GuideGrid
import com.symmetricalpalmtree.soil.sketchsprout.raster.GuideImage
import com.symmetricalpalmtree.soil.sketchsprout.raster.GuideRows
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

    // ── Paper ──────

    /** The paper could not be drawn at the page's size. */
    class PaperRenderFailed : Exception("the paper could not be rendered")

    /** The sketchbook's template rows, blob-free. */
    fun templateDigests(): List<TemplateDigest> = guard {
        store.query(SketchbookSql.selectTemplateDigests(sketchbookId)).rows.map {
            TemplateDigest(it.text("id"), it.textOrNull("text"), it.realOrNull("width")?.toFloat(), it.realOrNull("height")?.toFloat(), it.longOrNull("blobLength"))
        }
    }

    /** A template row's pixels, or null when the row is gone or holds none. */
    fun templateBlob(id: String): ByteArray? = guard {
        if (id.isEmpty()) null else store.query(SketchbookSql.selectTemplateBlob(id)).rows.firstOrNull()?.blobOrNull("blob")
    }

    fun setPageTemplate(pageId: String, templateId: String) =
        execAll(listOf(SketchbookSql.setPageTemplate(pageId, templateId, System.currentTimeMillis())))

    /**
     * Re-paper [page] with [paper]: **reuse before mint** — a row this file already holds that is
     * the wanted paper at the page's exact size is pointed at, and only otherwise is another
     * render stored (templates.md's rule). Answers the template id the page now points at, or
     * null when it already did. Blocking, IO only.
     */
    fun changeTemplate(page: SketchPage, paper: PaperSource, dpi: Float): String? {
        val token = PagePaper.token(paper)
        val w = page.width.toInt(); val h = page.height.toInt()
        val target = if (token.isEmpty()) "" else PageTemplate.reusableId(templateDigests(), token, w, h, prefer = page.templateId)
            ?: run {
                val bitmap = PagePaper.render(paper, w, h, dpi) ?: throw PaperRenderFailed()
                val blob = try { BuiltInTemplates.toWebp(bitmap) } finally { bitmap.recycle() }
                val id = newId()
                execAll(listOf(SketchbookSql.insertTemplate(id, sketchbookId, token, w, h, blob, System.currentTimeMillis())))
                id
            }
        if (target == page.templateId) return null
        setPageTemplate(page.id, target)
        return target
    }

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

    // ── Convert: a sketchbook made whole from a notebook's ink ──────

    /** One page as the ingest writes it: its size, its paper (a token and a picture, or none),
     *  its ink raster's bytes (or null for a blank page). */
    class IngestedPage(val width: Float, val height: Float, val paperToken: String?, val paper: ByteArray?, val ink: ByteArray?)

    /**
     * A sketchbook file Soil just made, filled in one transaction: the root row, a template row
     * per distinct paper (shared by every page on the same paper), a page row per page in order,
     * and an ink row under each page that has one. Answers the page ids in order.
     */
    fun ingest(name: String, pages: List<IngestedPage>): List<String> {
        require(pages.isNotEmpty()) { "no pages" }
        val now = System.currentTimeMillis()
        val statements = ArrayList<Statement>()
        statements += SketchbookSql.insertRoot(sketchbookId, name, now)
        val templates = HashMap<String, String>()
        val ids = ArrayList<String>(pages.size)
        for ((i, page) in pages.withIndex()) {
            val w = page.width.toInt(); val h = page.height.toInt()
            val templateId = if (page.paperToken.isNullOrEmpty() || page.paper == null || page.paper.isEmpty()) "" else {
                templates.getOrPut("${page.paperToken}|$w|$h") {
                    newId().also { statements += SketchbookSql.insertTemplate(it, sketchbookId, page.paperToken, w, h, page.paper, now) }
                }
            }
            val pageId = newId()
            ids += pageId
            statements += SketchbookSql.insertPage(pageId, sketchbookId, i, page.width, page.height, templateId, now)
            val ink = page.ink
            if (ink != null && ink.isNotEmpty()) {
                if (ink.size > RasterRows.MAX_BYTES) throw RasterTooLarge(ink.size)
                statements += SketchbookSql.insertRaster(newId(), pageId, SketchbookSchema.TYPE_SKETCH_INK, ink, now)
            }
        }
        statements += SketchbookSql.setLastOpened(sketchbookId, ids.first(), now)
        execAll(statements)
        return ids
    }

    // ── The clipboard ──────

    /** Snapshot [page] and everything under it as the clipboard's bytes. The caller flushed the
     *  rasters first. Null when the page row has gone. */
    fun capturePage(page: SketchPage): ByteArray? = guard {
        val pageRow = store.query(SketchbookSql.selectRows(listOf(page.id))).rows.mapNotNull { SketchRow.fromRow(it) }.firstOrNull() ?: return@guard null
        val template = page.templateId.takeIf { it.isNotEmpty() }?.let { id -> store.query(SketchbookSql.selectRows(listOf(id))).rows.mapNotNull { SketchRow.fromRow(it) }.firstOrNull() }
        val children = store.query(SketchbookSql.selectLiveDescendantRows(page.id)).rows.mapNotNull { SketchRow.fromRow(it) }
        SketchPageClip.encode(pageRow, template, children)
    }

    /**
     * Paste the clipboard's page beside [currentId] — before or after — and land on it. The paper
     * is reused when this file already holds the same paper at the page's size, else the carried
     * row is inserted. Answers the new list and the page, or null when [rows] carry no page.
     */
    fun pastePage(pages: List<SketchPage>, currentId: String, before: Boolean, rows: List<SketchRow>): Pair<List<SketchPage>, SketchPage>? {
        val i = pages.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        val pos = PageMath.insertPosition(i, !before)
        val digests = templateDigests()
        val plan = SketchPageClip.plan(rows, sketchbookId, pos, template = { carried ->
            if (carried == null) SketchPageClip.Template.None
            else {
                val w = carried.width?.toInt() ?: 0; val h = carried.height?.toInt() ?: 0
                PageTemplate.reusableId(digests, carried.text.orEmpty(), w, h)?.let { SketchPageClip.Template.Reuse(it) }
                    ?: SketchPageClip.Template.Insert(newId())
            }
        }, newId = ::newId) ?: return null
        val page = SketchPage(plan.page.id, plan.page.width ?: 0f, plan.page.height ?: 0f, plan.page.refId.orEmpty())
        val next = pages.toMutableList().also { it.add(pos, page) }
        val now = System.currentTimeMillis()
        execAll(plan.rows.map { SketchbookSql.insertRow(it, now) } + renumber(next, now) + SketchbookSql.setLastOpened(sketchbookId, page.id, now))
        return next to page
    }

    // ── Guides ──────

    /** A page's two guide rows as read: either may be absent; [imageBytes] is null for no picture. */
    class Guides(val grid: GuideGrid?, val image: GuideImage?, val imageBytes: ByteArray?)

    fun readGuides(pageId: String): Guides = guard {
        val gridRow = store.query(SketchbookSql.selectGuide(pageId, SketchbookSchema.TYPE_GUIDE_GRID)).rows.firstOrNull()
        val imageRow = store.query(SketchbookSql.selectGuide(pageId, SketchbookSchema.TYPE_GUIDE_IMAGE)).rows.firstOrNull()
        val bytes = imageRow?.blobOrNull("blob")?.takeIf { it.isNotEmpty() }
        Guides(
            grid = gridRow?.let { GuideRows.decodeGrid(it.textOrNull("text")) },
            image = imageRow?.let { GuideRows.decodeImage(it.textOrNull("text")) },
            imageBytes = bytes,
        )
    }

    /** The page's grid row: written in place, minted on first use, soft-deleted for Off (null). */
    fun writeGrid(pageId: String, grid: GuideGrid?) = guard {
        val now = System.currentTimeMillis()
        val existing = store.query(SketchbookSql.selectGuide(pageId, SketchbookSchema.TYPE_GUIDE_GRID)).rows.firstOrNull()?.textOrNull("id")
        val statement = when {
            grid == null -> existing?.let { SketchbookSql.softDelete(it, now) } ?: return@guard
            existing != null -> SketchbookSql.updateGuideText(existing, GuideRows.encodeGrid(grid), now)
            else -> SketchbookSql.insertGuide(newId(), pageId, SketchbookSchema.TYPE_GUIDE_GRID, GuideRows.encodeGrid(grid), null, now)
        }
        run(listOf(statement))
    }

    /** The page's reference image row: [bytes] with [image] writes or replaces it; null bytes
     *  soft-delete it. A picture over the cap is refused before anything is sent. */
    fun writeImage(pageId: String, image: GuideImage?, bytes: ByteArray?) {
        if (bytes != null && bytes.size > RasterRows.MAX_BYTES) throw RasterTooLarge(bytes.size)
        guard {
            val now = System.currentTimeMillis()
            val existing = store.query(SketchbookSql.selectGuide(pageId, SketchbookSchema.TYPE_GUIDE_IMAGE)).rows.firstOrNull()?.textOrNull("id")
            val statement = when {
                bytes == null || image == null -> existing?.let { SketchbookSql.softDelete(it, now) } ?: return@guard
                existing != null -> SketchbookSql.updateGuide(existing, GuideRows.encodeImage(image), bytes, now)
                else -> SketchbookSql.insertGuide(newId(), pageId, SketchbookSchema.TYPE_GUIDE_IMAGE, GuideRows.encodeImage(image), bytes, now)
            }
            run(listOf(statement))
        }
    }

    /** The image row's settings alone, when the page has one. */
    fun setImageSettings(pageId: String, image: GuideImage) = guard {
        val existing = store.query(SketchbookSql.selectGuide(pageId, SketchbookSchema.TYPE_GUIDE_IMAGE)).rows.firstOrNull()?.textOrNull("id") ?: return@guard
        run(listOf(SketchbookSql.updateGuideText(existing, GuideRows.encodeImage(image), System.currentTimeMillis())))
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
