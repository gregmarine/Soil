package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import com.symmetricalpalmtree.soil.notesprout.objects.PageSticky
import com.symmetricalpalmtree.soil.notesprout.objects.PageText
import com.symmetricalpalmtree.soil.paper.ink.InkAction

/**
 * What the notebook can put back. Every entry names the page it happened on, so history survives
 * a page turn. An object's row survives a delete soft-deleted with every column intact, so ids are
 * enough for most kinds; a sticky note carries its content, because its children revive by id
 * from the snapshot.
 */
sealed interface NotebookAction {

    /** An ink edit: a stroke, an erase, a move of ink alone. */
    class Ink(val action: InkAction) : NotebookAction

    /**
     * One gesture that took ink and objects together: the eraser sweeping a heading, a scribble,
     * a lasso erase, or Delete on a mixed selection. One gesture is one undo step.
     */
    class Deleted(val pageId: String, val ink: InkAction.Erased?, val objects: DeletedObjects) : NotebookAction

    /** One selection drag, ink and objects together. A sticky's content does not move; a link
     *  moves with everything it wraps. */
    class Moved(
        val pageId: String,
        val ink: InkAction.Moved?,
        val headingIds: List<String>,
        val textIds: List<String>,
        val stickyIds: List<String>,
        val dx: Float,
        val dy: Float,
        val linkIds: List<String> = emptyList(),
    ) : NotebookAction

    class HeadingCreated(val pageId: String, val heading: Heading) : NotebookAction

    /** An edit of the words or the level; both sides carry the whole heading. */
    class HeadingEdited(val pageId: String, val before: Heading, val after: Heading) : NotebookAction

    class TextCreated(val pageId: String, val text: PageText) : NotebookAction

    class TextEdited(val pageId: String, val before: PageText, val after: PageText) : NotebookAction

    /** The icon row as created, with no content yet. */
    class StickyInserted(val pageId: String, val sticky: PageSticky) : NotebookAction

    /** One showing of the sticky editor that changed the note: the content before and after, in
     *  the note's own space. Either direction makes one side the note's whole content. */
    class StickyContentEdited(val pageId: String, val stickyId: String, val before: List<Stroke>, val after: List<Stroke>) : NotebookAction

    /** A selection wrapped into a link: the link as made, with everything it wraps. */
    class LinkCreated(val pageId: String, val link: PageLink) : NotebookAction

    /** A link unwrapped: the link as it was, so a redo of the wrap takes the same children. */
    class LinkUnlinked(val pageId: String, val link: PageLink) : NotebookAction

    /** Where a link points, rewritten. Both payloads, so either direction is one write. */
    class LinkEdited(val pageId: String, val linkId: String, val before: String, val after: String) : NotebookAction

    /** The page re-papered: the template row ids before and after (`""` is blank). Either direction is one write. */
    class TemplateChanged(val pageId: String, val from: String, val to: String) : NotebookAction

    /** Erase page: everything on [pageId] soft-deleted; the page stays. Undo restores [ids]. */
    class PageErased(val pageId: String, val ids: List<String>) : NotebookAction

    /**
     * A page insert or delete: the live pages [before] and [after], in order; the ids of the
     * content the operation soft-deleted (empty for an insert), which undo restores; and the
     * page the notebook was on either side.
     */
    class Page(
        val before: List<PageRef>,
        val after: List<PageRef>,
        val contentIds: List<String>,
        val beforeCurrent: String,
        val afterCurrent: String,
    ) : NotebookAction
}
