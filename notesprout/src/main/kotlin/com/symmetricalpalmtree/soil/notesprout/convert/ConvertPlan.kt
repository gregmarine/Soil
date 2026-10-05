package com.symmetricalpalmtree.soil.notesprout.convert

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.markdown.MarkdownReflow
import com.symmetricalpalmtree.soil.markdown.rich.RichAttr
import com.symmetricalpalmtree.soil.markdown.rich.RichBlock
import com.symmetricalpalmtree.soil.markdown.rich.RichDoc
import com.symmetricalpalmtree.soil.markdown.rich.RichParse
import com.symmetricalpalmtree.soil.markdown.rich.RichWrite
import com.symmetricalpalmtree.soil.notesprout.data.PageContent

/**
 * **A notebook page as the words of a document**, in the order a reader meets them. Pure.
 *
 * A page holds handwriting and things placed among it. The handwriting has to be read; a heading
 * and a text object are words already. So the page is cut into [Piece]s from top to bottom: each
 * heading and text object in its place, and between them the ink that sits above the next one,
 * read as one stretch. A link's wrapped ink and objects are part of the page like any other (the
 * link itself, a jump, does not come across); a sticky note's content is not on the page and is
 * left out.
 *
 * What is read is a person's words, not Markdown: each paragraph goes into the document as plain
 * words, and [RichWrite] escapes whatever in them would otherwise read as markup. A heading
 * keeps its level, and a text object, which is Markdown already, is read as Markdown.
 */
object ConvertPlan {

    sealed class Piece {
        /** Handwriting to be read, in writing order. */
        class Ink(val strokes: List<Stroke>) : Piece()

        /** Blocks that are words already. */
        class Words(val blocks: List<RichBlock>) : Piece()
    }

    private class Placed(val y: Float, val x: Float, val blocks: List<RichBlock>)

    fun pieces(content: PageContent): List<Piece> {
        val placed = ArrayList<Placed>()
        for (h in content.headings + content.links.flatMap { it.headings }) {
            val words = oneLine(h.text)
            if (words.isNotEmpty()) placed += Placed(h.y, h.x, listOf(RichBlock(RichAttr.heading(h.level.coerceIn(1, 6)), words)))
        }
        for (t in content.texts + content.links.flatMap { it.texts }) {
            val blocks = RichParse.parse(t.text).doc.blocks
            if (blocks.isNotEmpty()) placed += Placed(t.y, t.x, blocks)
        }
        placed.sortWith(compareBy<Placed> { it.y }.thenBy { it.x })

        val ink = (content.strokes.map { it.second } + content.links.flatMap { it.strokes }).filter { it.points.isNotEmpty() }
        // Each stroke goes with the stretch above the first placed thing that starts below its middle.
        val stretches = List(placed.size + 1) { ArrayList<Stroke>() }
        for (stroke in ink) {
            val middle = (stroke.bounds.top + stroke.bounds.bottom) / 2f
            var band = placed.indexOfFirst { middle < it.y }
            if (band < 0) band = placed.size
            stretches[band] += stroke
        }
        val out = ArrayList<Piece>()
        for (i in stretches.indices) {
            if (stretches[i].isNotEmpty()) out += Piece.Ink(stretches[i])
            if (i < placed.size) out += Piece.Words(placed[i].blocks)
        }
        return out
    }

    /**
     * What the recogniser read of one stretch, as paragraphs. It answers a line for each line of
     * handwriting, and a hand wraps where the page ends, not where the sentence does: the lines
     * of a paragraph are joined, and each line left after that is a paragraph.
     */
    fun paragraphs(recognised: String): List<RichBlock> {
        val lines = recognised.split('\n').joinToString("\n") { it.replace(HORIZONTAL, " ").trim() }
        return MarkdownReflow.reflow(lines).split('\n').map { it.trim() }.filter { it.isNotEmpty() }.map { RichBlock(RichAttr.PARAGRAPH, it) }
    }

    /** The document's Markdown, or null when there is nothing to make one of. */
    fun markdown(blocks: List<RichBlock>): String? = RichWrite.write(RichDoc(blocks)).text.takeIf { it.isNotBlank() }

    private fun oneLine(text: String): String = text.replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")
    private val HORIZONTAL = Regex("[^\\S\\n]+")
}
