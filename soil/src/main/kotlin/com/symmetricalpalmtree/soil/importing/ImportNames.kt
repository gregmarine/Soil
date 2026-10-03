package com.symmetricalpalmtree.soil.importing

/** The names an import lands under: the file's own, cleaned, else the document's, else a fallback. */
object ImportNames {

    const val MAX_NAME_CHARS = 120
    const val FALLBACK = "Imported item"
    private const val MAX_TRIES = 50

    fun clean(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        val flattened = buildString(raw.length) { for (ch in raw) append(if (ch.isISOControl()) ' ' else ch) }
        return flattened.trim().take(MAX_NAME_CHARS).trim()
    }

    fun fromDisplayName(displayName: String): String {
        val stem = displayName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        return clean(stem).ifEmpty { FALLBACK }
    }

    fun itemName(metaName: String?, displayName: String): String = clean(metaName).ifEmpty { fromDisplayName(displayName) }

    fun folderName(metaName: String?): String = clean(metaName).ifEmpty { "Imported" }

    fun specDisplayName(displayName: String, max: Int): String {
        val leaf = displayName.substringAfterLast('/').substringAfterLast('\\')
        return clean(leaf).filter { it != '/' }.take(max).trim()
    }

    /** `X Copy`, `X Copy 2`, … the first not taken. */
    fun keepBothName(name: String, isTaken: (String) -> Boolean): String {
        val base = clean(name).ifEmpty { FALLBACK }
        var candidate = withSuffix(base, " Copy")
        var n = 2
        while (isTaken(candidate) && n <= MAX_TRIES) {
            candidate = withSuffix(base, " Copy $n")
            n++
        }
        return candidate
    }

    private fun withSuffix(base: String, suffix: String): String {
        val room = MAX_NAME_CHARS - suffix.length
        if (room <= 0) return suffix.trim()
        return (if (base.length <= room) base else base.take(room).trimEnd()) + suffix
    }
}
