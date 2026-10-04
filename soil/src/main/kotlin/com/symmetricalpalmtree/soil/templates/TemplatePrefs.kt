package com.symmetricalpalmtree.soil.templates

import android.content.Context

enum class SortField { NAME, MODIFIED }
enum class SortOrder { ASC, DESC }

/**
 * What this device remembers of the paper library: the sort, and the paper recently **applied**,
 * by card id, newest first. Ids only, never a name: these prefs are plaintext. A recent id may be
 * a sentinel with no row, which is why the prune takes the pruneable set rather than the alive
 * rows. A corrupt value reads as empty.
 */
class TemplatePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var sortField: SortField
        get() = prefs.getString(KEY_SORT_FIELD, null)?.let { runCatching { SortField.valueOf(it) }.getOrNull() } ?: SortField.NAME
        set(value) { prefs.edit().putString(KEY_SORT_FIELD, value.name).apply() }

    var sortOrder: SortOrder
        get() = prefs.getString(KEY_SORT_ORDER, null)?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.ASC
        set(value) { prefs.edit().putString(KEY_SORT_ORDER, value.name).apply() }

    /** Newest first. */
    fun recents(): List<String> = prefs.getString(KEY_RECENTS, null).orEmpty().lineSequence().filter { it.isNotBlank() }.toList()

    fun recordUse(id: String) {
        val list = recents().toMutableList()
        list.remove(id)
        list.add(0, id)
        save(list.take(MAX_RECENTS))
    }

    fun forget(ids: Collection<String>) {
        val list = recents()
        if (list.any { it in ids }) save(list.filter { it !in ids })
    }

    /** Drop every entry not in [keep]. */
    fun prune(keep: Set<String>) {
        val list = recents()
        if (list.any { it !in keep }) save(list.filter { it in keep })
    }

    private fun save(list: List<String>) = prefs.edit().putString(KEY_RECENTS, list.joinToString("\n")).apply()

    private companion object {
        const val FILE = "soil_templates"
        const val KEY_SORT_FIELD = "sortField"
        const val KEY_SORT_ORDER = "sortOrder"
        const val KEY_RECENTS = "recents"
        const val MAX_RECENTS = 20
    }
}

/**
 * A picture an app parked for the save-template screen. One at a time, in memory: an app's
 * `stageTemplate` puts it here, the screen takes it, and a parking never taken up is replaced
 * by the next.
 */
object TemplateStaging {
    private var id: String? = null
    private var bytes: ByteArray? = null

    @Synchronized
    fun stage(image: ByteArray): String {
        val fresh = java.util.UUID.randomUUID().toString()
        id = fresh
        bytes = image
        return fresh
    }

    /** The parked picture for [stagedId], taken: a second ask answers null. */
    @Synchronized
    fun take(stagedId: String): ByteArray? {
        if (stagedId != id) return null
        val out = bytes
        id = null
        bytes = null
        return out
    }
}

/**
 * A short text an app parked for a screen of Soil's: the tag screen's prefill. One at a time,
 * in memory, taken once, so what a person wrote never rides an Intent.
 */
object TextStaging {
    private var id: String? = null
    private var text: String? = null

    @Synchronized
    fun stage(value: String): String {
        val fresh = java.util.UUID.randomUUID().toString()
        id = fresh
        text = value
        return fresh
    }

    @Synchronized
    fun take(stagedId: String?): String? {
        if (stagedId == null || stagedId != id) return null
        val out = text
        id = null
        text = null
        return out
    }
}
