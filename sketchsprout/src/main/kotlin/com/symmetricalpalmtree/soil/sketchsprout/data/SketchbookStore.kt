package com.symmetricalpalmtree.soil.sketchsprout.data

import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.soil.paper.ink.InkStore
import com.symmetricalpalmtree.soil.paper.store.RowStore
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
