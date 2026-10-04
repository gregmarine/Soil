package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.PageBundle
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** A bundle into one-page bundles, version 1 each (a lone page has no links to carry), the pixels
 *  copied and never re-encoded. */
object BundleSplit {

    fun split(input: InputStream, sink: (index: Int, pageCount: Int) -> OutputStream): Int =
        PageBundle.Reader(input).use { reader ->
            val count = reader.pageCount
            for (index in 0 until count) {
                val page = reader.readPage()
                PageBundle.Writer(sink(index, count), pageCount = 1).use { it.writePage(page.widthPx, page.heightPx, page.image) }
            }
            count
        }

    fun split(bundle: File, dir: File): List<File> {
        val parts = ArrayList<File>()
        bundle.inputStream().use { input ->
            split(input) { index, _ ->
                val part = File(dir, "page-${index + 1}.pages")
                parts += part
                FileOutputStream(part)
            }
        }
        return parts
    }
}
