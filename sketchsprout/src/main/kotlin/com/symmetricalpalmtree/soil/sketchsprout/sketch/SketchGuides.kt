package com.symmetricalpalmtree.soil.sketchsprout.sketch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.sketchsprout.R
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchPage
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookStore
import com.symmetricalpalmtree.soil.sketchsprout.raster.GuideRows
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterImage
import com.symmetricalpalmtree.soil.sketchsprout.raster.RasterRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The sketch screen's **guides** (Notesprout SN's arc 51) — a grid and a reference image laid
 * under the page as a tool and never as a mark, per page — **and the sheet itself**: whatever
 * lies under the rasters is **one** page-sized bitmap ([GuideSheet]) of the page's paper, the
 * image at its opacity and the grid over both, handed to g-paper's `setSheet`. The screen keeps
 * only its hooks: the load in `loadPage`, a re-papering, the outside-contact dismissal, the
 * floating rects, the overflow entry and the exit.
 *
 * - **At every load** ([load]): the two guide rows and the paper are read, the image decoded
 *   behind the header guard, and the sheet built, all off the main thread; the screen sets it in
 *   the same breath as the rasters. A failure is a log line and no guides, never a dialog.
 * - **At every settings pick**: the sheet rebuilt and set, the rows written in pick order under a
 *   fair mutex; a failure is a log line. Not on the undo stack.
 * - **At a Pick… or a Remove**: the image row written or soft-deleted first; a failure there
 *   **is** a problem dialog, since the person asked for something and it did not happen.
 *
 * The picker is `ACTION_OPEN_DOCUMENT` for PNG / JPEG / WebP; the grant is read once and dropped,
 * and neither the Uri nor a byte of the picture is ever logged. `ImageDecoder` applies a photo's
 * EXIF orientation and reads the size before any pixel is allocated.
 */
class SketchGuides(
    private val activity: AppCompatActivity,
    private val paper: PaperView,
    root: ViewGroup,
    barView: LinearLayout,
    anchor: View,
    bandBottom: () -> Int?,
    private val store: () -> SketchbookStore?,
    private val usable: () -> Boolean,
    private val onBarChanged: () -> Unit,
    /** The sheet g-paper should hold now — the screen sets it and tracks it. */
    private val onSheet: (Bitmap?) -> Unit,
) {

    private var pageId: String? = null
    private var pageWidth = 0
    private var pageHeight = 0

    /** The page's guides as the screen knows them. Main thread. */
    var state: GuideState = GuideState.NONE
        private set

    /** The page's paper, owned by the screen's cache: never recycled here. */
    private var paperBitmap: Bitmap? = null

    /** The decoded reference, page-sized with its fit baked in; swapped and recycled only under [sheetLock]. */
    private var image: Bitmap? = null

    /** The bitmap g-paper holds by reference; recycled only after its replacement is set. */
    private var sheet: Bitmap? = null

    private val sheetLock = Mutex()
    private val writes = Mutex()
    private var pickingFor: String? = null

    private val picker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onPickResult(it) }

    val bar = GuidesBar(root, barView, anchor, bandBottom, paper, state = { state }, onChanged = { pick(it) }, onPickImage = { pickImage() }, onRemoveImage = { removeImage() })

    val isShowing: Boolean get() = bar.isShowing
    fun rects(): List<Rect> = bar.rects()
    fun contains(x: Int, y: Int): Boolean = bar.contains(x, y)

    fun toggle(anchor: View? = null) {
        if (bar.isShowing) { hide(); return }
        if (!usable()) return
        val shown = if (anchor == null) bar.show() else bar.show(anchor)
        if (shown) onBarChanged()
    }

    fun takeDown(): Boolean {
        if (!bar.isShowing) return false
        bar.hide()
        return true
    }

    fun hide() { if (takeDown()) onBarChanged() }

    // ── Loading a page ───────────────────────────────────────────────────────

    /**
     * Read [page]'s guides and build its sheet over [paperBitmap] — off the main thread, before
     * the rasters are decoded — and answer the sheet for the screen to set in its one synchronous
     * swap. The sheet is remembered here so a later pick can replace it.
     */
    suspend fun load(page: SketchPage, paperBitmap: Bitmap?): Bitmap? = sheetLock.withLock {
        pageId = page.id
        pageWidth = page.width.toInt()
        pageHeight = page.height.toInt()
        pickingFor = null
        this.paperBitmap = paperBitmap
        val t0 = SystemClock.elapsedRealtime()
        val s = store()
        val read = if (s == null) null else withContext(Dispatchers.IO) {
            runCatching { s.readGuides(page.id) }.onFailure { Log.w(TAG, "the page's guides could not be read: ${it.javaClass.simpleName}") }.getOrNull()
        }
        val decoded = read?.imageBytes?.let { bytes -> withContext(Dispatchers.IO) { RasterImage.decode(bytes, pageWidth, pageHeight) } }
        replaceImage(decoded)
        state = GuideState.of(read?.grid, read?.image, hasImage = read?.imageBytes != null)
        val next = buildSheet()
        swapSheet(next)
        Slog.d(TAG) { "guides loaded in ${SystemClock.elapsedRealtime() - t0} ms: grid ${state.gridKind}/${state.gridCount}${if (state.gridVisible) "" else " hidden"}, image ${read?.imageBytes?.size ?: 0} B${if (state.imageVisible) "" else " hidden"}, paper ${paperBitmap != null}" }
        next
    }

    /** The page was re-papered: the sheet is rebuilt over the new paper and set. */
    fun setPaper(paperBitmap: Bitmap?) {
        activity.lifecycleScope.launch {
            sheetLock.withLock {
                this@SketchGuides.paperBitmap = paperBitmap
                applySheet()
            }
        }
    }

    // ── Settings picks ───────────────────────────────────────────────────────

    private fun pick(next: GuideState) {
        if (!usable()) return
        val id = pageId ?: return
        state = next
        activity.lifecycleScope.launch { sheetLock.withLock { applySheet() } }
        remember(id, next)
        Slog.d(TAG) { "guides picked: $next" }
    }

    /** The grid row and the image's settings written, in pick order. A failure is a log line. */
    private fun remember(id: String, s: GuideState) {
        val grid = s.toGridRow()
        val image = if (s.hasImage) s.toImageRow() else null
        activity.lifecycleScope.launch {
            writes.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        val st = store() ?: throw IllegalStateException("no store")
                        st.writeGrid(id, grid)
                        if (image != null) st.setImageSettings(id, image)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w(TAG, "the page's guides could not be remembered: ${t.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    // ── The reference image ──────────────────────────────────────────────────

    private fun pickImage() {
        if (!usable()) return
        val id = pageId ?: return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp"))
        try {
            pickingFor = id
            picker.launch(intent)
        } catch (e: ActivityNotFoundException) {
            pickingFor = null
            Log.w(TAG, "no document picker")
            Dialogs.problem(activity, R.string.guides_no_picker_title, R.string.guides_no_picker_body)
        }
    }

    private fun onPickResult(result: ActivityResult) {
        val wanted = pickingFor
        pickingFor = null
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return
        val id = pageId ?: return
        if (wanted != null && wanted != id) { Log.w(TAG, "a picked image arrived for a page no longer shown; dropped"); return }
        if (!usable()) return
        activity.lifecycleScope.launch { takeImage(uri, id) }
    }

    private suspend fun takeImage(uri: Uri, id: String) {
        val w = pageWidth; val h = pageHeight
        val t0 = SystemClock.elapsedRealtime()
        val prepared = withContext(Dispatchers.IO) { prepare(uri, w, h) }
        if (prepared !is Prepared.Ok) {
            problem(if (prepared is Prepared.TooLarge) R.string.guides_image_too_large_body else R.string.guides_image_unreadable_body)
            return
        }
        val settings = state.withImage().toImageRow()
        val error = writes.withLock {
            withContext(Dispatchers.IO) { runCatching { store()?.writeImage(id, settings, prepared.bytes) ?: throw IllegalStateException("no store") }.exceptionOrNull() }
        }
        if (error != null) {
            prepared.page.recycle()
            Log.w(TAG, "the reference image could not be kept: ${error.javaClass.simpleName}")
            problem(if (error is SketchbookStore.RasterTooLarge) R.string.guides_image_too_large_body else R.string.guides_image_not_saved_body)
            return
        }
        Slog.d(TAG) { "reference image saved: ${prepared.bytes.size} B in ${SystemClock.elapsedRealtime() - t0} ms" }
        val landed = sheetLock.withLock {
            if (pageId != id) { prepared.page.recycle(); return@withLock false }
            replaceImage(prepared.page)
            state = state.withImage()
            applySheet()
            true
        }
        if (landed) bar.refresh()
    }

    private fun removeImage() {
        if (!usable()) return
        val id = pageId ?: return
        activity.lifecycleScope.launch {
            val error = writes.withLock {
                withContext(Dispatchers.IO) { runCatching { store()?.writeImage(id, null, null) ?: throw IllegalStateException("no store") }.exceptionOrNull() }
            }
            if (error != null) { problem(R.string.guides_image_not_removed_body); return@launch }
            val landed = sheetLock.withLock {
                if (pageId != id) return@withLock false
                replaceImage(null)
                state = state.withoutImage()
                applySheet()
                true
            }
            if (landed) bar.refresh()
        }
    }

    private sealed class Prepared {
        class Ok(val bytes: ByteArray, val page: Bitmap) : Prepared()
        object TooLarge : Prepared()
        object Failed : Prepared()
    }

    /** The whole IO half of a pick: decoded at the smallest power-of-two sample that keeps the
     *  long edge at or above the page's, fit into a page-sized transparent bitmap, encoded lossy,
     *  checked against the cap and the header guard before anything is written. */
    private fun prepare(uri: Uri, pageW: Int, pageH: Int): Prepared {
        if (pageW <= 0 || pageH <= 0) return Prepared.Failed
        val decoded = try {
            val source = ImageDecoder.createSource(activity.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(GuideSheet.sampleSize(info.size.width, info.size.height, pageW, pageH))
            }
        } catch (e: Exception) {
            Log.w(TAG, "a picked image would not decode: ${e.javaClass.simpleName}"); return Prepared.Failed
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "a picked image ran out of memory decoding"); return Prepared.Failed
        }
        var page: Bitmap? = null
        try {
            page = GuideSheet.fitToPage(decoded, pageW, pageH) ?: return Prepared.Failed
            val bytes = GuideSheet.encode(page)
            Slog.d(TAG) { "picked ${decoded.width}x${decoded.height} → ${pageW}x$pageH, ${bytes.size} B" }
            if (bytes.isEmpty()) return Prepared.Failed.also { page.recycle() }
            if (bytes.size > RasterRows.MAX_BYTES) return Prepared.TooLarge.also { page.recycle() }
            if (!GuideRows.fitsPage(bytes, pageW, pageH)) { Log.w(TAG, "the encoded reference does not read as a ${pageW}x$pageH WebP; refused"); page.recycle(); return Prepared.Failed }
            return Prepared.Ok(bytes, page)
        } catch (e: OutOfMemoryError) {
            page?.recycle()
            Log.w(TAG, "a picked image ran out of memory fitting or encoding")
            return Prepared.Failed
        } finally {
            decoded.recycle()
        }
    }

    private fun problem(bodyRes: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.problem(activity, R.string.guides_image_not_saved_title, bodyRes)
    }

    // ── The sheet ────────────────────────────────────────────────────────────

    private fun replaceImage(next: Bitmap?) {
        val old = image
        image = next
        if (old != null && old !== next) old.recycle()
    }

    /** Under [sheetLock]: the sheet for the state now, built on IO. Null when nothing lies under. */
    private suspend fun buildSheet(): Bitmap? {
        val s = state; val img = image; val pp = paperBitmap; val w = pageWidth; val h = pageHeight
        return withContext(Dispatchers.IO) {
            try {
                GuideSheet.render(w, h, pp, s, img)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "the sheet could not be allocated; the page shows without it"); null
            } catch (e: Exception) {
                Log.w(TAG, "the sheet could not be drawn: ${e.javaClass.simpleName}"); null
            }
        }
    }

    /** Under [sheetLock], on Main: build and hand the screen the sheet, recycling the old one
     *  only after the engine has let go of it. */
    private suspend fun applySheet() {
        val next = buildSheet()
        if (activity.isDestroyed) { next?.recycle(); return }
        onSheet(next)
        swapSheet(next)
    }

    private fun swapSheet(next: Bitmap?) {
        val old = sheet
        sheet = next
        if (old != null && old !== next) old.recycle()
    }

    private companion object {
        const val TAG = "SketchGuides"
    }
}
