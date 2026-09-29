package com.symmetricalpalmtree.soil.crypto

import java.io.File

/**
 * Which database files this process currently holds a connection to.
 *
 * A re-key needs its file **cold**: checkpointed, closed, and not about to be written under the
 * copy. "Guaranteed by where the button is" is not something the code can check, so the code
 * checks this instead. Claimed and released by whatever owns the connection — `SoilIndex` and
 * `AppStores` — on open and on close.
 *
 * A count rather than a set: if the one-connection rule is ever broken the registry must still be
 * honest about when the *last* one goes. An unbalanced claim makes a re-key of that file refuse
 * until the process dies, which is the safe direction.
 */
object OpenFiles {

    private val open = HashMap<String, Int>()

    @Synchronized
    fun claim(file: File) {
        val k = key(file)
        open[k] = (open[k] ?: 0) + 1
    }

    @Synchronized
    fun release(file: File) {
        val k = key(file)
        val n = (open[k] ?: 0) - 1
        if (n <= 0) open.remove(k) else open[k] = n
    }

    /** True while any connection to [file] is open in this process. */
    @Synchronized
    fun isOpen(file: File): Boolean = key(file) in open

    /** Canonical where the filesystem will say, absolute otherwise. */
    private fun key(file: File): String =
        try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
}
