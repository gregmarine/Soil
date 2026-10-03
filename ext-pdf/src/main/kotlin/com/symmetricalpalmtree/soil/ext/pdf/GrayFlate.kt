package com.symmetricalpalmtree.soil.ext.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * An 8-bit grayscale plane as a PDF image stream: PNG's "Up" predictor on every row, then Flate.
 * Ink on white compresses this way to about a twentieth of a full-colour JPEG, and exactly.
 * Pure, so the shape is tested on the JVM; the bitmap-to-plane step is in [PdfAssembly].
 */
internal object GrayFlate {

    /** PNG predictors, one filter byte per row. */
    const val PREDICTOR = 15
    private const val FILTER_UP = 2

    /** [rows] is [width] × [height] bytes, row-major. The result decodes with a Flate filter and
     *  `DecodeParms << /Predictor 15 /Colors 1 /BitsPerComponent 8 /Columns width >>`. */
    fun encode(width: Int, height: Int, rows: ByteArray): ByteArray {
        require(width > 0 && height > 0) { "empty plane" }
        require(rows.size == width * height) { "${rows.size} bytes for ${width}x$height" }
        val out = ByteArrayOutputStream(rows.size / 8 + 64)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            DeflaterOutputStream(out, deflater, 64 * 1024).use { z ->
                val line = ByteArray(width + 1)
                line[0] = FILTER_UP.toByte()
                for (y in 0 until height) {
                    val at = y * width
                    if (y == 0) {
                        System.arraycopy(rows, at, line, 1, width)
                    } else {
                        val above = at - width
                        for (x in 0 until width) line[x + 1] = (rows[at + x] - rows[above + x]).toByte()
                    }
                    z.write(line)
                }
            }
        } finally {
            deflater.end()
        }
        return out.toByteArray()
    }
}
