package com.symmetricalpalmtree.soil.notesprout.links

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Binder
import android.util.Log
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.data.NotebookStore
import com.symmetricalpalmtree.soil.notesprout.data.PageContent
import com.symmetricalpalmtree.soil.notesprout.data.PageRef
import com.symmetricalpalmtree.soil.notesprout.notebook.PageDraw
import com.symmetricalpalmtree.soil.notesprout.notebook.PagePaints
import com.symmetricalpalmtree.soil.seam.ISeamItem
import com.symmetricalpalmtree.soil.seamkit.SeamConnection
import com.symmetricalpalmtree.soil.seamkit.SeamRowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a page grid's pages come from: the open notebook through its live store, or another
 *  notebook through a session of its own. Every call suspends and does its reading on IO. */
interface PickerSource {
    suspend fun pages(): List<PageRef>
    suspend fun content(page: PageRef): PageContent?

    /** A blank page beside [anchorId] (`before` or after it), or at the end with no anchor.
     *  Null when it could not be made. Picker creations are not undoable. */
    suspend fun createPage(anchorId: String?, before: Boolean): PageRef?
}

/**
 * The open notebook, as the notebook screen hands it to the picker: the picker never opens a
 * second session on the file the screen holds. Armed right before the launch and dropped on
 * the way back; null means the process was rebuilt under the picker.
 */
object LinkPickerRelay {
    class Showing(val notebookId: String, val currentPageId: String, val source: PickerSource)

    @Volatile
    var showing: Showing? = null
}

/**
 * Another notebook, read through a session of its own for as long as the picker shows its
 * pages. Opened on first use, closed by [close] on the way out; the session is Soil's, so a
 * process death ends it too.
 */
class ForeignNotebook(private val soil: SeamConnection, val itemId: String) : PickerSource {

    private val owner = Binder()
    private var session: ISeamItem? = null
    private var store: NotebookStore? = null
    private var pages: List<PageRef> = emptyList()

    private suspend fun open(): NotebookStore = withContext(Dispatchers.IO) {
        store ?: run {
            val opened = soil.seam().openItem(itemId, NotebookSchema.SCHEMA, owner)
            session = opened
            NotebookStore(SeamRowStore(opened), itemId).also { store = it }
        }
    }

    override suspend fun pages(): List<PageRef> = withContext(Dispatchers.IO) {
        try {
            open().load().pages.also { pages = it }
        } catch (e: Exception) {
            Log.w(TAG, "the notebook's pages could not be read: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    override suspend fun content(page: PageRef): PageContent? = withContext(Dispatchers.IO) {
        runCatching { open().readPage(page) }.getOrNull()
    }

    override suspend fun createPage(anchorId: String?, before: Boolean): PageRef? = withContext(Dispatchers.IO) {
        try {
            val s = open()
            val current = pages.ifEmpty { s.load().pages.also { pages = it } }
            val anchor = current.firstOrNull { it.id == anchorId } ?: current.last()
            val (next, page) = s.insertPage(current, anchor.id, after = anchorId == null || !before)
            pages = next
            runCatching { soil.seam().setPageCount(itemId, next.size) }
            page
        } catch (e: Exception) {
            Log.w(TAG, "a page could not be added: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Let the session go. Never throws; safe to call more than once. */
    fun close() {
        val s = session ?: return
        session = null
        store = null
        runCatching { s.close(false) }
    }

    private companion object { const val TAG = "ForeignNotebook" }
}

/**
 * One page in miniature for a picker card: white paper, the page's content scaled from page px to
 * the card's width in the paper's own layering, and the page's own edge drawn on the bitmap. No
 * template and no link chrome: a preview shows what was written. Off Main.
 */
object PagePreview {

    fun render(page: PageRef, content: PageContent, outWidth: Int, outHeight: Int, density: Float, paints: PagePaints): Bitmap? {
        if (outWidth < 1 || outHeight < 1 || page.width <= 0f) return null
        val bmp = try {
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "a ${outWidth}x$outHeight preview would not allocate")
            return null
        }
        bmp.eraseColor(Color.WHITE)
        val canvas = Canvas(bmp)
        val scale = outWidth / page.width
        canvas.save()
        canvas.scale(scale, scale)
        PageDraw.drawContent(canvas, content, density, paints)
        canvas.restore()
        canvas.drawRect(0.5f, 0.5f, outWidth - 0.5f, outHeight - 0.5f, border)
        return bmp
    }

    private val border = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private const val TAG = "PagePreview"
}
