package com.symmetricalpalmtree.soil.notesprout.links

import com.symmetricalpalmtree.soil.notesprout.links.LinkPickerModel.PickMode
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPickerModelTest {

    @Test
    fun `a prefill opens on its shelf with its style, and anything unreadable opens fresh`() {
        assertEquals(PickMode.THIS_NOTEBOOK, LinkPickerModel.modeFor(null))
        assertEquals(PickMode.THIS_NOTEBOOK, LinkPickerModel.modeFor(LinkPayload.decode("L1|0|0||p")))
        assertEquals(PickMode.NOTEBOOK, LinkPickerModel.modeFor(LinkPayload.decode("L1|1|1|nb|")))
        assertEquals(PickMode.NOTEBOOK_PAGE, LinkPickerModel.modeFor(LinkPayload.decode("L1|1|2|nb|p")))
        assertEquals(LinkPayload.CHROME_UNDERLINE, LinkPickerModel.chromeFor(null))
        assertEquals(LinkPayload.CHROME_NONE, LinkPickerModel.chromeFor(LinkPayload.decode("L1|0|0||p")))
    }

    @Test
    fun `page numbers count the whole notebook before the current page is dropped`() {
        val cards = LinkPickerModel.pageCards(listOf("a", "b", "c"), { it }, "b")
        assertEquals(listOf("a" to 1, "c" to 3), cards)
        assertEquals(3, LinkPickerModel.pageCards(listOf("a", "b", "c"), { it }, null).size)
    }

    @Test
    fun `the grid's arithmetic`() {
        assertEquals(1, LinkPickerModel.gridPageOf(7, 6))
        assertEquals(0, LinkPickerModel.gridPageOf(-1, 6))
        assertEquals(0, LinkPickerModel.gridPageOf(3, 0))
        assertEquals(1, LinkPickerModel.pageCount(0, 6))
        assertEquals(2, LinkPickerModel.pageCount(7, 6))
        assertEquals(1, LinkPickerModel.clampPage(5, 2))
        assertEquals(0, LinkPickerModel.clampPage(-3, 2))
        assertEquals(0, LinkPickerModel.clampPage(0, 0))
    }

    @Test
    fun `the create buttons follow the grid on screen`() {
        assertEquals(LinkPickerModel.CreateButtons(newPage = true, newNotebook = false), LinkPickerModel.createButtons(PickMode.THIS_NOTEBOOK, false))
        assertEquals(LinkPickerModel.CreateButtons(newPage = false, newNotebook = true), LinkPickerModel.createButtons(PickMode.NOTEBOOK, false))
        assertEquals(LinkPickerModel.CreateButtons(newPage = false, newNotebook = true), LinkPickerModel.createButtons(PickMode.NOTEBOOK_PAGE, false))
        assertEquals(LinkPickerModel.CreateButtons(newPage = true, newNotebook = false), LinkPickerModel.createButtons(PickMode.NOTEBOOK_PAGE, true))
    }

    @Test
    fun `OK composes a payload only when the choice is whole and is not home`() {
        assertEquals("L1|1|0||p", LinkPickerModel.composeOk(PickMode.THIS_NOTEBOOK, 1, "me", null, "p"))
        assertNull(LinkPickerModel.composeOk(PickMode.THIS_NOTEBOOK, 1, "me", null, null))
        assertEquals("L1|0|1|nb|", LinkPickerModel.composeOk(PickMode.NOTEBOOK, 0, "me", "nb", null))
        assertNull(LinkPickerModel.composeOk(PickMode.NOTEBOOK, 0, "me", "me", null))
        assertEquals("L1|1|2|nb|p", LinkPickerModel.composeOk(PickMode.NOTEBOOK_PAGE, 1, "me", "nb", "p"))
        assertNull(LinkPickerModel.composeOk(PickMode.NOTEBOOK_PAGE, 1, "me", "nb", null))
        assertNull(LinkPickerModel.composeOk(PickMode.NOTEBOOK_PAGE, 1, "me", "a|b", "p"))
    }

    @Test
    fun `the calendar shelf opens on a day prefill, offers no creates, and composes a day link`() {
        assertEquals(LinkPickerModel.PickMode.CAL_DAY, LinkPickerModel.modeFor(LinkPayload.decode("L1|1|5|2026-10-06|")))
        assertEquals(LinkPickerModel.CreateButtons(newPage = false, newNotebook = false), LinkPickerModel.createButtons(LinkPickerModel.PickMode.CAL_DAY, drilled = false))
        assertEquals("L1|0|5|2026-10-06|", LinkPickerModel.composeOk(LinkPickerModel.PickMode.CAL_DAY, 0, "me", null, null, "2026-10-06"))
        assertEquals(null, LinkPickerModel.composeOk(LinkPickerModel.PickMode.CAL_DAY, 0, "me", null, null, null))
        assertEquals(null, LinkPickerModel.composeOk(LinkPickerModel.PickMode.CAL_DAY, 0, "me", null, null, "nonsense"))
    }

    @Test
    fun `a preview keeps the page's aspect, clamped, and never an absurd size`() {
        assertEquals(4f / 3f, PreviewMath.aspect(0, 10))
        assertEquals(PreviewMath.MAX_ASPECT, PreviewMath.aspect(1, 100))
        assertEquals(300 to 400, PreviewMath.renderSize(300, 1404, 1872))
        assertEquals(PreviewMath.MAX_RENDER_EDGE_PX, PreviewMath.renderSize(5000, 1, 1).first)
    }
}
