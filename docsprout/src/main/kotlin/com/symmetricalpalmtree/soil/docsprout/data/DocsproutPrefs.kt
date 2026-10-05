package com.symmetricalpalmtree.soil.docsprout.data

import android.content.Context

/**
 * What Docsprout remembers on this device: the document last open, the text size, and where the
 * cursor was left in each of the documents last worked on. Device-local: never in a file, never
 * backed up. This is comfort, not content: losing it costs a scroll and a tap.
 *
 * A stored value is untrusted input: a size this build does not offer reads as the default.
 */
class DocsproutPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The document last open, or null. The library's id, never a path. */
    var lastDocumentId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) { prefs.edit().putString(KEY_LAST, value).apply() }

    /** The editor's text size in sp: one of [TextSizes.SIZES]. */
    var textSize: Float
        get() = TextSizes.orDefault(runCatching { prefs.getFloat(KEY_TEXT_SIZE, TextSizes.DEFAULT) }.getOrDefault(TextSizes.DEFAULT))
        set(value) { prefs.edit().putFloat(KEY_TEXT_SIZE, TextSizes.orDefault(value)).apply() }

    /** Where the cursor was left in [documentId], or null. */
    fun caret(documentId: String): Int? = CaretMemory.decode(prefs.getString(KEY_CARETS, null))[documentId]?.caret

    fun rememberCaret(documentId: String, caret: Int, now: Long = System.currentTimeMillis()) {
        val known = CaretMemory.decode(prefs.getString(KEY_CARETS, null))
        prefs.edit().putString(KEY_CARETS, CaretMemory.encode(CaretMemory.put(known, documentId, caret, now))).apply()
    }

    /** Where the writer came from, link by link ([DocTrail]). */
    var trail: List<String>
        get() = DocTrail.decode(prefs.getString(KEY_TRAIL, null))
        set(value) { prefs.edit().putString(KEY_TRAIL, DocTrail.encode(value)).apply() }

    /**
     * The document a link (or the way back along the trail) is about to open. A document that
     * opens and is not this one was opened afresh, and that is a new story: the trail is cleared.
     */
    var arriving: String?
        get() = prefs.getString(KEY_ARRIVING, null)
        set(value) { prefs.edit().putString(KEY_ARRIVING, value).apply() }

    private companion object {
        const val FILE = "docsprout"
        const val KEY_TRAIL = "trail"
        const val KEY_ARRIVING = "arriving"
        const val KEY_LAST = "lastDocumentId"
        const val KEY_TEXT_SIZE = "textSize"
        const val KEY_CARETS = "carets"
    }
}

/** The five text sizes, smallest first, in sp. */
object TextSizes {
    const val DEFAULT = 16f
    val SIZES: List<Float> = listOf(14f, 16f, 18f, 21f, 25f)

    fun orDefault(sp: Float): Float = if (sp in SIZES) sp else DEFAULT
}

/**
 * The cursor positions of the documents last worked on, as one stored line: at most [MAX]
 * documents, the ones longest untouched dropped first. Pure.
 */
object CaretMemory {
    const val MAX = 100

    data class Entry(val caret: Int, val at: Long)

    fun put(known: Map<String, Entry>, documentId: String, caret: Int, now: Long): Map<String, Entry> {
        val next = LinkedHashMap(known)
        next[documentId] = Entry(caret.coerceAtLeast(0), now)
        if (next.size <= MAX) return next
        return next.entries.sortedByDescending { it.value.at }.take(MAX).associate { it.key to it.value }
    }

    /** `id caret at`, one document a line. Ids are UUIDs: no spaces, no line breaks. */
    fun encode(known: Map<String, Entry>): String = known.entries.joinToString("\n") { "${it.key} ${it.value.caret} ${it.value.at}" }

    /** A line that does not read is dropped, and the rest still read. */
    fun decode(stored: String?): Map<String, Entry> {
        if (stored.isNullOrEmpty()) return emptyMap()
        val known = LinkedHashMap<String, Entry>()
        for (line in stored.split('\n')) {
            val parts = line.split(' ')
            if (parts.size != 3 || parts[0].isEmpty()) continue
            val caret = parts[1].toIntOrNull() ?: continue
            val at = parts[2].toLongOrNull() ?: continue
            if (caret < 0) continue
            known[parts[0]] = Entry(caret, at)
        }
        return known
    }
}
