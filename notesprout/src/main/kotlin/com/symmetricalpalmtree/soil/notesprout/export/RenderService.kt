package com.symmetricalpalmtree.soil.notesprout.export

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import com.symmetricalpalmtree.soil.ext.PageBundle
import com.symmetricalpalmtree.soil.notesprout.NotesproutApp
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.data.NotebookStore
import com.symmetricalpalmtree.soil.notesprout.data.PageContent
import com.symmetricalpalmtree.soil.notesprout.notebook.PagePaints
import com.symmetricalpalmtree.soil.notesprout.notebook.PageRaster
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.templates.Bitmaps
import com.symmetricalpalmtree.soil.seam.IItemRenderer
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck
import com.symmetricalpalmtree.soil.seam.SeamPageNames
import com.symmetricalpalmtree.soil.seam.SeamRenderInfo
import com.symmetricalpalmtree.soil.seamkit.SeamRowStore
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * **The notebook's pages for Soil's export screen.** Soil binds this, guarded by its own
 * permission, and asks for the pages' names or a rendered bundle. The item is opened through the
 * seam like the notebook screen opens it, read, rendered page by page with the same raster the
 * templates use, and closed. Nothing is written to the notebook.
 *
 * One page in memory at a time: a whole notebook of full-size pages is an OOM on this device.
 */
class RenderService : Service() {

    private val binder = object : IItemRenderer.Stub() {

        override fun pages(itemId: String): SeamPageNames = guarded {
            withStore(itemId) { store ->
                val loaded = store.load()
                val titles = loaded.pages.map { page -> PageLabels.titleOf(store.readPage(page)).orEmpty() }
                SeamPageNames(loaded.pages.map { it.id }, loaded.pages.indices.map { it + 1 }, titles)
            }
        }

        override fun render(itemId: String, pageIds: List<String>?, template: Boolean, bundleVersion: Int, destination: ParcelFileDescriptor?): SeamPageNames = guarded {
            val out = destination ?: throw IllegalArgumentException("no destination")
            try {
                withStore(itemId) { store -> bake(store, pageIds.orEmpty(), template, bundleVersion, out) }
            } finally {
                runCatching { out.close() }
            }
        }

        override fun relabelStatements(oldId: String, newId: String): List<String> = guarded { Relabel.statements(oldId, newId) }

        // A notebook is its pages: it does not flow, and writes no format of its own.
        override fun describe(): SeamRenderInfo = guarded { SeamRenderInfo.PAGES_ONLY }

        override fun renderFlow(itemId: String, pageSize: String?, bundleVersion: Int, destination: ParcelFileDescriptor?): SeamPageNames = guarded {
            runCatching { destination?.close() }
            throw IllegalArgumentException("a notebook does not flow")
        }

        override fun produce(itemId: String, formatId: String?, pageSize: String?, destination: ParcelFileDescriptor?) = guarded {
            runCatching { destination?.close() }
            throw IllegalArgumentException("no such format")
        }

        override fun ingest(itemId: String, fileExtension: String?, source: ParcelFileDescriptor?) = guarded {
            runCatching { source?.close() }
            throw IllegalArgumentException("a notebook takes no file in")
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
    private fun <T> withStore(itemId: String, body: (NotebookStore) -> T): T {
        val seam = runBlocking { (application as NotesproutApp).soil.seam() }
        val session = seam.openItem(itemId, NotebookSchema.SCHEMA, Binder())
        try {
            return body(NotebookStore(SeamRowStore(session), itemId))
        } finally {
            runCatching { session.close(false) }
        }
    }

    private fun bake(store: NotebookStore, pageIds: List<String>, template: Boolean, bundleVersion: Int, out: ParcelFileDescriptor): SeamPageNames {
        val loaded = try {
            store.load()
        } catch (e: NotebookStore.NoPages) {
            throw IllegalStateException(Seam.RENDER_EMPTY)
        }
        val pages = when (val plan = RenderPlan.of(loaded.pages, pageIds)) {
            is RenderPlan.Outcome.Ready -> plan.pages
            RenderPlan.Outcome.Empty -> throw IllegalStateException(Seam.RENDER_EMPTY)
            RenderPlan.Outcome.Damaged -> throw IllegalStateException(Seam.RENDER_DAMAGED)
            RenderPlan.Outcome.TooLong -> throw IllegalStateException(Seam.RENDER_TOO_LONG)
        }
        // Every page's content first: titles, sticky sources and page links come from it, and
        // the bundle's link trailer is declared before the first page is written.
        val contents = pages.map { store.readPage(it.ref) }
        val withLinks = bundleVersion >= PageBundle.VERSION
        val endnotes = if (withLinks) Endnotes.plan(endnoteSources(store, pages, contents), pages.size) else Endnotes.Plan(emptyList(), emptyList())
        val total = pages.size + endnotes.notes.size
        if (total > PageBundle.MAX_PAGES) throw IllegalStateException(Seam.RENDER_TOO_LONG)
        val links = if (withLinks) endnotes.links + pageLinks(pages, contents) else emptyList()

        val metrics = resources.displayMetrics
        val paints = PagePaints.of(metrics.scaledDensity, runCatching { AppCompatResources.getDrawable(this, com.symmetricalpalmtree.soil.paper.R.drawable.ic_sticker_2)?.mutate() }.getOrNull())
        val numbers = ArrayList<Int>(total)
        val titles = ArrayList<String>(total)
        var templateId: String? = null
        var paper: Bitmap? = null
        try {
            PageBundle.Writer(ParcelFileDescriptor.AutoCloseOutputStream(out), total, links).use { writer ->
                pages.forEachIndexed { index, page ->
                    if (template && page.ref.templateId.isNotEmpty() && page.ref.templateId != templateId) {
                        paper?.recycle()
                        paper = null
                        templateId = page.ref.templateId
                        paper = Bitmaps.decodeBounded(store.templateBlob(page.ref.templateId), MAX_TEMPLATE_EDGE)
                    } else if (!template || page.ref.templateId.isEmpty()) {
                        paper?.recycle()
                        paper = null
                        templateId = null
                    }
                    val content = contents[index]
                    numbers += page.number
                    titles += PageLabels.titleOf(content).orEmpty()
                    writer.writePage(page.widthPx, page.heightPx, PageRaster.toPng(page.widthPx, page.heightPx, paper, content, metrics.density, paints))
                }
                paper?.recycle()
                paper = null
                for (note in endnotes.notes) {
                    numbers += note.fromPageLabel
                    titles += Endnotes.caption(note.number, note.fromPageLabel)
                    writer.writePage(note.widthPx, note.heightPx, bakeEndnote(note, store.stickyContent(note.stickyId)))
                }
            }
        } finally {
            paper?.recycle()
        }
        Slog.d(TAG) { "rendered ${pages.size} page(s) + ${endnotes.notes.size} endnote(s)" }
        return SeamPageNames(List(total) { "" }, numbers, titles)
    }

    /** Every sticky note with content, loose on a page or wrapped in a link, as an endnote source. */
    private fun endnoteSources(store: NotebookStore, pages: List<RenderPlan.Page>, contents: List<PageContent>): List<Endnotes.Source> {
        val sources = ArrayList<Endnotes.Source>()
        pages.forEachIndexed { index, page ->
            val content = contents[index]
            val stickies = store.withContent(content.stickies + content.links.flatMap { it.stickies })
            for (s in stickies) {
                if (s.strokes.isEmpty()) continue
                sources += Endnotes.Source(
                    stickyId = s.id, fromPage = index + 1, fromPageLabel = page.number,
                    iconL = s.x, iconT = s.y, iconR = s.x + s.width, iconB = s.y + s.height,
                    contentW = s.contentW, contentH = s.contentH, pageW = page.widthPx, pageH = page.heightPx,
                )
            }
        }
        return sources
    }

    /** A link to another page of this notebook that is in the bundle becomes a jump. */
    private fun pageLinks(pages: List<RenderPlan.Page>, contents: List<PageContent>): List<PageBundle.Link> {
        val position = HashMap<String, Int>(pages.size)
        pages.forEachIndexed { index, page -> position[page.ref.id] = index + 1 }
        val links = ArrayList<PageBundle.Link>()
        contents.forEachIndexed { index, content ->
            for (link in content.links) {
                val decoded = LinkPayload.decode(link.payload) ?: continue
                if (decoded.kind != LinkPayload.KIND_PAGE) continue
                val to = position[decoded.pageId] ?: continue
                if (link.width <= 0f || link.height <= 0f) continue
                links += PageBundle.Link(index + 1, link.x, link.y, link.x + link.width, link.y + link.height, to)
            }
        }
        return links
    }

    private fun bakeEndnote(note: Endnotes.Note, strokes: List<Stroke>): ByteArray {
        val w = note.widthPx
        val h = note.heightPx
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw IOException("a ${w}x$h endnote would not allocate", e)
        }
        return try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.save()
            canvas.clipRect(0, 0, w, note.contentH)
            StrokeRasterizer.draw(canvas, strokes)
            canvas.restore()
            val top = note.contentH.toFloat()
            canvas.drawRect(0f, top, w.toFloat(), top + 1f, captionPaint)
            val fm = captionPaint.fontMetrics
            val baseline = top + Endnotes.CAPTION_PX / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(Endnotes.caption(note.number, note.fromPageLabel), Endnotes.CAPTION_INSET_PX, baseline, captionPaint)
            PageRaster.encodePng(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = Endnotes.CAPTION_TEXT_PX
        typeface = Typeface.SANS_SERIF
    }

    private companion object {
        const val TAG = "RenderService"
        const val MAX_TEMPLATE_EDGE = 4096
    }
}
