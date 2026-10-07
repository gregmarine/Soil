package com.symmetricalpalmtree.soil.calsprout

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.gpaper.core.render.StrokeRasterizer
import com.symmetricalpalmtree.soil.ext.PageBundle
import com.symmetricalpalmtree.soil.paper.core.CalendarTarget
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.IItemRenderer
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck
import com.symmetricalpalmtree.soil.seam.SeamPageNames
import com.symmetricalpalmtree.soil.seam.SeamRenderInfo
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate

/**
 * **The calendar's pages for Soil's export screen**, in its render-only mode: Soil binds this,
 * guarded by its own permission, and asks for the pages a [RenderKey] names, or a rendered
 * bundle of them. The pages are read through the app's one store lease ([CalsproutApp.calendar])
 * and baked exactly as the screen bakes them — the grid at the page's own size with today's
 * ring and the events' marks, then the ink — one page in memory at a time. Nothing is written.
 *
 * The paper toggle is the grid: off, the ink alone on white. A page with no row yet (nothing
 * written on it) is still a page — blank paper at the surface's size, the display's.
 */
class RenderService : Service() {

    private val binder = object : IItemRenderer.Stub() {

        override fun pages(itemId: String): SeamPageNames = guarded {
            val targets = RenderKey.pagesOf(itemId) ?: throw IllegalStateException(Seam.RENDER_DAMAGED)
            names(targets)
        }

        override fun render(itemId: String, pageIds: List<String>?, template: Boolean, bundleVersion: Int, destination: ParcelFileDescriptor?): SeamPageNames = guarded {
            val out = destination ?: throw IllegalArgumentException("no destination")
            try {
                val targets = RenderKey.pagesOf(itemId) ?: throw IllegalStateException(Seam.RENDER_DAMAGED)
                bake(targets, template, out)
            } finally {
                runCatching { out.close() }
            }
        }

        // The calendar is no item: nothing of it is ever imported under another id.
        override fun relabelStatements(oldId: String, newId: String): List<String> = guarded { emptyList() }

        // The calendar is its pages: it does not flow, and writes no format of its own.
        override fun describe(): SeamRenderInfo = guarded { SeamRenderInfo.PAGES_ONLY }

        override fun renderFlow(itemId: String, pageSize: String?, bundleVersion: Int, destination: ParcelFileDescriptor?): SeamPageNames = guarded {
            runCatching { destination?.close() }
            throw IllegalArgumentException("the calendar does not flow")
        }

        override fun produce(itemId: String, formatId: String?, pageSize: String?, destination: ParcelFileDescriptor?) = guarded {
            runCatching { destination?.close() }
            throw IllegalArgumentException("no such format")
        }

        override fun ingest(itemId: String, fileExtension: String?, source: ParcelFileDescriptor?) = guarded {
            runCatching { source?.close() }
            throw IllegalArgumentException("the calendar takes no file in")
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

    private fun names(targets: List<CalendarTarget>): SeamPageNames =
        SeamPageNames(targets.map { RenderKey.of(it) + "#" + it.half }, targets.indices.map { it + 1 }, targets.map { RenderKey.titleOf(it) })

    private fun bake(targets: List<CalendarTarget>, template: Boolean, out: ParcelFileDescriptor): SeamPageNames {
        val app = application as CalsproutApp
        val store = runBlocking { app.calendar() }
        val marks = runBlocking { app.events() }
        val metrics = resources.displayMetrics
        val palette = CalendarTemplate.Palette(
            ink = ContextCompat.getColor(this, com.symmetricalpalmtree.soil.paper.R.color.inkBlack),
            light = ContextCompat.getColor(this, com.symmetricalpalmtree.soil.paper.R.color.inkLight),
        )
        val notes = getString(R.string.calendar_notes_label)
        val today = LocalDate.now()
        PageBundle.Writer(ParcelFileDescriptor.AutoCloseOutputStream(out), targets.size).use { writer ->
            for (t in targets) {
                val stored = store.readPage(t)
                // A page with no row, or one minted at 0 × 0, is the surface's size: the display's.
                val w = if (stored.width > 0f && stored.height > 0f) stored.width.toInt() else metrics.widthPixels
                val h = if (stored.width > 0f && stored.height > 0f) stored.height.toInt() else metrics.heightPixels
                if (w <= 0 || h <= 0) throw IllegalStateException(Seam.RENDER_DAMAGED)
                val grid = if (!template) null else {
                    val (from, to) = GridMarks.rangeOf(t)
                    val dayMarks = marks.marksFor(from, to)
                    when (t.kind) {
                        CalendarTarget.KIND_WEEK -> CalendarTemplate.week(CalendarGeometry.week(w, h, metrics.density), t.localDate, today, metrics.density, palette, notes, dayMarks)
                        CalendarTarget.KIND_DAY -> CalendarTemplate.day(CalendarGeometry.day(w, h, metrics.density), t.half, metrics.density, palette, dayMarks[t.localDate].orEmpty())
                        else -> CalendarTemplate.month(CalendarGeometry.month(w, h, metrics.density), t.localDate, today, metrics.density, palette, notes, dayMarks)
                    }
                }
                try {
                    writer.writePage(w, h, toPng(w, h, grid, stored.strokes.map { it.second }))
                } finally {
                    grid?.recycle()
                }
            }
        }
        Slog.d(TAG) { "rendered ${targets.size} page(s)" }
        return names(targets)
    }

    /** One page, lossless: white, the grid under the ink when there is one. */
    private fun toPng(w: Int, h: Int, grid: Bitmap?, strokes: List<com.symmetricalpalmtree.gpaper.core.model.Stroke>): ByteArray {
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            throw IOException("a ${w}x$h page would not allocate", e)
        }
        return try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            if (grid != null) canvas.drawBitmap(grid, 0f, 0f, null)
            StrokeRasterizer.draw(canvas, strokes)
            val bytes = ByteArrayOutputStream(w * h / 8)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw IOException("the page would not encode")
            bytes.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAG = "RenderService"
    }
}
