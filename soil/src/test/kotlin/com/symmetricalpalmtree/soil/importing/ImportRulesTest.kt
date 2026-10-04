package com.symmetricalpalmtree.soil.importing

import com.symmetricalpalmtree.soil.export.ExportStamp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportRulesTest {

    @Test fun `names are cleaned, capped and fallen back`() {
        assertEquals("A B", ImportNames.clean(" A\u0007B "))
        assertEquals(120, ImportNames.clean("x".repeat(200)).length)
        assertEquals("Notes", ImportNames.fromDisplayName("dir/Notes.soil"))
        assertEquals(ImportNames.FALLBACK, ImportNames.fromDisplayName(".soil"))
        assertEquals("Meta", ImportNames.itemName("Meta", "file.soil"))
        assertEquals("file", ImportNames.itemName("  ", "file.soil"))
        assertEquals("Imported", ImportNames.folderName(null))
        assertEquals("a b", ImportNames.specDisplayName("x/a b", 10))
    }

    @Test fun `keep-both names count up past the taken`() {
        val taken = setOf("N Copy", "N Copy 2")
        assertEquals("N Copy 3", ImportNames.keepBothName("N", { it in taken }))
        assertEquals("N Copy", ImportNames.keepBothName("N", { false }))
        assertEquals(120, ImportNames.keepBothName("x".repeat(130), { false }).length)
    }

    @Test fun `only canonical ids are safe`() {
        assertTrue(SafeImportId.isSafe("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(SafeImportId.isSafe("../x"))
        assertNull(SafeImportId.orNull(""))
    }

    @Test fun `importers match by extension and the filter takes everything`() {
        val declared = listOf(listOf("soil"), listOf("md", "txt"))
        assertEquals(listOf(0), ImporterMatch.matching(declared, "Book.SOIL"))
        assertEquals(listOf(1), ImporterMatch.matching(declared, "a/b/c.txt"))
        assertTrue(ImporterMatch.matching(declared, "noext").isEmpty())
        assertTrue(ImporterMatch.matching(declared, "dot.").isEmpty())
        assertArrayEquals(arrayOf("application/octet-stream", "*/*"), ImporterMatch.mimeFilter(listOf(listOf("application/octet-stream"))))
    }

    @Test fun `an ancestry is reused, created and stopped at a block`() {
        val a = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"; val b = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"; val c = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        val path = listOf(ExportStamp.Folder(a, "A"), ExportStamp.Folder(b, "B"), ExportStamp.Folder(c, "C"))
        val plan = AncestryPlan.plan(path) { id -> when (id) { a -> AncestryPlan.Slot.LIVE_FOLDER; b -> AncestryPlan.Slot.MISSING; else -> AncestryPlan.Slot.BLOCKED } }
        assertEquals(b, plan.parentId)
        assertEquals(listOf(AncestryPlan.Create(b, "B", a)), plan.create)
        assertTrue(plan.truncated)
        val root = AncestryPlan.plan(listOf(ExportStamp.Folder("bad", "X")), { AncestryPlan.Slot.MISSING })
        assertEquals("", root.parentId); assertTrue(root.truncated)
        val all = AncestryPlan.plan(path) { AncestryPlan.Slot.MISSING }
        assertEquals(c, all.parentId); assertEquals(3, all.create.size); assertFalse(all.truncated)
    }

    @Test fun `a folder path round-trips through the stamp`() {
        val path = listOf(ExportStamp.Folder("1", "One"), ExportStamp.Folder("2", "Two"))
        assertEquals(path, ExportStamp.decodePath(ExportStamp.encodePath(path)))
        assertTrue(ExportStamp.decodePath(null).isEmpty())
        assertTrue(ExportStamp.decodePath("junk").isEmpty())
    }
}
