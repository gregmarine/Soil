package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.soil.paper.ink.InkAction

/**
 * What the notebook can put back. Two shapes: an **ink** edit, one of `:paper`'s [InkAction]s,
 * and a change to the **page list**, described well enough to replay in either direction.
 */
sealed interface NotebookAction {

    class Ink(val action: InkAction) : NotebookAction

    /**
     * A page insert or delete: the live pages [before] and [after], in order; the ids of the
     * content the operation soft-deleted (empty for an insert), which undo restores; and the
     * page the notebook was on either side. Undo makes the page set [before] and redo [after],
     * through one primitive with the two sides swapped.
     */
    class Page(
        val before: List<PageRef>,
        val after: List<PageRef>,
        val contentIds: List<String>,
        val beforeCurrent: String,
        val afterCurrent: String,
    ) : NotebookAction
}
