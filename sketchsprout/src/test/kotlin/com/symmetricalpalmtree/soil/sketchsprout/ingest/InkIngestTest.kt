package com.symmetricalpalmtree.soil.sketchsprout.ingest

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.gpaper.core.model.StrokePoint
import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.seamkit.clip.InkClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkIngestTest {

    private fun stroke(id: String) = Stroke(id = id, points = listOf(StrokePoint(1f, 2f, 0.5f, 0f, 0L), StrokePoint(3f, 4f, 0.5f, 0f, 0L)), color = 0xFF808080.toInt(), width = 3f, style = StrokeStyle.PENCIL)

    @Test
    fun `a notebook's page envelope reads as pages, strokes black ink, paper carried, blank pages blank`() {
        var n = 0
        val env = InkClip.pageEnvelopeOf(
            listOf(
                InkClip.PageInk(1404f, 1872f, byteArrayOf(7, 7, 7), listOf(0L to stroke("a"), 1L to stroke("b"))),
                InkClip.PageInk(1404f, 1872f, null, emptyList()),
            ),
            now = 5L, newId = { "id${n++}" },
        )!!
        val pages = InkIngest.pagesOf(env)!!
        assertEquals(2, pages.size)
        assertEquals(1404f, pages[0].width)
        assertEquals(listOf("a", "b"), pages[0].strokes.map { it.id })
        assertTrue(pages[0].strokes.all { it.color == InkIngest.BLACK && it.style == StrokeStyle.PEN })
        assertTrue(byteArrayOf(7, 7, 7).contentEquals(pages[0].paper))
        assertTrue(pages[0].paperToken!!.isNotEmpty())
        assertTrue(pages[1].strokes.isEmpty())
        assertNull(pages[1].paper)
    }

    @Test
    fun `an objects envelope is not a sketchbook`() {
        val env = InkClip.envelopeOf(listOf(stroke("a")), now = 5L)!!
        assertEquals(ClipEnvelope.KIND_OBJECTS, env.kind)
        assertNull(InkIngest.pagesOf(env))
    }

    @Test
    fun `paste ink takes a page payload's first page alone, and every stroke of an objects payload`() {
        var n = 0
        val pages = InkClip.pageEnvelopeOf(
            listOf(
                InkClip.PageInk(1404f, 1872f, null, listOf(0L to stroke("am1"), 1L to stroke("am2"))),
                InkClip.PageInk(1404f, 1872f, null, listOf(0L to stroke("pm1"))),
            ),
            now = 5L, newId = { "id${n++}" },
        )!!
        assertEquals(listOf("am1", "am2"), InkIngest.strokesToPaste(pages).map { it.id })
        val objects = InkClip.envelopeOf(listOf(stroke("a"), stroke("b")), now = 5L)!!
        assertEquals(listOf("a", "b"), InkIngest.strokesToPaste(objects).map { it.id })
    }
}
