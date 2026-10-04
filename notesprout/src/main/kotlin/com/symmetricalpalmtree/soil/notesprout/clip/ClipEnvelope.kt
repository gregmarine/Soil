package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.soil.notesprout.data.NotebookRow
import com.symmetricalpalmtree.soil.seam.SeamLimits
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * One notebook row, neutral, as the clipboard carries it: the universal row shape with the blob
 * as **Base64**. No clock travels: a paste is a new row and stamps its own, and only live rows
 * are ever captured. `java.util.Base64`, never `android.util.Base64`, which is a stub under the
 * JVM tests.
 */
@Serializable
data class ClipRow(
    val id: String,
    val parentId: String,
    val type: String,
    val order: Int = 0,
    val text: String? = null,
    val refId: String? = null,
    val x: Float? = null,
    val y: Float? = null,
    val width: Float? = null,
    val height: Float? = null,
    val color: String? = null,
    val strokeWidth: Float? = null,
    val style: String? = null,
    val flags: Long? = null,
    val blob: String? = null,
) {
    /** The decoded blob, or null when there is none or it is unusable: one row's bytes, never the paste. */
    fun blobBytes(): ByteArray? = blob?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    /** The row as a paste writes it: fresh identity and lineage, the content verbatim. */
    fun toRow(id: String, parentId: String, order: Int): NotebookRow = NotebookRow(
        id = id, parentId = parentId, type = type, order = order,
        text = text, refId = refId, x = x, y = y, width = width, height = height,
        color = color, strokeWidth = strokeWidth, style = style, flags = flags, blob = blobBytes(),
    )

    companion object {
        fun encodeBlob(bytes: ByteArray?): String? = bytes?.let { Base64.getEncoder().encodeToString(it) }

        fun of(r: NotebookRow): ClipRow = ClipRow(
            id = r.id, parentId = r.parentId, type = r.type, order = r.order,
            text = r.text, refId = r.refId, x = r.x, y = r.y, width = r.width, height = r.height,
            color = r.color, strokeWidth = r.strokeWidth, style = r.style, flags = r.flags, blob = encodeBlob(r.blob),
        )
    }
}

/**
 * What the clipboard holds: a set of rows and the provenance a paste needs, as JSON, kept by Soil
 * under the notebook kind. Kind-discriminated and neutral: a page payload owns a whole
 * self-contained row set; an objects payload lands among rows that are already there. [decode]
 * never throws: an unusable payload reads as no clipboard at all. The byte cap is enforced on
 * write and read, so a payload that grew past it is refused whole rather than half-applied.
 */
@Serializable
data class ClipEnvelope(
    val version: Int,
    val kind: String,
    val sourceNotebookId: String,
    val copiedAt: Long,
    val rows: List<ClipRow>,
) {
    companion object {
        const val VERSION = 1

        /** A whole page and everything on it. */
        const val KIND_PAGE = "page"

        /** What a lasso caught, with a link's wrapped children and a note's content. One slot, kind wins. */
        const val KIND_OBJECTS = "objects"

        /** The seam's value cap: a blob larger than the cursor window could be written and never read back. */
        const val MAX_BYTES = SeamLimits.MAX_VALUE_BYTES

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Encoded UTF-8 JSON, or null when it does not fit [MAX_BYTES]. */
        fun encode(env: ClipEnvelope): ByteArray? {
            val bytes = try {
                json.encodeToString(serializer(), env).toByteArray(Charsets.UTF_8)
            } catch (_: Exception) {
                return null
            }
            return bytes.takeIf { it.size <= MAX_BYTES }
        }

        /** The envelope in [bytes], or null for anything unusable: absent, over-cap, malformed, empty, or newer. */
        fun decode(bytes: ByteArray?): ClipEnvelope? {
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val env = try {
                json.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
            } catch (_: Exception) {
                return null
            }
            if (env.version !in 1..VERSION) return null
            if (env.kind.isEmpty() || env.rows.isEmpty()) return null
            return env
        }
    }
}
