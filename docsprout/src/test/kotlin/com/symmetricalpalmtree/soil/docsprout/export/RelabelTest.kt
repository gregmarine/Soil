package com.symmetricalpalmtree.soil.docsprout.export

import com.symmetricalpalmtree.soil.seam.SeamSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RelabelTest {

    @Test
    fun `the root and what it parents take the new id, in statements the seam will run`() {
        val statements = Relabel.statements("old-1", "new-2")
        assertEquals(2, statements.size)
        statements.forEach { SeamSql.checkExec(it); assertEquals(0, SeamSql.bindCount(it)) }
        assertTrue(statements[0].contains("SET id = 'new-2' WHERE id = 'old-1'"))
        assertTrue(statements[1].contains("SET parentId = 'new-2' WHERE parentId = 'old-1'"))
    }

    @Test
    fun `an id that is not plain is refused`() {
        assertThrows(IllegalArgumentException::class.java) { Relabel.statements("a'b", "c") }
        assertThrows(IllegalArgumentException::class.java) { Relabel.statements("a", "a") }
        assertThrows(IllegalArgumentException::class.java) { Relabel.statements("", "a") }
    }
}
