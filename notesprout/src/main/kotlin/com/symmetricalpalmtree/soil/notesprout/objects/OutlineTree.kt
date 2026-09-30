package com.symmetricalpalmtree.soil.notesprout.objects

import com.symmetricalpalmtree.soil.markdown.HeadingPrefix

/**
 * The Contents tree: heading items to a nested tree of up to [MAX_LEVEL] levels, the rows visible
 * under an expansion set, the entry to highlight for the current page, and the paging math. An
 * orphan attaches to the nearest shallower heading before it, or becomes a root: nothing the
 * person wrote is hidden. Pure.
 */
object OutlineTree {

    const val MAX_LEVEL: Int = 6

    data class Item(val objectId: String, val pageId: String, val pageIndex: Int, val x: Float, val y: Float, val label: String, val level: Int)

    /** Document order: page, then down the page, then across. */
    val DOCUMENT_ORDER: Comparator<Item> = compareBy<Item> { it.pageIndex }.thenBy { it.y }.thenBy { it.x }

    class Node(val id: String, val pageId: String, val pageIndex: Int, val label: String, val level: Int, val parent: Node?) {
        val children: MutableList<Node> = ArrayList()
    }

    fun build(items: List<Item>): List<Node> {
        val sorted = items.sortedWith(DOCUMENT_ORDER)
        val roots = ArrayList<Node>()
        val open = arrayOfNulls<Node>(MAX_LEVEL + 1)
        for (item in sorted) {
            val level = item.level.coerceIn(1, MAX_LEVEL)
            var parent: Node? = null
            for (l in level - 1 downTo 1) { val n = open[l]; if (n != null) { parent = n; break } }
            val node = Node(item.objectId, item.pageId, item.pageIndex, item.label, level, parent)
            if (parent == null) roots += node else parent.children += node
            open[level] = node
            for (l in level + 1..MAX_LEVEL) open[l] = null
        }
        return roots
    }

    /** Pre-order, descending only into nodes in [expanded]. */
    fun visible(roots: List<Node>, expanded: Set<String>): List<Node> {
        val out = ArrayList<Node>()
        fun walk(n: Node) { out += n; if (n.id in expanded) n.children.forEach(::walk) }
        roots.forEach(::walk)
        return out
    }

    fun all(roots: List<Node>): List<Node> {
        val out = ArrayList<Node>()
        fun walk(n: Node) { out += n; n.children.forEach(::walk) }
        roots.forEach(::walk)
        return out
    }

    /** The last node at or before [currentPageIndex], or its nearest visible ancestor. */
    fun highlight(all: List<Node>, currentPageIndex: Int, expanded: Set<String>): String? {
        val target = all.lastOrNull { it.pageIndex <= currentPageIndex } ?: return null
        var n: Node = target
        while (!isVisible(n, expanded)) n = n.parent ?: return n.id
        return n.id
    }

    private fun isVisible(n: Node, expanded: Set<String>): Boolean {
        var p = n.parent
        while (p != null) { if (p.id !in expanded) return false; p = p.parent }
        return true
    }

    fun ancestorsOf(node: Node): List<String> {
        val out = ArrayList<String>()
        var p = node.parent
        while (p != null) { out.add(0, p.id); p = p.parent }
        return out
    }

    fun pageOf(indexInVisible: Int, itemsPerPage: Int): Int =
        if (itemsPerPage <= 0 || indexInVisible < 0) 0 else indexInVisible / itemsPerPage

    fun pageCount(n: Int, itemsPerPage: Int): Int {
        val per = itemsPerPage.coerceAtLeast(1)
        return if (n <= 0) 1 else (n + per - 1) / per
    }

    /** Cap on listed entries, in document order. */
    const val MAX_ENTRIES = 2000

    /**
     * The pure half of the gather: heading rows `(heading, parentId)` to capped, ordered items.
     * A heading under a link is placed by the link's page. Dropped, never crashed on: a heading
     * whose page cannot be resolved, or whose label strips to blank.
     */
    fun items(
        headings: List<Pair<Heading, String>>,
        pageIndexById: Map<String, Int>,
        linkPageById: Map<String, String> = emptyMap(),
    ): Pair<List<Item>, Boolean> {
        val all = headings.asSequence()
            .mapNotNull { (h, parentId) ->
                val pageId = if (pageIndexById.containsKey(parentId)) parentId else linkPageById[parentId] ?: return@mapNotNull null
                val pageIndex = pageIndexById[pageId] ?: return@mapNotNull null
                val label = HeadingPrefix.stripHeadingPrefix(h.text).trim()
                if (label.isEmpty()) return@mapNotNull null
                Item(h.id, pageId, pageIndex, h.x, h.y, label, h.level)
            }
            .sortedWith(DOCUMENT_ORDER)
            .toList()
        val truncated = all.size > MAX_ENTRIES
        return (if (truncated) all.subList(0, MAX_ENTRIES) else all) to truncated
    }
}
