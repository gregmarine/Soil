package com.symmetricalpalmtree.soil.biblesprout

import com.symmetricalpalmtree.soil.bibleref.*
import com.symmetricalpalmtree.soil.seam.SeamBibleBacklink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Notes panel's arithmetic: the scope the reader is standing in, and the grouping that turns
 *  the index's rows into the places that cite it. */
class NotesModelTest {

    private val nbA = "11111111-1111-4111-8111-111111111111"
    private val docB = "22222222-2222-4222-8222-222222222222"
    private val p1 = "33333333-3333-4333-8333-333333333333"
    private val p2 = "44444444-4444-4444-8444-444444444444"

    private fun row(
        linkId: String, wire: String, startKey: Int, endKey: Int,
        itemId: String = nbA, pageId: String = p1, kind: String = "notebook", name: String = "Study", pageNumber: Int = 4,
    ) = SeamBibleBacklink(linkId, itemId, kind, name, pageId, pageNumber, wire, startKey, endKey)

    private fun k(o: Int, c: Int, v: Int) = VerseKey.encode(o, c, v)

    @Test
    fun `a chapter scopes its whole verse band`() {
        assertEquals(listOf(VerseRange(k(43, 3, 0), k(43, 3, VerseKey.MAX_VERSE))), NotesModel.scope(ChapterRef("JHN", 3), null))
    }

    @Test
    fun `a passage scopes every range of every passage, and wins over the chapter`() {
        val passages = ReferenceCodec.decode("JHN:3:14-3:18,PRO:3:5-3:6")!!
        assertEquals(listOf(VerseRange(k(43, 3, 14), k(43, 3, 18)), VerseRange(k(20, 3, 5), k(20, 3, 6))), NotesModel.scope(ChapterRef("GEN", 1), passages))
    }

    @Test
    fun `nothing open is an empty scope`() {
        assertTrue(NotesModel.scope(null, null).isEmpty())
        assertTrue(NotesModel.scope(ChapterRef("XYZ", 1), null).isEmpty())
    }

    @Test
    fun `the same row read by two ranges is one note, and a multi-range link labels itself once`() {
        val wire = "JHN:3:14-3:18,JHN:3:20-3:21"
        val rows = listOf(
            row("a", wire, k(43, 3, 14), k(43, 3, 18)), row("a", wire, k(43, 3, 14), k(43, 3, 18)),
            row("a#1", wire, k(43, 3, 20), k(43, 3, 21)),
        )
        val groups = NotesModel.group(rows)
        assertEquals(1, groups.size)
        assertEquals(listOf("John 3:14–18, 20–21"), groups[0].labels)
        assertEquals(k(43, 3, 14), groups[0].firstStartKey)
    }

    @Test
    fun `one page's references read as one entry, in reading order`() {
        val groups = NotesModel.group(listOf(row("b", "JHN:3:16-3:16", k(43, 3, 16), k(43, 3, 16)), row("a", "JHN:3:14-3:14", k(43, 3, 14), k(43, 3, 14))))
        assertEquals(1, groups.size)
        assertEquals(listOf("John 3:14", "John 3:16"), groups[0].labels)
        assertEquals("Study", groups[0].name)
        assertEquals(4, groups[0].pageNumber)
    }

    @Test
    fun `a page, another page and a document are three entries, sorted by where they point`() {
        val groups = NotesModel.group(
            listOf(
                row("a", "JHN:3:16-3:16", k(43, 3, 16), k(43, 3, 16), pageId = p1),
                row("b", "JHN:3:16-3:16", k(43, 3, 16), k(43, 3, 16), pageId = p2, pageNumber = 9),
                row("c", "JHN:3:2-3:2", k(43, 3, 2), k(43, 3, 2), itemId = docB, pageId = "", kind = "document", name = "Sermon", pageNumber = 0),
            ),
        )
        assertEquals(listOf("Sermon", "Study", "Study"), groups.map { it.name })
        assertEquals(listOf("", p1, p2), groups.map { it.pageId })
    }

    @Test
    fun `a row whose wire this build cannot read is dropped whole`() {
        assertTrue(NotesModel.group(listOf(row("a", "nonsense", 1, 2))).isEmpty())
    }

    @Test
    fun `the two lines`() {
        val page = NotesModel.NoteGroup(nbA, p1, "notebook", "Study", 4, listOf("John 3:14–18", "Proverbs 3:5–6"), 1)
        val doc = NotesModel.NoteGroup(docB, "", "document", "Sermon", 0, listOf("John 3:16"), 1)
        assertEquals("Study · Page 4", NotesModel.title(page, "Page", "Document"))
        assertEquals("Sermon · Document", NotesModel.title(doc, "Page", "Document"))
        assertEquals("John 3:14–18; Proverbs 3:5–6", NotesModel.detail(page))
    }

    @Test
    fun `the sidebar is sixty per cent of the window`() {
        assertEquals(449, NotesModel.sidebarWidthPx(749))
    }
}
