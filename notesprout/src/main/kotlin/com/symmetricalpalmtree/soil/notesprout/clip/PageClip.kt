package com.symmetricalpalmtree.soil.notesprout.clip

import com.symmetricalpalmtree.soil.notesprout.data.NotebookRow
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload

/**
 * A page ⇄ clipboard payload, and nothing else. Pure, so the risky half of copy and paste (which
 * id becomes which, what re-parents onto what, what keeps its `"order"`) is provable off-device.
 *
 * Row-level, not object-level: a page copies with everything on it without this file learning a
 * content type. A payload may carry **several pages** (the calendar's Copy page of a Day: AM and
 * PM — Greg, 2026-10-05); they paste in its order, each after the one before, as one undo step. Two rules: **every pasted row gets a fresh id** through one old→new map, so a
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

    /** The rows to write in insert order (templates, pages, content) and the pages' new
     *  descendants, which an undo soft-deletes. A template row is left in place by an undo.
     *  [pageIds] are the pasted pages in the envelope's order; [pageId] the first of them. */
    data class Plan(val pageIds: List<String>, val rows: List<NotebookRow>, val contentIds: List<String>) {
        val pageId: String get() = pageIds.first()
    }

    /** Snapshot [page] and everything on it. [content] is its live descendants at any depth;
     *  [template] its template row, or null for a blank page. */
    fun capture(page: NotebookRow, template: NotebookRow?, content: List<NotebookRow>, sourceNotebookId: String, now: Long): ClipEnvelope =
        ClipEnvelope(
            version = ClipEnvelope.VERSION, kind = ClipEnvelope.KIND_PAGE, sourceNotebookId = sourceNotebookId, copiedAt = now,
            rows = (listOfNotNull(template) + page + content).map { clipRowOf(it) },
        )

    /**
     * The rows a paste into [notebookId] writes, **every page row of the payload in its order**,
     * the first taking slot [firstOrder] and each next the slot after (a notebook's own Copy
     * page carries one; the calendar's Copy page of a Day carries two — Greg, 2026-10-05). Null
     * when the payload holds no page row. Each page reaches its paper through [template], which
     * only the caller can answer, since only it sees the destination file; a template row is
     * brought in once however many pages name it. A content row whose parent did not travel is
     * **dropped**, never re-parented onto a page: the payload is untrusted input like any file.
     */
    fun plan(env: ClipEnvelope, notebookId: String, firstOrder: Int, template: (page: ClipRow) -> Template, newId: () -> String): Plan? {
        val pageRows = env.rows.filter { it.type == NotebookSchema.TYPE_PAGE }
        if (pageRows.isEmpty()) return null
        val content = env.rows.filter { it.type != NotebookSchema.TYPE_PAGE && it.type != NotebookSchema.TYPE_TEMPLATE }

        val idMap = HashMap<String, String>(content.size * 2 + pageRows.size * 2)
        for (row in pageRows) idMap[row.id] = newId()
        for (row in content) idMap[row.id] = newId()

        val templates = ArrayList<NotebookRow>()
        val inserted = HashSet<String>()
        val pages = ArrayList<NotebookRow>(pageRows.size)
        for ((i, pageRow) in pageRows.withIndex()) {
            val refId = when (val t = template(pageRow)) {
                Template.None -> ""
                is Template.Reuse -> t.id
                is Template.Insert -> {
                    val carried = env.rows.firstOrNull { it.type == NotebookSchema.TYPE_TEMPLATE && it.id == pageRow.refId }
                    if (carried == null) "" else {
                        if (inserted.add(t.id)) templates += carried.toRow(t.id, notebookId, carried.order)
                        t.id
                    }
                }
            }
            pages += pageRow.toRow(idMap.getValue(pageRow.id), notebookId, firstOrder + i).copy(refId = refId)
        }

        val crossNotebook = env.sourceNotebookId.isNotBlank() && env.sourceNotebookId != notebookId
        val sourcePageIds = pageRows.map { it.id }.toSet()
        val rows = ArrayList<NotebookRow>(templates.size + pages.size + content.size)
        rows += templates
        rows += pages
        val contentIds = ArrayList<String>(content.size)
        for (row in content) {
            val parentId = idMap[row.parentId] ?: continue
            val id = idMap.getValue(row.id)
            var out = row.toRow(id, parentId, row.order)
            if (crossNotebook && row.type == NotebookSchema.TYPE_LINK) {
                out = out.copy(text = rewriteLink(row.text, env.sourceNotebookId, sourcePageIds, idMap))
            }
            rows += out
            contentIds += id
        }
        return Plan(pageRows.map { idMap.getValue(it.id) }, rows, contentIds)
    }

    /**
     * What a link's payload must say once its page lives in another notebook. A page link
     * carries no item id: it means "a page of my own notebook", a different page once the row has
     * moved, so it is re-pointed at the notebook it was copied from. A link whose target **is**
     * the page being pasted follows the copy instead. Item and item-page links already name their
     * item and travel unchanged; a payload that does not decode travels verbatim.
     */
    fun rewriteLink(text: String?, sourceNotebookId: String, sourcePageId: String, newPageId: String): String? =
        rewriteLink(text, sourceNotebookId, setOf(sourcePageId), mapOf(sourcePageId to newPageId))

    /** [rewriteLink] over every page being pasted: a link to any of them follows its copy. */
    fun rewriteLink(text: String?, sourceNotebookId: String, sourcePageIds: Set<String>, idMap: Map<String, String>): String? {
        val decoded = LinkPayload.decode(text ?: return null) ?: return text
        if (decoded.kind != LinkPayload.KIND_PAGE) return text
        val target = decoded.pageId ?: return text
        return runCatching {
            if (target in sourcePageIds) LinkPayload.encode(decoded.chrome, LinkPayload.KIND_PAGE, null, idMap.getValue(target))
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
