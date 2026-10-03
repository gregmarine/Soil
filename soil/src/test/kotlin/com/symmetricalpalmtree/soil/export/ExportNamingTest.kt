package com.symmetricalpalmtree.soil.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportNamingTest {
    @Test fun `illegal characters go and the id stands in for nothing`() {
        assertEquals("Mynotes2", ExportNaming.base("My/notes:2?", "id-1"))
        assertEquals("My notes 2", ExportNaming.base(" My notes 2 ", "id-1"))
        assertEquals("id-1", ExportNaming.base("///", "id-1"))
        assertEquals("id-1", ExportNaming.base("..", "id-1"))
    }
    @Test fun `a page stem prefers the title, then the number`() {
        assertEquals("Book - Plans", ExportNaming.pageStem("Book", "id", 3, "Plans"))
        assertEquals("Book - page 3", ExportNaming.pageStem("Book", "id", 3, null))
        assertEquals("Book", ExportNaming.pageStem("Book", "id", 0, "  "))
        assertEquals("Book - " + "t".repeat(80), ExportNaming.pageStem("Book", "id", 1, "t".repeat(100)))
    }
    @Test fun `a file name joins stem and extension`() = assertEquals("Book.pdf", ExportNaming.fileName("Book", "pdf"))
    @Test fun `the spec name is capped`() = assertEquals(200, ExportNaming.specNameOf("x".repeat(300)).length)
}
