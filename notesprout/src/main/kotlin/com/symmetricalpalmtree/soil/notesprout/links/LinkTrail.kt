package com.symmetricalpalmtree.soil.notesprout.links

import android.content.Context
import com.symmetricalpalmtree.soil.notesprout.objects.TrailCodec
import com.symmetricalpalmtree.soil.notesprout.objects.TrailEntry

/**
 * Where a link story's hops are remembered. Every successful follow pushes the **origin** (the
 * notebook and page the person was looking at) before going; a swipe up pops the newest and
 * returns there. Stored rather than held, because a follow into another notebook restarts the
 * screen, and a process death mid-story must not strand the person with no way home.
 *
 * A fresh open of any notebook, not via a link, [clear]s it: that is a new story.
 *
 * Device-local, ids only, never a name: the library is the only place a name lives.
 */
class LinkTrail(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun entries(): List<TrailEntry> = TrailCodec.decode(prefs.getString(KEY, null))

    fun push(entry: TrailEntry) = save(TrailCodec.push(entries(), entry))

    fun pop(): TrailEntry? {
        val (entry, rest) = TrailCodec.pop(entries())
        if (entry != null) save(rest)
        return entry
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun save(entries: List<TrailEntry>) = prefs.edit().putString(KEY, TrailCodec.encode(entries)).apply()

    private companion object {
        const val FILE = "notesprout_trail"
        const val KEY = "trail"
    }
}
