package com.symmetricalpalmtree.soil.data

import com.symmetricalpalmtree.soil.data.index.TagStore
import com.symmetricalpalmtree.soil.seam.TagRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tag index over the statement-applying fake: the reads, and the exact shape of every edit. */
class TagStoreTest {

    private val n1 = "11111111-1111-4111-8111-111111111111"
    private val n2 = "22222222-2222-4222-8222-222222222222"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"
    private val t1 = "cccccccc-1111-4111-8111-111111111111"
    private val t2 = "dddddddd-2222-4222-8222-222222222222"

    @Test
    fun `tags read in browse order and a bad row is dropped`() {
        val fake = FakeIndexRows()
        fake.tag(t1, "Zebra")
        fake.tag("not-a-uuid", "broken")
        fake.tag(t2, "Apple")
        assertEquals(listOf("Apple", "Zebra"), TagStore(fake).tags().map { it.display })
    }

    @Test
    fun `the assignments of one item are its own and its pages'`() {
        val fake = FakeIndexRows()
        fake.tag(t1, "draft")
        fake.assign(t1, n1); fake.assign(t1, n1, p1); fake.assign(t1, n2)
        val rows = TagStore(fake).assignmentsOfItem(n1)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.itemId == n1 })
        assertEquals(listOf(null, p1), rows.map { it.pageIdOrNull })
    }

    @Test
    fun `the assignments of no tags asks nothing`() {
        val fake = FakeIndexRows()
        assertTrue(TagStore(fake).assignmentsOf(emptyList()).isEmpty())
        assertTrue(fake.queries.isEmpty())
    }

    @Test
    fun `usage decodes both counts and a null sum as zero`() {
        val fake = FakeIndexRows()
        fake.tag(t1, "draft")
        fake.assign(t1, n1); fake.assign(t1, n2); fake.assign(t1, n1, p1)
        val used = TagStore(fake).usageOf(t1)
        assertEquals(2, used.items); assertEquals(1, used.pages); assertEquals(3, used.total)
        val unused = TagStore(fake).usageOf(t2)
        assertEquals(0, unused.total)
    }

    @Test
    fun `a new tag is created and attached in one batch, with the stored spelling answered`() {
        val fake = FakeIndexRows()
        val out = TagStore(fake).assign("  Reading   List ", n1, null)
        assertEquals("Reading List", out.display)
        assertTrue(out.changed)
        assertEquals(1, fake.execs.size)
        assertEquals(2, fake.execs[0].size)
        assertTrue(fake.execs[0][0].sql.startsWith("INSERT OR IGNORE INTO tag ("))
        assertTrue(fake.execs[0][1].sql.startsWith("INSERT OR IGNORE INTO tag_assignment"))
        assertEquals(1, fake.assignments.size)
    }

    @Test
    fun `an existing identity is attached under its first casing, and a repeat writes nothing`() {
        val fake = FakeIndexRows()
        fake.tag(t1, "Reading List")
        val store = TagStore(fake)
        val first = store.assign("reading list", n1, p1)
        assertEquals("Reading List", first.display)
        assertTrue(first.changed)
        assertEquals(1, fake.execs.single().size)   // the assignment only: no second tag row
        val again = store.assign("READING LIST", n1, p1)
        assertFalse(again.changed)
        assertEquals(1, fake.execs.size)
        assertEquals(1, fake.tags.size)
    }

    @Test
    fun `a cap refuses inside the insert and nothing is left behind`() {
        val fake = FakeIndexRows()
        fake.tag(t1, "one")
        val store = TagStore(fake, maxTags = 1, maxAssignments = 50_000)
        val e = assertThrows(IllegalStateException::class.java) { store.assign("two", n1, null) }
        assertEquals(TagRules.TAGS_FULL, e.message)
        assertEquals(1, fake.tags.size)
        assertTrue(fake.assignments.isEmpty())
        val full = TagStore(fake, maxTags = 5_000, maxAssignments = 0)
        assertThrows(IllegalStateException::class.java) { full.assign("three", n1, null) }
        assertEquals(1, fake.tags.size)   // the tag was not created for an attachment the cap refused
    }

    @Test
    fun `invalid input is refused before anything is read`() {
        val fake = FakeIndexRows()
        val store = TagStore(fake)
        assertThrows(IllegalArgumentException::class.java) { store.assign("   ", n1, null) }
        assertThrows(IllegalArgumentException::class.java) { store.assign("ok", "nb-1", null) }
        assertThrows(IllegalArgumentException::class.java) { store.assign("ok", n1, "p") }
        assertTrue(fake.queries.isEmpty())
    }

    @Test
    fun `unassign leaves the tag and delete takes every assignment with it`() {
        val fake = FakeIndexRows()
        fake.tag(t1, "draft")
        fake.assign(t1, n1); fake.assign(t1, n2, p1)
        val store = TagStore(fake)
        assertTrue(store.unassign(t1, n1, null))
        assertFalse(store.unassign(t1, n1, null))
        assertEquals(1, fake.tags.size)
        assertTrue(store.deleteTag(t1))
        assertTrue(fake.tags.isEmpty())
        assertTrue(fake.assignments.isEmpty())
        assertFalse(store.deleteTag(t1))
    }
}
