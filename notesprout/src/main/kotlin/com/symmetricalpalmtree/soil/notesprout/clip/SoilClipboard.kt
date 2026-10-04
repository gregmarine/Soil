package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.ISoilSeam
import com.symmetricalpalmtree.soil.seam.SeamClip
import com.symmetricalpalmtree.soil.seam.SeamShared

/** What is on the clipboard, without the payload. */
data class ClipHeader(val kind: String, val sourceNotebookId: String, val copiedAt: Long)

/**
 * The notebook kind's clipboard, which lives in Soil: the in-memory mirror of its **header**, so
 * a sheet can decide synchronously whether a Paste row exists, and the four calls that read and
 * write it over the seam. The payload never lives here; it is read only when a paste happens.
 * The header is read once per process, at a notebook's open, and set by every copy. IO only.
 */
object SoilClipboard {

    @Volatile
    var header: ClipHeader? = null
        private set

    @Volatile
    private var loaded = false

    val hasPage: Boolean get() = header?.kind == ClipEnvelope.KIND_PAGE

    /** One slot, kind wins: [hasPage] and this are mutually exclusive by construction. */
    val hasObjects: Boolean get() = header?.kind == ClipEnvelope.KIND_OBJECTS

    /** Read the header once per process. A failed read does not latch: one transient seam
     *  error costs this open, not the whole process life. */
    fun ensureLoaded(seam: ISoilSeam) {
        if (loaded) return
        try {
            header = seam.clipHeader(NotebookSchema.KIND)?.let { ClipHeader(it.payloadKind, it.sourceItemId, it.copiedAt) }
            loaded = true
        } catch (e: Exception) {
            Slog.d(TAG) { "clipboard header read failed: ${e.javaClass.simpleName}" }
        }
    }

    /** Put [env] on the clipboard. Null when the payload does not fit: nothing is written and the
     *  previous clipboard stands. Throws when the seam refuses. */
    fun write(seam: ISoilSeam, env: ClipEnvelope): ClipHeader? {
        val bytes = ClipEnvelope.encode(env) ?: return null
        seam.putClip(NotebookSchema.KIND, SeamClip(env.kind, env.sourceNotebookId, env.copiedAt), SeamShared.write(bytes))
        return ClipHeader(env.kind, env.sourceNotebookId, env.copiedAt).also { header = it; loaded = true }
    }

    /** The payload, or null when the clipboard is empty or its bytes are unusable. */
    fun read(seam: ISoilSeam): ClipEnvelope? {
        val region = seam.clip(NotebookSchema.KIND) ?: return null
        return ClipEnvelope.decode(runCatching { SeamShared.readAndClose(region) }.getOrNull())
    }

    /** Retire the clipboard, in memory and in Soil. Never throws. */
    fun clear(seam: ISoilSeam) {
        header = null
        loaded = true
        runCatching { seam.clearClip(NotebookSchema.KIND) }.onFailure { Slog.d(TAG) { "clipboard clear failed: ${it.javaClass.simpleName}" } }
    }

    private const val TAG = "SoilClipboard"
}
