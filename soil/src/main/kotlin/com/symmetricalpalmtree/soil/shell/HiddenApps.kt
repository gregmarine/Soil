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
 * An app is hidden from the app drawer and from the side menu alike. Hiding changes nothing about
 * the app itself, and the hidden apps screen brings any of them back.
 *
 * It is an ordinary preference, not part of the encrypted library: the drawer and the menu work
 * while the library is locked, so what they show cannot depend on the key. Read from disk once,
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

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        _hidden.value = prefs(context).getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet()
    }

    fun hide(context: Context, app: AppEntry) = save(context, _hidden.value + keyOf(app))

    fun show(context: Context, app: AppEntry) = save(context, _hidden.value - keyOf(app))

    private fun save(context: Context, next: Set<String>) {
        _hidden.value = next
        // A copy: a set handed to the preferences must never be changed afterwards.
        prefs(context).edit().putStringSet(KEY_HIDDEN, HashSet(next)).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
