package com.symmetricalpalmtree.soil.shell

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * **The apps the person has chosen not to see.** Many installed apps have a launcher entry and
 * were never meant to be opened by hand; which ones are worth seeing is the person's call, made
 * one app at a time.
 *
 * A hidden app leaves the app drawer for the hidden apps drawer. Hiding changes nothing about the
 * app itself, and a long press there brings it back.
 *
 * It is an ordinary preference, not part of the encrypted library: the drawer works while the
 * library is locked, so what it shows cannot depend on the key. Read from disk once,
 * off the main thread, and answered from memory after that.
 *
 * A hidden app that is uninstalled stays on the list, so it is still hidden if it comes back.
 */
object HiddenApps {

    private const val FILE = "soil_apps"
    private const val KEY_HIDDEN = "hidden"

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> get() = _hidden

    /** What an app is remembered by: the activity that opens it. */
    fun keyOf(app: AppEntry): String = "${app.packageName}/${app.className}"

    /** [all] without what is hidden, in the order it came. Pure. */
    fun visible(all: List<AppEntry>, hidden: Set<String>): List<AppEntry> = all.filter { keyOf(it) !in hidden }

    /** What of [all] is hidden, in the order it came. Pure. */
    fun hiddenOf(all: List<AppEntry>, hidden: Set<String>): List<AppEntry> = all.filter { keyOf(it) in hidden }

    private val lock = Any()
    private var loaded = false
    /** Hides and shows made before the load landed, in order: replayed over what was stored,
     *  so an early tap never writes over the whole list. */
    private val early = ArrayList<Pair<String, Boolean>>()

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val stored = prefs(context).getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet()
        synchronized(lock) {
            val merged = replay(stored, early)
            loaded = true
            _hidden.value = merged
            if (early.isNotEmpty()) {
                early.clear()
                write(context, merged)
            }
        }
    }

    fun hide(context: Context, app: AppEntry) = edit(context, keyOf(app), true)

    fun show(context: Context, app: AppEntry) = edit(context, keyOf(app), false)

    /** [stored] with each edit applied in turn: true hides the key, false shows it. Pure. */
    fun replay(stored: Set<String>, edits: List<Pair<String, Boolean>>): Set<String> =
        edits.fold(stored) { set, (key, hide) -> if (hide) set + key else set - key }

    private fun edit(context: Context, key: String, hide: Boolean) = synchronized(lock) {
        val next = replay(_hidden.value, listOf(key to hide))
        _hidden.value = next
        if (loaded) write(context, next) else early += key to hide
    }

    private fun write(context: Context, next: Set<String>) {
        // A copy: a set handed to the preferences must never be changed afterwards.
        prefs(context).edit().putStringSet(KEY_HIDDEN, HashSet(next)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
