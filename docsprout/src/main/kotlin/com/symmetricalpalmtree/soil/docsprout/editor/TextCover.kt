package com.symmetricalpalmtree.soil.docsprout.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import com.symmetricalpalmtree.soil.docsprout.editor.rich.BlockMetrics
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichCodec
import com.symmetricalpalmtree.soil.markdown.rich.RichParse
import java.io.ByteArrayOutputStream

/**
 * A document's library cover: its opening lines, drawn as the rendered editor draws them, onto a
 * white page.
 *
 * **The canvas and the density are fixed**, not the device's, so the same document's card looks
 * the same on the Nomad and the Manta. It is a thumbnail of prose, so legibility beats fidelity:
 * a generous margin, body type big enough to read at card size, and the text simply clipped
 * where it runs off the bottom edge.
 *
 * A blank document is a white card: leaving a previous cover standing would show words the
 * document no longer has.
 */
object TextCover {

    /** 3:4, the family's page proportion; the long edge is the most the seam takes for a cover. */
    const val WIDTH_PX = 384
    const val HEIGHT_PX = 512

    private const val DENSITY = 1.28f
    private const val BODY_SIZE_PX = 12f
    private const val MARGIN_PX = 22

    /** How much of the document is even considered: a cover shows the opening. */
    private const val MAX_LINES = 60
    private const val MAX_CHARS = 2000

    /** The cover as the seam takes it: lossy WEBP, q100. */
    fun encode(markdown: String): ByteArray {
        val bitmap = draw(markdown)
        try {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            val out = ByteArrayOutputStream()
            bitmap.compress(format, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** The page itself. The caller owns the bitmap and must recycle it. */
    fun draw(markdown: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val head = opening(markdown)
        if (head.isBlank()) return bitmap
        val width = WIDTH_PX - 2 * MARGIN_PX
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = BODY_SIZE_PX
        }
        val spanned = RichCodec.toSpannable(RichParse.parse(head).doc, BlockMetrics(BODY_SIZE_PX, DENSITY))
        val layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, width).build()
        // The bitmap's own bounds are the clip. No `maxLines`: that would ellipsize.
        canvas.save()
        canvas.translate(MARGIN_PX.toFloat(), MARGIN_PX.toFloat())
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    /** The opening lines: at most [MAX_LINES] lines and [MAX_CHARS] characters of them. Pure. */
    fun opening(markdown: String): String {
        if (markdown.isEmpty()) return ""
        val capped = if (markdown.length > MAX_CHARS) markdown.take(MAX_CHARS) else markdown
        var cut = 0
        var lines = 0
        while (lines < MAX_LINES) {
            val nl = capped.indexOf('\n', cut)
            if (nl < 0) return capped
            cut = nl + 1
            lines++
        }
        return capped.substring(0, cut)
    }
}
