package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.ISoilSeam
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.clip.BibleClip

/**
 * The `bible` kind's clipboard, as the notebook sees it: the in-memory mirror of when a passage
 * was copied there, read beside the notebook kind's header, so a Paste knows which was copied
 * last without a seam call. The payload is read only when a paste happens. IO only.
 */
object BibleClipboard {

    @Volatile
    var copiedAt: Long? = null
        private set

    val has: Boolean get() = copiedAt != null

    /** Whether the passage was copied after the notebook kind's clipboard (ink or objects). */
    fun newerThan(other: ClipHeader?): Boolean {
        val at = copiedAt ?: return false
        return other == null || at > other.copiedAt
    }

    fun refresh(seam: ISoilSeam) {
        try {
            copiedAt = seam.clipHeader(BibleClip.SLOT)?.copiedAt
        } catch (e: Exception) {
            Slog.d(TAG) { "bible clipboard header read failed: ${e.javaClass.simpleName}" }
        }
    }

    fun read(seam: ISoilSeam): BibleClip? {
        val region = seam.clip(BibleClip.SLOT) ?: return null
        return BibleClip.decode(runCatching { SeamShared.readAndClose(region) }.getOrNull())
    }

    fun clear(seam: ISoilSeam) {
        copiedAt = null
        runCatching { seam.clearClip(BibleClip.SLOT) }.onFailure { Slog.d(TAG) { "bible clipboard clear failed: ${it.javaClass.simpleName}" } }
    }

    private const val TAG = "BibleClipboard"
}
