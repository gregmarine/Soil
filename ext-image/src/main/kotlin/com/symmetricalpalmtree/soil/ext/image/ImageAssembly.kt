package com.symmetricalpalmtree.soil.ext.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.symmetricalpalmtree.soil.ext.PageBundle
import java.io.IOException

/** One bundle page into one image. A bundle of more than one page is refused outright: Soil
 *  guarantees one per call, and writing the first silently would misreport the export. */
internal object ImageAssembly {

    fun assemble(source: ParcelFileDescriptor, destination: ParcelFileDescriptor, encoding: ImageExportSpec.Encoding, tag: String): Long {
        val startedAt = SystemClock.elapsedRealtime()
        val page: PageBundle.Page
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            page = stage("reading the page bundle") {
                PageBundle.Reader(input).use { reader ->
                    requireOnePage(reader.pageCount)
                    val only = reader.readPage()
                    reader.readLinks()
                    only
                }
            }
        }
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeByteArray(page.image, 0, page.image.size, options) ?: throw IllegalStateException("page did not decode")
        val written: Long
        try {
            if (bitmap.width != page.widthPx || bitmap.height != page.heightPx) {
                throw IllegalStateException("page decoded ${bitmap.width}x${bitmap.height}, bundle declares ${page.widthPx}x${page.heightPx}")
            }
            written = stage("writing the image") { deliver(bitmap, destination, encoding, tag) }
        } finally {
            bitmap.recycle()
        }
        if (BuildConfig.DEBUG) Log.d(tag, "assembled ${page.widthPx}x${page.heightPx} ${encoding.format} q${encoding.quality} → $written bytes in ${SystemClock.elapsedRealtime() - startedAt} ms")
        return written
    }

    fun requireOnePage(pageCount: Int) {
        if (pageCount != 1) throw IllegalStateException("bundle carries $pageCount pages; one expected")
    }

    private fun deliver(bitmap: Bitmap, destination: ParcelFileDescriptor, encoding: ImageExportSpec.Encoding, tag: String): Long {
        var count = 0L
        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
            val counting = CountingOutputStream(output)
            if (!bitmap.compress(encoding.format, encoding.quality, counting)) throw IllegalStateException("page would not encode")
            counting.flush()
            Sync.ifRegular(output.fd, tag, "image")
            count = counting.count
        }
        return count
    }

    private inline fun <T> stage(name: String, body: () -> T): T =
        try {
            body()
        } catch (e: IOException) {
            throw IllegalStateException("$name failed (${e.javaClass.simpleName})")
        }
}
