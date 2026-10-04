package com.symmetricalpalmtree.soil.backup

import android.os.Build

/**
 * The suggestion for this device's folder under `Backups/`: the model name, cleaned to the narrow
 * charset that is legal everywhere a folder name goes. The person types the name at first use
 * (decision 2026-10-03); this is only what the field starts with. Never the hardware serial.
 */
object DeviceFolder {

    const val FALLBACK = "device"
    const val MAX_MODEL_CHARS = 48

    fun suggestion(model: String?): String = sanitize(model.orEmpty()).take(MAX_MODEL_CHARS).trim('-').ifEmpty { FALLBACK }

    /** Not pure: the one line that reads [Build]. */
    fun suggest(): String = suggestion(runCatching { Build.MODEL }.getOrNull())

    private fun sanitize(raw: String): String {
        val out = StringBuilder(raw.length)
        for (c in raw) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'
            if (ok) out.append(c) else if (out.isNotEmpty() && out.last() != '-') out.append('-')
        }
        return out.toString()
    }
}
