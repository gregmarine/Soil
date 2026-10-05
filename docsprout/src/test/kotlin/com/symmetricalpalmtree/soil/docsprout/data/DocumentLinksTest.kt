package com.symmetricalpalmtree.soil.docsprout.data

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.seam.SeamLinks
import com.symmetricalpalmtree.soil.seam.SeamSql
import com.symmetricalpalmtree.soil.seam.SoilAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLinksTest {

    private val me = "11111111-1111-1111-1111-111111111111"
    private val a = "22222222-2222-2222-2222-222222222222"
    private val b = "33333333-3333-3333-3333-333333333333"
    private val page = "44444444-4444-4444-4444-444444444444"

    @Test
    fun `a document's links into the library are listed once each, in the order they appear`() {
        val markdown = "See [one](soil:$a) and [a page](soil:$b/$page), then [one again](soil:$a).\n\n- [the web](http://example.com)\n"
        assertEquals(listOf(SoilAddress(a), SoilAddress(b, page)), DocumentLinks.targets(markdown, me))
    }

    @Test
    fun `a link to the document itself, and an address that is not one, are not links into the library`() {
        assertTrue(DocumentLinks.targets("[me](soil:$me) [bad](soil:a/b/c) [none](soil:)", me).isEmpty())
        assertTrue(DocumentLinks.targets("no links at all", me).isEmpty())
    }

    @Test
    fun `what is in code or a raw line is not a link`() {
        assertTrue(DocumentLinks.targets("`[x](soil:$a)`\n\n| [y](soil:$a) |\n", me).isEmpty())
    }

    @Test
    fun `the mirror drops the document's links and puts each one, in statements the seam admits`() {
        val statements = DocumentLinks.mirror(me, listOf(SoilAddress(a), SoilAddress(b, page)))
        assertEquals(listOf(SeamLinks.DROP_PAGE, SeamLinks.PUT, SeamLinks.PUT), statements.map { it.sql })
        statements.forEach { SeamSql.checkExec(it.sql); assertEquals(it.args.size, SeamSql.bindCount(it.sql)) }
        assertEquals(listOf<Cell>(Cell.Text("")), statements[0].args)
        assertEquals(listOf(Cell.Text("$me:0"), Cell.Text(""), Cell.Text(a), Cell.Null), statements[1].args)
        assertEquals(listOf(Cell.Text("$me:1"), Cell.Text(""), Cell.Text(b), Cell.Text(page)), statements[2].args)
    }

    @Test
    fun `a document with no links still clears its mirror`() {
        assertEquals(listOf(SeamLinks.DROP_PAGE), DocumentLinks.mirror(me, emptyList()).map { it.sql })
    }

    @Test
    fun `the trail is a bounded stack, newest last`() {
        var trail = emptyList<String>()
        for (i in 0..DocTrail.MAX_ENTRIES) trail = DocTrail.push(trail, "doc-$i")
        assertEquals(DocTrail.MAX_ENTRIES, trail.size)
        assertEquals("doc-1", trail.first())
        val (back, rest) = DocTrail.pop(trail)
        assertEquals("doc-${DocTrail.MAX_ENTRIES}", back)
        assertEquals(DocTrail.MAX_ENTRIES - 1, rest.size)
        assertEquals(null to emptyList<String>(), DocTrail.pop(emptyList()))
    }

    @Test
    fun `a stored trail reads back, and a line that is not an id is dropped`() {
        assertEquals(listOf(a, b), DocTrail.decode(DocTrail.encode(listOf(a, b))))
        assertEquals(listOf(a), DocTrail.decode("$a\nnot an id!\n\n"))
        assertTrue(DocTrail.decode(null).isEmpty())
    }
}
