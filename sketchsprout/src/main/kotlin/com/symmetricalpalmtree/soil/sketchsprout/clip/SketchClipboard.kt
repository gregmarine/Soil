package com.symmetricalpalmtree.soil.sketchsprout.clip

import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.ISoilSeam
import com.symmetricalpalmtree.soil.seam.SeamClip
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.seamkit.clip.InkClip
import com.symmetricalpalmtree.soil.sketchsprout.data.SketchbookSchema

/**
 * The two clipboards a sketchbook reads, which live in Soil: the **sketchbook** kind's slot,
 * where Copy page puts a page for Paste page, and the **notebook** kind's slot, where a notebook's
 * lasso, the pad or the calendar put ink that Paste ink bakes onto a sketch page. The in-memory
 * mirror is the two **headers**, so the sheet can decide synchronously which Paste rows exist;
 * a payload is read only when a paste happens. Read at open and again at every return to the
 * front; the page header is set by every copy. IO only.
 */
object SketchClipboard {

    const val SLOT: String = SketchbookSchema.KIND

    @Volatile
    var hasPage: Boolean = false
        private set

    /** Whether the notebook slot holds ink of either shape: a lasso's objects or a whole page. */
    @Volatile
    var hasInk: Boolean = false
        private set

    /** Both headers read again. A failed read leaves what was known. */
    fun refresh(seam: ISoilSeam) {
        try {
            hasPage = seam.clipHeader(SLOT)?.payloadKind == SketchPageClip.KIND_PAGE
            val ink = seam.clipHeader(InkClip.SLOT)?.payloadKind
            hasInk = ink == ClipEnvelope.KIND_OBJECTS || ink == ClipEnvelope.KIND_PAGE
        } catch (e: Exception) {
            Slog.d(TAG) { "clipboard header refresh failed: ${e.javaClass.simpleName}" }
        }
    }

    /** Put a page's [bytes] on the sketchbook slot. Throws when the seam refuses. */
    fun writePage(seam: ISoilSeam, sourceId: String, bytes: ByteArray, now: Long) {
        seam.putClip(SLOT, SeamClip(SketchPageClip.KIND_PAGE, sourceId, now), SeamShared.write(bytes))
        hasPage = true
    }

    /** The page payload's bytes, or null when the slot is empty. */
    fun readPage(seam: ISoilSeam): ByteArray? {
        val region = seam.clip(SLOT) ?: return null
        return runCatching { SeamShared.readAndClose(region) }.getOrNull()
    }

    /** The notebook slot's envelope, or null when empty or unusable. */
    fun readInk(seam: ISoilSeam): ClipEnvelope? {
        val region = seam.clip(InkClip.SLOT) ?: return null
        return ClipEnvelope.decode(runCatching { SeamShared.readAndClose(region) }.getOrNull())
    }

    /** Retire the page slot, in memory and in Soil. Never throws. */
    fun clearPage(seam: ISoilSeam) {
        hasPage = false
        runCatching { seam.clearClip(SLOT) }
    }

    private const val TAG = "SketchClipboard"
}
