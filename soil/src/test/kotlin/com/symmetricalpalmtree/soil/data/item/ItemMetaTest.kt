package com.symmetricalpalmtree.soil.data.item

import com.symmetricalpalmtree.soil.data.item.ItemMeta.Verdict
import com.symmetricalpalmtree.soil.seam.SeamNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemMetaTest {

    private val rows = ItemMeta.rows("id-1", "notebook", "Journal", 1_000L)

    @Test
    fun `a new file says what it is`() {
        assertEquals("1", rows[ItemMeta.KEY_FORMAT])
        assertEquals("id-1", rows[ItemMeta.KEY_ID])
        assertEquals("notebook", rows[ItemMeta.KEY_KIND])
        assertEquals("Journal", rows[ItemMeta.KEY_NAME])
        assertEquals("1000", rows[ItemMeta.KEY_CREATED_AT])
    }

    @Test
    fun `the file of the item opens`() {
        assertEquals(Verdict.OK, ItemMeta.verdict(rows, "id-1", "notebook"))
    }

    @Test
    fun `a file of another kind or another item does not`() {
        assertEquals(Verdict.NOT_THIS_ITEM, ItemMeta.verdict(rows, "id-1", "sketchbook"))
        assertEquals(Verdict.NOT_THIS_ITEM, ItemMeta.verdict(rows, "id-2", "notebook"))
        assertEquals(Verdict.NOT_THIS_ITEM, ItemMeta.verdict(rows - ItemMeta.KEY_KIND, "id-1", "notebook"))
    }

    @Test
    fun `a file that says nothing of itself does not`() {
        assertEquals(Verdict.NO_META, ItemMeta.verdict(null, "id-1", "notebook"))
        assertEquals(Verdict.NO_META, ItemMeta.verdict(emptyMap(), "id-1", "notebook"))
        assertEquals(Verdict.NO_META, ItemMeta.verdict(rows + (ItemMeta.KEY_FORMAT to "x"), "id-1", "notebook"))
    }

    @Test
    fun `a file from a later Soil does not`() {
        assertEquals(Verdict.NEWER, ItemMeta.verdict(rows + (ItemMeta.KEY_FORMAT to "2"), "id-1", "notebook"))
    }

    @Test
    fun `an app cannot name the table`() {
        assertTrue(SeamNames.isReserved(ItemMeta.TABLE))
    }

    @Test
    fun `a name is kept on one line`() {
        assertEquals("My journal", ItemNames.clean("  My\n journal \t"))
        assertEquals("../etc", ItemNames.clean("../etc"))
        assertThrows(IllegalArgumentException::class.java) { ItemNames.clean("  \n ") }
        assertThrows(IllegalArgumentException::class.java) { ItemNames.clean("a".repeat(201)) }
        assertEquals(200, ItemNames.clean("a".repeat(200)).length)
    }
}
