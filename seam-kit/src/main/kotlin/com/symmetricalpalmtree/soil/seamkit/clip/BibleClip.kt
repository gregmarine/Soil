package com.symmetricalpalmtree.soil.seamkit.clip

import com.symmetricalpalmtree.soil.seam.BibleAddress
import com.symmetricalpalmtree.soil.seam.SeamLimits
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **A passage on the clipboard**: what the Bible's reader copies and a notebook or a document
 * pastes, in the `bible` kind's slot ([SLOT]) in Soil. The wire, its canonical label, and the
 * verses as the reader's Markdown, so a paste may put in the reference alone or the words too
 * without asking the reader again. Shared so the three apps read one shape.
 *
 * [decode] never throws: an unusable payload reads as no clipboard at all.
 */
@Serializable
data class BibleClip(
    val version: Int,
    val wire: String,
    val label: String,
    val text: String,
    val copiedAt: Long,
) {
    companion object {
        const val VERSION = 1

        /** The clipboard slot: a kind of its own, beside the notebook kind's. */
        const val SLOT = "bible"

        /** The header's payload kind. */
        const val PAYLOAD_KIND = "passage"

        const val MAX_LABEL_CHARS = 200

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Encoded UTF-8 JSON, or null for a clip [decode] would refuse (a wire that is not one,
         *  a label that is blank or too long) or one over the seam's value cap: no caller can
         *  write a clip that cannot be pasted. */
        fun encode(clip: BibleClip): ByteArray? {
            if (!BibleAddress.isWire(clip.wire)) return null
            if (clip.label.isBlank() || clip.label.length > MAX_LABEL_CHARS) return null
            val bytes = json.encodeToString(serializer(), clip).toByteArray(Charsets.UTF_8)
            return bytes.takeIf { it.size <= SeamLimits.MAX_VALUE_BYTES }
        }

        /** The clip, or null for bytes this build cannot read: a wire that is not one, a label
         *  that is blank or too long, a later version. */
        fun decode(bytes: ByteArray?): BibleClip? {
            if (bytes == null || bytes.isEmpty() || bytes.size > SeamLimits.MAX_VALUE_BYTES) return null
            val clip = runCatching { json.decodeFromString(serializer(), String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
            if (clip.version != VERSION || !BibleAddress.isWire(clip.wire)) return null
            if (clip.label.isBlank() || clip.label.length > MAX_LABEL_CHARS) return null
            return clip
        }
    }
}
