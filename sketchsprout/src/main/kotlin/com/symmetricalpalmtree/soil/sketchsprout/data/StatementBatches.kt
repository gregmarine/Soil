package com.symmetricalpalmtree.soil.sketchsprout.data

import com.symmetricalpalmtree.soil.paper.store.Cell
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.SeamLimits

/**
 * Statements cut into batches the seam will carry: at most [SeamLimits.MAX_BATCH_STATEMENTS] to a
 * batch and, by a generous estimate of the encoded size, under [MAX_BATCH_BYTES] — well inside
 * [SeamLimits.MAX_PAYLOAD_BYTES], and over [SeamLimits.MAX_VALUE_BYTES] so a batch always holds at
 * least one statement whatever it carries. Order is kept. Pure.
 *
 * What it is for: Convert writes a whole sketchbook at once, a raster a page, and a long notebook
 * is many times one payload.
 */
object StatementBatches {

    /** A batch's estimated size, at most. */
    const val MAX_BATCH_BYTES: Long = 48L * 1024 * 1024

    /** Each statement's framing in the seam's encoding, rounded up. */
    private const val STATEMENT_OVERHEAD = 16L

    fun split(
        statements: List<Statement>,
        maxStatements: Int = SeamLimits.MAX_BATCH_STATEMENTS,
        maxBytes: Long = MAX_BATCH_BYTES,
    ): List<List<Statement>> {
        val out = ArrayList<List<Statement>>()
        var batch = ArrayList<Statement>()
        var bytes = 0L
        for (st in statements) {
            val size = estimate(st)
            if (batch.isNotEmpty() && (batch.size >= maxStatements || bytes + size > maxBytes)) {
                out += batch
                batch = ArrayList()
                bytes = 0L
            }
            batch += st
            bytes += size
        }
        if (batch.isNotEmpty()) out += batch
        return out
    }

    /** Over, never under: text at three bytes a character, every number at nine. */
    fun estimate(st: Statement): Long {
        var n = STATEMENT_OVERHEAD + st.sql.length * 3L
        for (a in st.args) n += when (a) {
            is Cell.Blob -> 5L + a.value.size
            is Cell.Text -> 5L + a.value.length * 3L
            else -> 9L
        }
        return n
    }
}
