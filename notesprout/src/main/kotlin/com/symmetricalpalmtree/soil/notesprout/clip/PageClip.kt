package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.soil.notesprout.data.NotebookRow
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload

/**
 * A page ⇄ clipboard payload, and nothing else. Pure, so the risky half of copy and paste (which
 * id becomes which, what re-parents onto what, what keeps its `"order"`) is provable off-device.
 *
 * Row-level, not object-level: a page copies with everything on it without this file learning a
 * content type. Two rules: **every pasted row gets a fresh id** through one old→new map, so a
 * link's wrapped children re-parent onto the copied link; and **`"order"` is preserved
 * verbatim** on content, since writing order is load-bearing. Only the page row's own order is
 * rewritten, to the slot it is inserted at. The one row it reads meaning out of is a link's
 * payload, across notebooks ([rewriteLink]).
 */
object PageClip {

    /** How the destination reaches a template: the caller decides, since only it sees the destination file. */
    sealed interface Template {
        /** A blank page: `refId` is `""`. */
        data object None : Template

        /** A row in the destination is this paper: point at it, insert nothing. */
        data class Reuse(val id: String) : Template

        /** Bring the payload's template row in under [id]. */
        data class Insert(val id: String) : Template
    }

    /** The rows to write in insert order (template, page, content) and the page's new descendants,
     *  which an undo soft-deletes. The template row is left in place by an undo. */
    data class Plan(val pageId: String, val rows: List<NotebookRow>, val contentIds: List<String>)

    /** Snapshot [page] and everything on it. [content] is its live descendants at any depth;
     *  [template] its template row, or null for a blank page. */
    fun capture(page: NotebookRow, template: NotebookRow?, content: List<NotebookRow>, sourceNotebookId: String, now: Long): ClipEnvelope =
        ClipEnvelope(
            version = ClipEnvelope.VERSION, kind = ClipEnvelope.KIND_PAGE, sourceNotebookId = sourceNotebookId, copiedAt = now,
            rows = (listOfNotNull(template) + page + content).map { clipRowOf(it) },
        )

    /**
     * The rows a paste into [notebookId] writes, the page taking slot [pageOrder]. Null when the
     * payload holds no page row. A content row whose parent did not travel is **dropped**, never
     * re-parented onto the page: the payload is untrusted input like any file.
     */
    fun plan(env: ClipEnvelope, notebookId: String, pageOrder: Int, template: Template, newId: () -> String): Plan? {
        val pageRow = env.rows.firstOrNull { it.type == NotebookSchema.TYPE_PAGE } ?: return null
        val content = env.rows.filter { it.type != NotebookSchema.TYPE_PAGE && it.type != NotebookSchema.TYPE_TEMPLATE }

        val newPageId = newId()
        val idMap = HashMap<String, String>(content.size * 2 + 2)
        idMap[pageRow.id] = newPageId
        for (row in content) idMap[row.id] = newId()

        val rows = ArrayList<NotebookRow>(content.size + 2)
        val templateRow = env.rows.firstOrNull { it.type == NotebookSchema.TYPE_TEMPLATE }
        val refId = when (template) {
            Template.None -> ""
            is Template.Reuse -> template.id
            is Template.Insert -> if (templateRow == null) "" else {
                rows += templateRow.toRow(template.id, notebookId, templateRow.order)
                template.id
            }
        }
        rows += pageRow.toRow(newPageId, notebookId, pageOrder).copy(refId = refId)

        val crossNotebook = env.sourceNotebookId.isNotBlank() && env.sourceNotebookId != notebookId
        val contentIds = ArrayList<String>(content.size)
        for (row in content) {
            val parentId = idMap[row.parentId] ?: continue
            val id = idMap.getValue(row.id)
            var out = row.toRow(id, parentId, row.order)
            if (crossNotebook && row.type == NotebookSchema.TYPE_LINK) {
                out = out.copy(text = rewriteLink(row.text, env.sourceNotebookId, pageRow.id, newPageId))
            }
            rows += out
            contentIds += id
        }
        return Plan(newPageId, rows, contentIds)
    }

    /**
     * What a link's payload must say once its page lives in another notebook. A page link
     * carries no item id: it means "a page of my own notebook", a different page once the row has
     * moved, so it is re-pointed at the notebook it was copied from. A link whose target **is**
     * the page being pasted follows the copy instead. Item and item-page links already name their
     * item and travel unchanged; a payload that does not decode travels verbatim.
     */
    fun rewriteLink(text: String?, sourceNotebookId: String, sourcePageId: String, newPageId: String): String? {
        val decoded = LinkPayload.decode(text ?: return null) ?: return text
        if (decoded.kind != LinkPayload.KIND_PAGE) return text
        val target = decoded.pageId ?: return text
        return runCatching {
            if (target == sourcePageId) LinkPayload.encode(decoded.chrome, LinkPayload.KIND_PAGE, null, newPageId)
            else LinkPayload.encode(decoded.chrome, LinkPayload.KIND_ITEM_PAGE, sourceNotebookId, target)
        }.getOrDefault(text)
    }

    /**
     * The id of a destination template row that is **the same paper** as the payload's: the
     * kind label, the size it was rendered for, and byte-identical pixels. Null when none is.
     */
    fun matchTemplate(payload: ClipRow?, candidates: List<NotebookRow>): String? {
        if (payload == null) return null
        val bytes = payload.blobBytes() ?: return null
        return candidates.firstOrNull {
            it.text == payload.text && it.width == payload.width && it.height == payload.height && it.blob != null && it.blob.contentEquals(bytes)
        }?.id
    }
}
