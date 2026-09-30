package com.symmetricalpalmtree.soil.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.notesprout.data.NotebookStore

/**
 * The hand-off between the notebook and the sticky editor, a process-local singleton: the editor
 * opens no notebook of its own. The notebook's session stays alive behind it, and every row the
 * editor writes goes through the notebook's own [NotebookStore]. Nothing rides the Intent but the
 * ids. After a process death the editor finds nothing staged and finishes at once.
 */
object StickyEditorTransfer {

    class Showing(
        val store: NotebookStore,
        val pageId: String,
        val stickyId: String,
        /** The note's paper in px, or 0 × 0 for an old or foreign row (the editor uses its own). */
        val contentW: Int,
        val contentH: Int,
        /** The content as read at launch, in the note's own space, each with the order it holds. */
        val initial: List<Pair<Long, Stroke>>,
    )

    @Volatile
    private var staged: Showing? = null

    /** The showing up now, for the result callback. */
    @Volatile
    var current: Showing? = null
        private set

    fun stage(showing: Showing) {
        staged = showing
        current = showing
    }

    /** The editor's `onCreate`: the staged showing, exactly once. */
    fun take(): Showing? {
        val s = staged
        staged = null
        return s
    }

    fun clear() {
        staged = null
        current = null
    }
}
