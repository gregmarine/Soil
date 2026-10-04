package com.symmetricalpalmtree.soil.data.index

import com.symmetricalpalmtree.soil.data.store.SqlCipherRowStore
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.SeamClip
import com.symmetricalpalmtree.soil.seam.SeamLimits

/**
 * The clipboard rows of the index: one slot per kind of item, sticky, replaced by the next copy
 * and cleared only on an app's say. The bytes are the app's own and are never read here; the
 * header is answered blob-free. **Blocking**: IO only, while [SoilIndex.isReady].
 *
 * A payload over [SeamLimits.MAX_VALUE_BYTES] is refused on the way in: a blob larger than the
 * cursor window it is read back through could be written and then never read.
 */
class ClipStore(private val rows: SqlCipherRowStore = SqlCipherRowStore(SoilIndex.db())) {

    fun header(kind: String): SeamClip? =
        rows.query(Statement("SELECT payloadKind, sourceItemId, copiedAt FROM clipboard WHERE kind = ?", kind)).rows.firstOrNull()?.let {
            runCatching { SeamClip(it.text("payloadKind"), it.text("sourceItemId"), it.long("copiedAt")) }.getOrNull()
        }

    fun put(kind: String, header: SeamClip, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= SeamLimits.MAX_VALUE_BYTES) { SeamLimits.VALUE_TOO_LARGE }
        rows.exec(
            listOf(
                Statement(
                    "INSERT OR REPLACE INTO clipboard (kind, payloadKind, sourceItemId, copiedAt, blob) VALUES (?, ?, ?, ?, ?)",
                    kind, header.payloadKind, header.sourceItemId, header.copiedAt, bytes,
                ),
            ),
        )
    }

    /** The payload, or null when the slot is empty or its bytes cannot be read back. */
    fun bytes(kind: String): ByteArray? =
        runCatching { rows.query(Statement("SELECT blob FROM clipboard WHERE kind = ?", kind)).rows.firstOrNull()?.blobOrNull("blob") }.getOrNull()

    fun clear(kind: String) {
        rows.exec(listOf(Statement("DELETE FROM clipboard WHERE kind = ?", kind)))
    }
}
