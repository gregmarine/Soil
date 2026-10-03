package com.symmetricalpalmtree.soil.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A saved way of exporting: which exporter and which option values. Stored as JSON in the index. */
@Serializable
data class ExportPreset(val version: Int = VERSION, val exporter: String, val values: Map<String, String> = emptyMap()) {
    companion object {
        const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(preset: ExportPreset): String = json.encodeToString(serializer(), preset)

        fun decode(text: String?): ExportPreset? {
            if (text.isNullOrEmpty()) return null
            val preset = try { json.decodeFromString(serializer(), text) } catch (_: Exception) { return null }
            if (preset.version !in 1..VERSION || preset.exporter.isBlank()) return null
            return preset
        }
    }
}
