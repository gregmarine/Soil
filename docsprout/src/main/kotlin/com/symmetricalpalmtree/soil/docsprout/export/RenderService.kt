package com.symmetricalpalmtree.soil.docsprout.export

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.soil.docsprout.DocsproutApp
import com.symmetricalpalmtree.soil.docsprout.data.DocsproutPrefs
import com.symmetricalpalmtree.soil.docsprout.data.DocumentSchema
import com.symmetricalpalmtree.soil.docsprout.data.DocumentStore
import com.symmetricalpalmtree.soil.ext.PageBundle
import com.symmetricalpalmtree.soil.markdown.rich.RichDoc
import com.symmetricalpalmtree.soil.markdown.rich.RichParse
import com.symmetricalpalmtree.soil.markdown.rich.RichPlain
import com.symmetricalpalmtree.soil.markdown.rich.normalized
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.IItemRenderer
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck
import com.symmetricalpalmtree.soil.seam.SeamFormat
import com.symmetricalpalmtree.soil.seam.SeamPageNames
import com.symmetricalpalmtree.soil.seam.SeamRenderInfo
import com.symmetricalpalmtree.soil.seamkit.SeamRowStore
import kotlinx.coroutines.runBlocking

/**
 * **The document for Soil's export and import.** Soil binds this, guarded by its own permission.
 * A document flows: it has no pages of its own, so it is laid out at the page size the person
 * chose ([PageLayout]), as page pictures for the extensions that take pages, or written whole in
 * one of the formats only this app can write: its Markdown, its words, or a PDF whose text is
 * text. The item is opened through the seam as the document screen opens it, read and closed.
 * Nothing is written to it, and none of its words is ever logged.
 */
class RenderService : Service() {

    private val binder = object : IItemRenderer.Stub() {

        /** A document has no pages to name. */
        override fun pages(itemId: String): SeamPageNames = guarded { SeamPageNames(emptyList(), emptyList(), emptyList()) }

        override fun describe(): SeamRenderInfo = guarded { DocumentFormats.INFO }

        /** Asked as a kind with pages of its own is asked: the screen's size. */
        override fun render(itemId: String, pageIds: List<String>?, template: Boolean, bundleVersion: Int, destination: ParcelFileDescriptor?): SeamPageNames =
            renderFlow(itemId, Seam.PAGE_SCREEN, bundleVersion, destination)

        override fun renderFlow(itemId: String, pageSize: String?, bundleVersion: Int, destination: ParcelFileDescriptor?): SeamPageNames = guarded {
            val out = destination ?: throw IllegalArgumentException("no destination")
            try {
                val layout = layoutOf(itemId, pageSize)
                val count = layout.pages.size
                PageBundle.Writer(ParcelFileDescriptor.AutoCloseOutputStream(out), count, emptyList()).use { writer ->
                    // One page picture in memory at a time.
                    for (index in 0 until count) writer.writePage(layout.spec.widthPx, layout.spec.heightPx, layout.png(index))
                }
                Slog.d(TAG) { "rendered $count page(s) at ${layout.spec.widthPx}x${layout.spec.heightPx}" }
                SeamPageNames(List(count) { "" }, List(count) { it + 1 }, List(count) { "" })
            } finally {
                runCatching { out.close() }
            }
        }

        override fun produce(itemId: String, formatId: String?, pageSize: String?, destination: ParcelFileDescriptor?) = guarded {
            val out = destination ?: throw IllegalArgumentException("no destination")
            try {
                when (formatId) {
                    DocumentFormats.MARKDOWN -> {
                        val markdown = markdownOf(itemId)
                        if (markdown.isBlank()) throw IllegalStateException(Seam.RENDER_EMPTY)
                        ParcelFileDescriptor.AutoCloseOutputStream(out).use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
                    }
                    DocumentFormats.TEXT -> {
                        val text = RichPlain.write(documentOf(itemId))
                        ParcelFileDescriptor.AutoCloseOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    }
                    DocumentFormats.PDF -> {
                        val layout = layoutOf(itemId, pageSize)
                        ParcelFileDescriptor.AutoCloseOutputStream(out).use { layout.writePdf(it) }
                        Slog.d(TAG) { "wrote a PDF of ${layout.pages.size} page(s)" }
                    }
                    else -> throw IllegalArgumentException("no such format")
                }
            } finally {
                runCatching { out.close() }
            }
        }

        override fun relabelStatements(oldId: String, newId: String): List<String> = guarded { Relabel.statements(oldId, newId) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun markdownOf(itemId: String): String {
        val seam = runBlocking { (application as DocsproutApp).soil.seam() }
        val session = seam.openItem(itemId, DocumentSchema.SCHEMA, Binder())
        try {
            return DocumentStore(SeamRowStore(session), itemId).load()
        } finally {
            // Untidied: nothing was written.
            runCatching { session.close(false) }
        }
    }

    /** The document, or the refusal an empty one gets: there is nothing to put on a page. */
    private fun documentOf(itemId: String): RichDoc {
        val doc = RichParse.parse(markdownOf(itemId)).doc.normalized()
        if (doc.blocks.isEmpty()) throw IllegalStateException(Seam.RENDER_EMPTY)
        return doc
    }

    private fun layoutOf(itemId: String, pageSize: String?): PageLayout {
        val layout = PageLayout(documentOf(itemId), PageSpec.of(this, pageSize.orEmpty(), DocsproutPrefs(this).textSize))
        if (layout.pages.isEmpty()) throw IllegalStateException(Seam.RENDER_EMPTY)
        if (layout.pages.size > PageBundle.MAX_PAGES) throw IllegalStateException(Seam.RENDER_TOO_LONG)
        return layout
    }

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

    private companion object {
        const val TAG = "RenderService"
    }
}

/** What Docsprout writes itself, beyond page pictures. */
object DocumentFormats {
    const val MARKDOWN = "md"
    const val TEXT = "txt"
    const val PDF = "pdf"

    val INFO = SeamRenderInfo(
        flowing = true,
        formats = listOf(
            SeamFormat(MARKDOWN, "Markdown", "md", "text/markdown", paged = false),
            SeamFormat(TEXT, "Plain text", "txt", "text/plain", paged = false),
            SeamFormat(PDF, "PDF with selectable text", "pdf", "application/pdf", paged = true),
        ),
    )
}
