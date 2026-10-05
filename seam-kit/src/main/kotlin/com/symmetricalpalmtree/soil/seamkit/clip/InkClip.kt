package com.symmetricalpalmtree.soil.seamkit.clip

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.soil.paper.core.InkColorCodec
import com.symmetricalpalmtree.soil.paper.core.StrokeCodec
import com.symmetricalpalmtree.soil.paper.ink.StrokeBlob
import com.symmetricalpalmtree.soil.paper.ink.StrokeRows

/**
 * **Ink on the clipboard**, for the two things that are not a notebook: the Scratch Pad, which
 * copies its ink there, and a document, which pastes ink as recognised words.
 *
 * There is one clipboard for ink and it is the notebook kind's ([SLOT]): the pad writes what a
 * lasso Copy in a notebook would have written, an objects payload of stroke rows, so a notebook
 * pastes the pad's ink with the Paste it already has, and a document reads ink whether it was
 * copied on the pad or lassoed in a notebook. Pure.
 */
object InkClip {

    /** The clipboard slot ink lives in: the notebook kind's. */
    const val SLOT = "notebook"

    /**
     * The slot beside it that keeps the words ink was read as, so a second paste into a document
     * does not read it again. It belongs to the ink whose `copiedAt` its own header carries; the
     * words of any other ink are stale, and are not used.
     */
    const val WORDS_SLOT = "ink_words"
    const val WORDS_KIND = "text"

    private const val TYPE_STROKE = "stroke"
    private const val TYPE_STICKY = "sticky_note"

    /** The parent every row of a pad copy names: a page that is not in the payload, which is how
     *  a paste knows these rows are the top of what was copied. */
    private const val PAD_PAGE = "scratch-pad"

    /** [strokes] as a lasso Copy would have put them on the clipboard, or null for none. No
     *  notebook is named as the source: the pad is no notebook, and nothing in ink re-points. */
    fun envelopeOf(strokes: List<Stroke>, now: Long): ClipEnvelope? {
        val ink = strokes.filter { it.points.isNotEmpty() }
        if (ink.isEmpty()) return null
        return ClipEnvelope(
            version = ClipEnvelope.VERSION, kind = ClipEnvelope.KIND_OBJECTS, sourceNotebookId = "", copiedAt = now,
            rows = ink.mapIndexed { i, s ->
                ClipRow(
                    id = s.id, parentId = PAD_PAGE, type = TYPE_STROKE, order = i,
                    color = InkColorCodec.encode(s.color), strokeWidth = s.width, style = s.style.name,
                    blob = ClipRow.encodeBlob(StrokeBlob.encode(s)),
                )
            },
        )
    }

    /**
     * The handwriting in a payload, in writing order: every stroke row but a sticky note's own
     * (those are in the note's space, not the page's). A row that does not read is dropped. A
     * page payload, an objects payload and a pad copy all answer here.
     */
    fun strokesOf(env: ClipEnvelope): List<Stroke> {
        val stickies = env.rows.filter { it.type == TYPE_STICKY }.mapTo(HashSet()) { it.id }
        return env.rows.filter { it.type == TYPE_STROKE && it.parentId !in stickies }.sortedBy { it.order }.mapNotNull(::strokeOf)
    }

    private fun strokeOf(row: ClipRow): Stroke? = try {
        val points = StrokeCodec.decode(row.blobBytes() ?: return null)
        if (points.size == 0) null else {
            val list = ArrayList<StrokePoint>(points.size)
            for (i in 0 until points.size) list += StrokePoint(points.x[i], points.y[i], points.pressure?.get(i) ?: 1f, points.tilt?.get(i) ?: 0f, 0L)
            Stroke(id = row.id, points = list, color = InkColorCodec.decode(row.color), width = row.strokeWidth ?: Stroke.DEFAULT_WIDTH, style = StrokeRows.styleOf(row.style.orEmpty()))
        }
    } catch (_: Exception) {
        null
    }
}
