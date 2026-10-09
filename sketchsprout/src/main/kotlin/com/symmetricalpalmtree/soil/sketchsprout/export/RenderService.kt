package com.symmetricalpalmtree.soil.sketchsprout.export

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.gpaper.core.RasterLayer
import com.symmetricalpalmtree.soil.ext.PageBundle
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.templates.Bitmaps
import com.symmetricalpalmtree.soil.seam.IItemRenderer
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck
import com.symmetricalpalmtree.soil.seam.SeamPageNames
import com.symmetricalpalmtree.soil.seam.SeamRenderInfo
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.paper.core.CoverSnapshot
import com.symmetricalpalmtree.soil.sketchsprout.ingest.InkIngest
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import android.graphics.Canvas
import com.symmetricalpalmtree.soil.seamkit.SeamRowStore
import com.symmetricalpalmtree.soil.sketchsprout.SketchsproutApp
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookSchema
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookStore
import com.symmetricalpalmtree.soil.sketchsprout.raster.PageFlatten
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterImage
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterRows
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * **The sketchbook's pages for Soil's export screen.** Soil binds this, guarded by its own
 * permission, and asks for the pages' names or a rendered bundle. The item is opened through the
 * seam as the sketch screen opens it, read, and each page flattened as the screen's cover is —
 * white, the paper (with the paper toggle), the graphite, the ink over it, in true greys, never
 * the panel's dither — one page in memory at a time. The guides are never here: they are what
 * the page lies on, not the page. Nothing is written to the sketchbook.
 */
class RenderService : Service() {

    private val binder = object : IItemRenderer.Stub() {

        override fun pages(itemId: String): SeamPageNames = guarded {
            withStore(itemId) { store ->
                val loaded = store.load()
                SeamPageNames(loaded.pages.map { it.id }, loaded.pages.indices.map { it + 1 }, loaded.pages.map { "" })
            }
        }

        override fun render(itemId: String, pageIds: List<String>?, template: Boolean, bundleVersion: Int, destination: ParcelFileDescriptor?): SeamPageNames = guarded {
            val out = destination ?: throw IllegalArgumentException("no destination")
            try {
                withStore(itemId) { store -> bake(store, pageIds.orEmpty(), template, out) }
            } finally {
                runCatching { out.close() }
            }
        }

        override fun relabelStatements(oldId: String, newId: String): List<String> = guarded { Relabel.statements(oldId, newId) }

        // A sketchbook is its pages: it does not flow, and writes no format of its own. It takes
        // one file in: a notebook's ink envelope, which is how Convert makes a sketchbook.
        override fun describe(): SeamRenderInfo = guarded { INFO }

        override fun renderFlow(itemId: String, pageSize: String?, bundleVersion: Int, destination: ParcelFileDescriptor?): SeamPageNames = guarded {
            runCatching { destination?.close() }
            throw IllegalArgumentException("a sketchbook does not flow")
        }

        override fun produce(itemId: String, formatId: String?, pageSize: String?, destination: ParcelFileDescriptor?) = guarded {
            runCatching { destination?.close() }
            throw IllegalArgumentException("no such format")
        }

        /**
         * **Convert's landing**: the notebook's ink envelope read into the sketchbook Soil just
         * made — a page per page, the paper carried, every stroke baked black into an ink raster
         * (one page in memory at a time), then the root row, the pages and the rasters written
         * in one transaction. The library is told the pages and shown the first page as the cover.
         */
        override fun ingest(itemId: String, fileExtension: String?, source: ParcelFileDescriptor?) = guarded {
            val input = source ?: throw IllegalArgumentException("no source")
            if (!InkIngest.EXTENSION.equals(fileExtension, ignoreCase = true)) { runCatching { input.close() }; throw IllegalArgumentException("a sketchbook takes only notebook ink") }
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(input).use { it.readBytes() }
            val env = ClipEnvelope.decode(bytes) ?: throw IllegalStateException(Seam.INGEST_NOT_TEXT)
            val pages = InkIngest.pagesOf(env) ?: throw IllegalStateException(Seam.INGEST_NOT_TEXT)
            if (pages.size > PageBundle.MAX_PAGES) throw IllegalStateException(Seam.INGEST_TOO_LARGE)
            val seam = runBlocking { (application as SketchsproutApp).soil.seam() }
            val name = seam.item(itemId)?.name ?: throw IllegalStateException(Seam.INGEST_FAILED)
            val baked = pages.map { page ->
                SketchbookStore.IngestedPage(page.width, page.height, page.paperToken, page.paper, bakeInk(page))
            }
            val session = seam.openItem(itemId, SketchbookSchema.SCHEMA, Binder())
            val ids = try {
                SketchbookStore(SeamRowStore(session), itemId).ingest(name, baked)
            } catch (e: SketchbookStore.RasterTooLarge) {
                throw IllegalStateException(Seam.INGEST_TOO_LARGE)
            } catch (e: Exception) {
                Log.w(TAG, "the converted ink was not written: ${e.javaClass.simpleName}")
                throw IllegalStateException(Seam.INGEST_FAILED)
            } finally {
                runCatching { session.close(true) }
            }
            runCatching { seam.setPages(itemId, ids) }
            // The first page as the card's cover; never worth failing the convert for.
            runCatching {
                val first = pages.first(); val w = first.width.toInt(); val h = first.height.toInt()
                val paper = baked.first().paper?.let { Bitmaps.decodeBounded(it, MAX_TEMPLATE_EDGE) }
                val ink = baked.first().ink?.let { RasterImage.decode(it, w, h) }
                val flat = try { PageFlatten.flatten(w, h, paper, RasterRows.LAYERS.map { if (it == RasterLayer.INK) ink else null }) } finally { paper?.recycle(); ink?.recycle() }
                try { seam.setCover(itemId, SeamShared.write(CoverSnapshot.encode(flat))) } finally { flat.recycle() }
            }
            Slog.d(TAG) { "converted ${pages.size} page(s) into a sketchbook" }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** The caller check first, then the one exception shape that crosses. */
    private inline fun <T> guarded(body: () -> T): T {
        SeamCallerCheck.enforce(this)
        return try {
            body()
        } catch (e: SecurityException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "render ran out of memory")
            throw IllegalStateException(Seam.RENDER_FAILED)
        } catch (e: Throwable) {
            Log.w(TAG, "render failed: ${e.javaClass.simpleName}")
            throw IllegalStateException(Seam.RENDER_FAILED)
        }
    }

    /** The item open through the seam for the call, closed after, untidied: nothing was written. */
    private fun <T> withStore(itemId: String, body: (SketchbookStore) -> T): T {
        val seam = runBlocking { (application as SketchsproutApp).soil.seam() }
        val session = seam.openItem(itemId, SketchbookSchema.SCHEMA, Binder())
        try {
            return body(SketchbookStore(SeamRowStore(session), itemId))
        } finally {
            runCatching { session.close(false) }
        }
    }

    private fun bake(store: SketchbookStore, pageIds: List<String>, template: Boolean, out: ParcelFileDescriptor): SeamPageNames {
        val loaded = try {
            store.load()
        } catch (e: SketchbookStore.NoPages) {
            throw IllegalStateException(Seam.RENDER_EMPTY)
        }
        val pages = when (val plan = RenderPlan.of(loaded.pages, pageIds)) {
            is RenderPlan.Outcome.Ready -> plan.pages
            RenderPlan.Outcome.Empty -> throw IllegalStateException(Seam.RENDER_EMPTY)
            RenderPlan.Outcome.Damaged -> throw IllegalStateException(Seam.RENDER_DAMAGED)
            RenderPlan.Outcome.TooLong -> throw IllegalStateException(Seam.RENDER_TOO_LONG)
        }
        var templateId: String? = null
        var paper: Bitmap? = null
        try {
            PageBundle.Writer(ParcelFileDescriptor.AutoCloseOutputStream(out), pages.size).use { writer ->
                for (page in pages) {
                    if (template && page.ref.templateId.isNotEmpty() && page.ref.templateId != templateId) {
                        paper?.recycle(); paper = null
                        templateId = page.ref.templateId
                        paper = Bitmaps.decodeBounded(store.templateBlob(page.ref.templateId), MAX_TEMPLATE_EDGE)
                    } else if (!template || page.ref.templateId.isEmpty()) {
                        paper?.recycle(); paper = null
                        templateId = null
                    }
                    writer.writePage(page.widthPx, page.heightPx, toPng(store, page, paper))
                }
            }
        } finally {
            paper?.recycle()
        }
        Slog.d(TAG) { "rendered ${pages.size} page(s)" }
        return SeamPageNames(pages.map { it.ref.id }, pages.map { it.number }, pages.map { "" })
    }

    /** One page, lossless: white, the paper, graphite, ink, the marker — every raster decoded
     *  behind the header guard and recycled the moment they are flattened. */
    private fun toPng(store: SketchbookStore, page: RenderPlan.Page, paper: Bitmap?): ByteArray {
        val w = page.widthPx; val h = page.heightPx
        val rasters = arrayOfNulls<Bitmap>(RasterRows.LAYERS.size)
        val flat = try {
            RasterRows.LAYERS.forEachIndexed { i, layer -> rasters[i] = RasterImage.decode(store.readRaster(page.ref.id, layer), w, h) }
            PageFlatten.flatten(w, h, paper, rasters.toList())
        } catch (e: OutOfMemoryError) {
            throw IOException("a ${w}x$h page would not allocate", e)
        } finally {
            rasters.forEach { it?.recycle() }
        }
        return try {
            val bytes = ByteArrayOutputStream(w * h / 8)
            if (!flat.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw IOException("the page would not encode")
            bytes.toByteArray()
        } finally {
            flat.recycle()
        }
    }

    /** A page's strokes baked onto a transparent page-sized image, as the engine bakes a pen mark
     *  into the ink raster: lossless WebP bytes, or null for a page with no strokes. */
    private fun bakeInk(page: InkIngest.Page): ByteArray? {
        if (page.strokes.isEmpty()) return null
        val w = page.width.toInt(); val h = page.height.toInt()
        val bitmap = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: OutOfMemoryError) { throw IOException("a ${w}x$h page would not allocate", e) }
        return try {
            StrokeRasterizer.draw(Canvas(bitmap), page.strokes)
            RasterImage.encode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        private const val TAG = "RenderService"
        private const val MAX_TEMPLATE_EDGE = 4096

        /** No formats of its own, no flow; one file in: a notebook's ink. */
        val INFO = SeamRenderInfo(
            flowing = false,
            formats = emptyList(),
            importLabel = InkIngest.LABEL,
            importExtensions = listOf(InkIngest.EXTENSION),
            // A type no file on the device carries: the ink file never exists on disk, and the
            // picker's filter must not widen for it.
            importMimeTypes = listOf("application/x-soil-ink"),
        )
    }
}
