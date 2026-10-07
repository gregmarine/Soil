package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRenderModeTest {

    @Test
    fun `a request needs a kind and a key, and names itself by the key when no name came`() {
        assertNull(ExportRenderMode.requestOf(null, "M:2026-09-01", "Calendar - September 2026"))
        assertNull(ExportRenderMode.requestOf("calendar", "", "x"))
        val named = ExportRenderMode.requestOf("calendar", "M:2026-09-01", "Calendar - September 2026")!!
        assertEquals("calendar", named.kind)
        assertEquals("M:2026-09-01", named.key)
        assertEquals("Calendar - September 2026", named.name)
        assertEquals("M:2026-09-01", ExportRenderMode.requestOf("calendar", "M:2026-09-01", null)!!.name)
    }

    @Test
    fun `only a pages exporter is listed, and the stem is the name made safe`() {
        assertTrue(ExportRenderMode.lists(ExportContract.SOURCE_PAGES))
        assertFalse(ExportRenderMode.lists(ExportContract.SOURCE_FILE))
        assertEquals("Calendar - September 2026", ExportRenderMode.stem(ExportRenderMode.Request("calendar", "M:2026-09-01", "Calendar - September 2026")))
        assertEquals("Calendar - Week of 2026-09-06", ExportRenderMode.stem(ExportRenderMode.Request("calendar", "W:2026-09-06", "Calendar - Week of 2026-09-06")))
        assertEquals("D:2026-09-08", ExportRenderMode.stem(ExportRenderMode.Request("calendar", "D:2026-09-08", "///")))
        assertEquals("Calendar - 2026-09-08 - AM", ExportNaming.pageStem("Calendar - 2026-09-08", "D:2026-09-08", 1, "AM"))
    }
}
