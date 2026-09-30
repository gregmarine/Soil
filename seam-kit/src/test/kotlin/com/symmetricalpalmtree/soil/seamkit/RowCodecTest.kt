package com.symmetricalpalmtree.soil.seamkit

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.SeamLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RowCodecTest {

    private val everyCell = listOf(
        Cell.Null, Cell.Integer(Long.MIN_VALUE), Cell.Real(-0.5), Cell.Text("héllo 🌱"), Cell.Blob(byteArrayOf(0, 1, -1)),
    )

    @Test
    fun `statements survive the crossing`() {
        val sent = listOf(
            Statement("INSERT INTO a VALUES (?, ?, ?, ?, ?)", everyCell),
            Statement("DELETE FROM a"),
        )
        val back = RowCodec.decodeStatements(RowCodec.encodeStatements(sent))
        assertEquals(2, back.size)
        assertEquals(sent[0].sql, back[0].sql)
        assertEquals(everyCell, back[0].args)
        assertEquals("DELETE FROM a", back[1].sql)
        assertTrue(back[1].args.isEmpty())
    }

    @Test
    fun `rows survive the crossing`() {
        val bytes = RowCodec.encodeRows(listOf("a", "b", "c", "d", "e"), listOf(everyCell, everyCell))
        val back = RowCodec.decodeRows(bytes)
        assertEquals(listOf("a", "b", "c", "d", "e"), back.columns)
        assertEquals(2, back.size)
        assertEquals(everyCell, back.cells[1])
        assertArrayEquals(byteArrayOf(0, 1, -1), back[0].blob("e"))
    }

    @Test
    fun `an empty result is a document of no rows`() {
        val back = RowCodec.decodeRows(RowCodec.encodeRows(listOf("id"), emptyList()))
        assertTrue(back.isEmpty())
        assertEquals(listOf("id"), back.columns)
    }

    @Test
    fun `the sizes are the bytes written`() {
        val columns = listOf("a", "bé", "c", "d", "e")
        val bytes = RowCodec.encodeRows(columns, listOf(everyCell))
        assertEquals(bytes.size, RowCodec.rowsHeaderBytes(columns) + RowCodec.rowBytes(everyCell))
    }

    @Test
    fun `unreadable is never empty`() {
        val good = RowCodec.encodeRows(listOf("id"), listOf(listOf(Cell.Text("x"))))
        val refused = { bytes: ByteArray ->
            assertThrows(IllegalArgumentException::class.java) { RowCodec.decodeRows(bytes) }
        }
        refused(ByteArray(0))
        refused(good.copyOf(good.size - 1))
        refused(good + 0)
        refused(good.copyOf().also { it[0] = 'X'.code.toByte() })
        refused(good.copyOf().also { it[4] = 9 })
        refused(good.copyOf().also { it[good.size - 6] = 77 }) // the cell's tag
        refused(RowCodec.encodeStatements(listOf(Statement("SELECT 1"))))
    }

    @Test
    fun `a length past the end is refused before it is allocated`() {
        val good = RowCodec.encodeRows(listOf("id"), listOf(listOf(Cell.Blob(ByteArray(4)))))
        val lying = good.copyOf()
        // The blob's u32 length sits just before its four bytes.
        lying[good.size - 8] = 0x7F
        assertThrows(IllegalArgumentException::class.java) { RowCodec.decodeRows(lying) }
    }

    @Test
    fun `a batch is counted`() {
        assertThrows(IllegalArgumentException::class.java) { RowCodec.encodeStatements(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            RowCodec.encodeStatements(List(SeamLimits.MAX_BATCH_STATEMENTS + 1) { Statement("DELETE FROM a") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            RowCodec.encodeStatements(listOf(Statement("SELECT 1", List(SeamLimits.MAX_ARGS + 1) { Cell.Null })))
        }
    }

    @Test
    fun `a value over the cap is stopped on its way in`() {
        val tooLarge = Cell.Blob(ByteArray(SeamLimits.MAX_VALUE_BYTES + 1))
        val e = assertThrows(IllegalArgumentException::class.java) {
            RowCodec.encodeStatements(listOf(Statement("INSERT INTO a VALUES (?)", listOf(tooLarge))))
        }
        assertEquals(SeamLimits.VALUE_TOO_LARGE, e.message)
        RowCodec.requireStorable(Cell.Blob(ByteArray(SeamLimits.MAX_VALUE_BYTES)))
    }

    @Test
    fun `a result too large to carry stops at the row that crosses`() {
        val columns = listOf("b")
        val row = listOf<Cell>(Cell.Blob(ByteArray(100)))
        val cap = RowCodec.rowsHeaderBytes(columns) + 2 * RowCodec.rowBytes(row)
        val builder = RowsBuilder(columns, cap)
        builder.add(row)
        builder.add(row)
        val e = assertThrows(IllegalStateException::class.java) { builder.add(row) }
        assertEquals(SeamLimits.RESULT_TOO_LARGE, e.message)
    }

    @Test
    fun `utf8 lengths match the encoder`() {
        for (s in listOf("", "abc", "é", "日本語", "🌱", "a🌱é")) {
            assertEquals(s, s.toByteArray(Charsets.UTF_8).size, RowCodec.utf8Length(s))
        }
    }
}
