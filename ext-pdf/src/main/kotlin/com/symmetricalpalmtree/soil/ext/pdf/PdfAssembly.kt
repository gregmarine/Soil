package com.symmetricalpalmtree.soil.ext.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.soil.ext.PageBundle
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The bundle into a PDF: one page per bundle page at its own pixel size (one pixel is one PDF
 * unit, or the points Soil says a pixel stands for when the pages were drawn at a paper size), the picture as an 8-bit grayscale image compressed losslessly ([GrayFlate]), the link
 * trailer as go-to annotations, and the password applied last when one was asked for.
 *
 * Grayscale is the decision of 2026-10-03: the Nomad's ink is grey on white, and a page this way
 * is about a twentieth of the full-colour JPEG it used to be, with nothing lost. When a Sprout
 * app draws in colour, this is the place that changes.
 *
 * An unprotected export holds its streams in memory up to [MAIN_MEMORY_BYTES] and spills the rest
 * to a scratch file in [scratchDir] (the extension's own cache), so a long notebook's pages do not
 * all sit on the heap at once; pdfbox deletes the scratch file when the document closes. A
 * **protected** export never spills: pdfbox encrypts only on save, so its scratch file would hold
 * the pages in the clear. It stays wholly in memory, as before.
 */
internal object PdfAssembly {

    /** What the document may keep on the heap before it spills to its scratch file. */
    private const val MAIN_MEMORY_BYTES = 16L * 1024 * 1024

    fun assemble(source: ParcelFileDescriptor, destination: ParcelFileDescriptor, exportSecret: String?, tag: String, scratchDir: File, pagePoints: Float = 1f): Long {
        val startedAt = SystemClock.elapsedRealtime()
        var pages = 0
        val written: Long
        var secret = exportSecret
        val document = if (secret != null) PDDocument() else PDDocument(MemoryUsageSetting.setupMixed(MAIN_MEMORY_BYTES).setTempDir(scratchDir))
        try {
            var links: List<PageBundle.Link> = emptyList()
            val heights = ArrayList<Int>()
            ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
                stage("reading the page bundle") {
                    PageBundle.Reader(input).use { reader ->
                        pages = reader.pageCount
                        for (number in 1..reader.pageCount) {
                            val page = reader.readPage()
                            heights += page.heightPx
                            addPage(document, page, number, reader.pageCount, pagePoints)
                        }
                        links = reader.readLinks()
                    }
                }
            }
            if (links.isNotEmpty()) stage("linking the pages") { annotate(document, PdfLinks.annotations(links, heights), pagePoints) }
            written = if (secret == null) {
                stage("writing the PDF") { deliver(document, destination, tag) }
            } else {
                stage("protecting the PDF") {
                    val policy = StandardProtectionPolicy(secret, secret, AccessPermission())
                    policy.encryptionKeyLength = 128
                    policy.setPreferAES(true)
                    document.protect(policy)
                    deliver(document, destination, tag)
                }
            }
        } finally {
            secret = null
            runCatching { document.close() }.onFailure { Log.w(tag, "document close failed: ${it.javaClass.simpleName}") }
        }
        if (BuildConfig.DEBUG) Log.d(tag, "assembled $pages page(s) → $written bytes in ${SystemClock.elapsedRealtime() - startedAt} ms")
        return written
    }

    /** Delete the scratch files a job that died mid-way left behind (pdfbox's `PDFBox*.tmp`). Only
     *  unprotected exports ever write one. Best effort: a file that will not go is left. */
    fun sweepScratch(scratchDir: File, tag: String) {
        val stray = scratchDir.listFiles { f -> f.isFile && f.name.startsWith("PDFBox") && f.name.endsWith(".tmp") } ?: return
        var swept = 0
        for (f in stray) if (f.delete()) swept++
        if (swept > 0) Log.d(tag, "swept $swept stray scratch file(s)")
    }

    private fun addPage(document: PDDocument, page: PageBundle.Page, number: Int, count: Int, pagePoints: Float) {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(page.image, 0, page.image.size, options) ?: throw IllegalStateException("page $number of $count did not decode")
        val encoded: ByteArray
        try {
            if (bitmap.width != page.widthPx || bitmap.height != page.heightPx) {
                throw IllegalStateException("page $number of $count decoded ${bitmap.width}x${bitmap.height}, bundle declares ${page.widthPx}x${page.heightPx}")
            }
            encoded = GrayFlate.encode(page.widthPx, page.heightPx, grayPlane(bitmap))
        } finally {
            bitmap.recycle()
        }
        stage("assembling page $number of $count") {
            val image = PDImageXObject(document, ByteArrayInputStream(encoded), COSName.FLATE_DECODE, page.widthPx, page.heightPx, 8, PDDeviceGray.INSTANCE)
            val parms = COSDictionary().apply {
                setInt(COSName.PREDICTOR, GrayFlate.PREDICTOR)
                setInt(COSName.COLORS, 1)
                setInt(COSName.BITS_PER_COMPONENT, 8)
                setInt(COSName.COLUMNS, page.widthPx)
            }
            image.cosObject.setItem(COSName.DECODE_PARMS, parms)
            val w = page.widthPx * pagePoints
            val h = page.heightPx * pagePoints
            val pdfPage = PDPage(PDRectangle(w, h))
            document.addPage(pdfPage)
            PDPageContentStream(document, pdfPage).use { content -> content.drawImage(image, 0f, 0f, w, h) }
        }
    }

    /** Luminance of every pixel, row-major, one row of the bitmap in memory at a time. */
    private fun grayPlane(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val plane = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            val at = y * w
            for (x in 0 until w) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                plane[at + x] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
        }
        return plane
    }

    private fun annotate(document: PDDocument, annotations: List<PdfLinks.Annotation>, pagePoints: Float) {
        for (a in annotations) {
            val page = document.getPage(a.pageIndex)
            val link = PDAnnotationLink()
            link.rectangle = PDRectangle(a.llx * pagePoints, a.lly * pagePoints, (a.urx - a.llx) * pagePoints, (a.ury - a.lly) * pagePoints)
            link.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
            val destination = PDPageFitDestination()
            destination.page = document.getPage(a.targetIndex)
            link.action = PDActionGoTo().apply { setDestination(destination) }
            page.annotations.add(link)
        }
    }

    private fun deliver(document: PDDocument, destination: ParcelFileDescriptor, tag: String): Long {
        var count = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
            val counting = CountingOutputStream(output)
            document.save(CloseShield(counting))
            counting.flush()
            Sync.ifRegular(output.fd, tag, "PDF")
            count = counting.count
        }
        return count
    }

    /** pdfbox closes what it saves to; the descriptor's stream is closed by its owner. */
    private class CloseShield(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = out.flush()
    }

    private inline fun <T> stage(name: String, body: () -> T): T =
        try {
            body()
        } catch (e: IOException) {
            throw IllegalStateException("$name failed (${e.javaClass.simpleName})")
        }
}
