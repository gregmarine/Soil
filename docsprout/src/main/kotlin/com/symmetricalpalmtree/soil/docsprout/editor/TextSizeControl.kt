package com.symmetricalpalmtree.soil.docsprout.editor

import android.content.Context
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.docsprout.data.DocsproutPrefs
import com.symmetricalpalmtree.soil.docsprout.data.TextSizes
import com.symmetricalpalmtree.soil.docsprout.databinding.ActivityDocumentBinding
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog

/**
 * The editor's text size: five steps, the sheet that picks one, and the size in force applied to
 * the text. It is a control rather than a tool: it acts on the screen, not on the words. The
 * choice is this device's, and outlives the showing.
 */
internal class TextSizeControl(
    private val context: Context,
    private val binding: ActivityDocumentBinding,
    private val prefs: DocsproutPrefs,
) {

    /** The size in force, in sp: **not** `editor.textSize`, which is px. */
    var sp: Float = TextSizes.DEFAULT
        private set

    /** The stored size, at open. */
    fun restore() = apply(prefs.textSize, persist = false)

    /** Pick a text size. The tick marks the one in force. */
    fun prompt() {
        val sheet = ActionSheetDialog(context).title(context.getString(R.string.text_size_title))
        TextSizes.SIZES.forEachIndexed { index, size ->
            val label = context.getString(LABELS[index])
            sheet.addAction(null, if (size == sp) context.getString(R.string.text_size_current, label) else label) { apply(size) }
        }
        sheet.show()
    }

    private fun apply(size: Float, persist: Boolean = true) {
        sp = size
        binding.editor.textSize = size
        // Prose reads a little larger than the monospace source it is written as.
        binding.rich.setBodySize(size + RENDERED_BUMP)
        if (persist) prefs.textSize = size
    }

    private companion object {
        const val RENDERED_BUMP = 2f
        val LABELS = listOf(R.string.text_size_small, R.string.text_size_medium, R.string.text_size_large, R.string.text_size_larger, R.string.text_size_largest)
    }
}
