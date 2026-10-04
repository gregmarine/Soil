package com.symmetricalpalmtree.soil.docsprout.editor

import android.content.Context

/**
 * What Proofread keeps on this device: whether it is on, and the words the writer has vouched
 * for. Device-local (decision 2026-10-04): not in a file of the library, not backed up; BACKLOG.md
 * records moving it into Soil's app store when there is one.
 *
 * A word is stored as the engine normalizes it, oldest first. **Blocking** in the way any
 * preferences file is: called off Main. Nothing here logs: the words are the writer's own.
 */
class ProofreadStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Absent means on. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    fun userWords(): LinkedHashSet<String> = UserWords.decode(prefs.getString(KEY_WORDS, null))

    @Synchronized
    fun addUserWord(word: String) {
        val words = userWords()
        if (words.add(word)) prefs.edit().putString(KEY_WORDS, UserWords.encode(words)).commit()
    }

    @Synchronized
    fun removeUserWord(word: String) {
        val words = userWords()
        if (words.remove(word)) prefs.edit().putString(KEY_WORDS, UserWords.encode(words)).commit()
    }

    private companion object {
        const val FILE = "proofread"
        const val KEY_ENABLED = "enabled"
        const val KEY_WORDS = "words"
    }
}

/** The user dictionary as one stored value: a word a line, in the order they were added. Pure. */
object UserWords {
    fun encode(words: Collection<String>): String = words.filter { it.isNotEmpty() && it.indexOf('\n') < 0 }.joinToString("\n")

    fun decode(stored: String?): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        if (stored.isNullOrEmpty()) return out
        for (line in stored.split('\n')) if (line.isNotEmpty()) out += line
        return out
    }
}
