package com.symmetricalpalmtree.soil.ext

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.settings.SettingsPrefs

/** One installed recogniser: its service, its label, and the languages it declared. */
data class Recognizer(val packageName: String, val className: String, val label: String, val languages: List<String>) {
    /** The key Settings stores: the service's package and class, which a flattened component also reads as. */
    val key: String get() = "$packageName/$className"
    val component: ComponentName get() = ComponentName(packageName, className)
}

/** The recogniser and language Soil relays to. */
data class RecognizerChoice(val recognizer: Recognizer, val languageTag: String)

/**
 * **Which recognisers are installed, and which one Soil uses.** A candidate `<service>` is kept
 * when it is exported, declares this contract's version, is signed with Soil's key and is of
 * Soil's build. Looked for each time, never remembered: an extension can be installed or removed
 * while Soil runs.
 *
 * The choice is Settings'. With nothing chosen and exactly one recogniser installed, that one in
 * its first language: the common case needs no setting.
 */
object Recognizers {

    private const val TAG = "Recognizers"

    fun installed(context: Context): List<Recognizer> {
        val pm = context.packageManager
        val found = try {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(Intent(ExtContract.ACTION_RECOGNIZER), PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.w(TAG, "the recognisers could not be read: ${e.javaClass.simpleName}")
            return emptyList()
        }
        val kept = ArrayList<Recognizer>()
        for (ri in found) {
            val si = ri.serviceInfo ?: continue
            val component = ComponentName(si.packageName, si.name)
            if (!si.exported) continue
            val version = si.metaData?.getInt(ExtContract.META_API_VERSION, -1) ?: -1
            if (version != ExtContract.API_VERSION) { Slog.d(TAG) { "skip $component: contract version $version" }; continue }
            if (pm.checkSignatures(context.packageName, si.packageName) != PackageManager.SIGNATURE_MATCH) { Slog.d(TAG) { "skip $component: signature" }; continue }
            if (!ExtContract.sameBuild(context.packageName, si.packageName)) { Slog.d(TAG) { "skip $component: other build" }; continue }
            val languages = ExtContract.languages(si.metaData?.getString(ExtContract.META_LANGUAGES))
            if (languages.isEmpty()) { Slog.d(TAG) { "skip $component: no languages" }; continue }
            kept += Recognizer(si.packageName, si.name, si.applicationInfo?.loadLabel(pm)?.toString() ?: si.packageName, languages)
        }
        return kept.sortedWith(compareBy({ it.label }, { it.packageName }))
    }

    /** The choice as Settings made it, among what is installed; the lone recogniser by default. Pure over [installed]. */
    fun choose(installed: List<Recognizer>, chosenKey: String?, chosenLanguage: String?): RecognizerChoice? {
        val chosen = installed.firstOrNull { it.key == chosenKey }
        if (chosen != null) {
            val language = chosenLanguage?.takeIf { it in chosen.languages } ?: chosen.languages.first()
            return RecognizerChoice(chosen, language)
        }
        if (chosenKey == NONE) return null
        val only = installed.singleOrNull() ?: return null
        return RecognizerChoice(only, only.languages.first())
    }

    fun chosen(context: Context): RecognizerChoice? {
        val prefs = SettingsPrefs(context)
        return choose(installed(context), prefs.recognizerKey, prefs.recognizerLanguage)
    }

    /** The key Settings stores for "no recogniser", so a lone installed one is not used by default. */
    const val NONE = "none"
}
