package com.symmetricalpalmtree.soil.notesprout.convert

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.markdown.rich.RichAttr
import com.symmetricalpalmtree.soil.markdown.rich.RichBlock
import com.symmetricalpalmtree.soil.notesprout.data.PageContent
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import com.symmetricalpalmtree.soil.notesprout.objects.PageText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvertPlanTest {

    private fun stroke(id: String, y: Float) = Stroke(
        id = id, points = listOf(StrokePoint(10f, y, 1f, 0f, 0L), StrokePoint(60f, y + 20f, 1f, 0f, 0L)),
        color = 0xFF000000.toInt(), width = 3f, style = StrokeStyle.PEN,
    )

    private fun heading(text: String, level: Int, y: Float) = Heading("h-$y", text, level, 10f, y, 200f, 40f, 0)
    private fun text(markdown: String, y: Float) = PageText("t-$y", markdown, 10f, y, 200f, 40f, 0)
    private fun ids(piece: ConvertPlan.Piece) = (piece as ConvertPlan.Piece.Ink).strokes.map { it.id }
    private fun words(piece: ConvertPlan.Piece) = (piece as ConvertPlan.Piece.Words).blocks

    @Test
    fun `a page of only handwriting is one stretch of ink`() {
        val pieces = ConvertPlan.pieces(PageContent(listOf(0L to stroke("a", 10f), 1L to stroke("b", 300f))))
        assertEquals(listOf("a", "b"), ids(pieces.single()))
    }

    @Test
    fun `ink is read in stretches between the things placed among it, top to bottom`() {
        val content = PageContent(
            strokes = listOf(0L to stroke("below-all", 900f), 1L to stroke("top", 10f), 2L to stroke("middle", 400f)),
            headings = listOf(heading("Second part", 2, 700f), heading("Title", 1, 200f)),
        )
        val pieces = ConvertPlan.pieces(content)
        assertEquals(5, pieces.size)
        assertEquals(listOf("top"), ids(pieces[0]))
        assertEquals(listOf(RichBlock(RichAttr.heading(1), "Title")), words(pieces[1]))
        assertEquals(listOf("middle"), ids(pieces[2]))
        assertEquals(listOf(RichBlock(RichAttr.heading(2), "Second part")), words(pieces[3]))
        assertEquals(listOf("below-all"), ids(pieces[4]))
    }

    @Test
    fun `a text object is Markdown already and comes across as its blocks`() {
        val pieces = ConvertPlan.pieces(PageContent(emptyList(), texts = listOf(text("- one\n- two", 50f))))
        assertEquals(2, words(pieces.single()).size)
    }

    @Test
    fun `a Bible reference comes across as its words linked to the passage, the verses as words`() {
        val wire = "JHN:3:14-3:18"
        val reference = PageLink("l1", "L1|1|3|$wire|", 1, 10f, 50f, 200f, 40f, 0, emptyList(), texts = listOf(text("Jn 3:14-18", 50f)))
        val verses = PageLink("l2", "L1|1|4|$wire|", 1, 10f, 120f, 200f, 40f, 0, emptyList(), texts = listOf(text("**John 3:14–18**\n\n14 Just as", 120f)))
        val pieces = ConvertPlan.pieces(PageContent(emptyList(), links = listOf(reference, verses)))
        val md = pieces.map { ConvertPlan.markdown(words(it)) }
        assertEquals("[Jn 3:14-18](bible:$wire)\n", md[0])
        assertTrue(md[1]!!.startsWith("**John 3:14–18**"))
    }

    @Test
    fun `an empty page gives nothing`() {
        assertTrue(ConvertPlan.pieces(PageContent.EMPTY).isEmpty())
        assertNull(ConvertPlan.markdown(emptyList()))
    }

    @Test
    fun `hand-wrapped lines join into paragraphs and a blank line parts them`() {
        assertEquals(listOf("The quick brown fox jumps.", "A new thought."), ConvertPlan.paragraphs("The quick brown\nfox jumps.\n\nA new thought.").map { it.text })
    }

    @Test
    fun `a last word alone on its line is still part of the paragraph`() {
        assertEquals(listOf("This should translate into another line."), ConvertPlan.paragraphs("This should translate into another\nline.").map { it.text })
        assertEquals(listOf("First line here", "line."), ConvertPlan.paragraphs("First line here\n\nline.").map { it.text })
        assertEquals(listOf("one two", "three."), ConvertPlan.paragraphs("one two\n \nthree.").map { it.text })
    }

    @Test
    fun `what was read is words, so marks in it are escaped, while a heading keeps its level`() {
        val blocks = listOf(RichBlock(RichAttr.heading(2), "Notes")) + ConvertPlan.paragraphs("# not a heading\n\n2 * 3 * 4")
        assertEquals("## Notes\n\n\\# not a heading\n\n2 \\* 3 * 4\n", ConvertPlan.markdown(blocks))
    }
}
