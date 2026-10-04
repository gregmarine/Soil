package com.symmetricalpalmtree.soil.tags

/** The Manage overview as data: what the list holds and in what order, and what a bare row says. */
object TagManage {

    /** One row of the overview: a thing tags hang on. [pageId] null is the item's own row. */
    class Row(val itemId: String, val pageId: String?, val label: String)

    /** The item first, then one row per page, in the index's page order. */
    fun targets(itemId: String, itemLabel: String, pageIds: List<String>, pageLabels: List<String>): List<Row> {
        val rows = ArrayList<Row>(pageIds.size + 1)
        rows += Row(itemId, null, itemLabel)
        for (i in 0 until minOf(pageIds.size, pageLabels.size)) rows += Row(itemId, pageIds[i], pageLabels[i])
        return rows
    }

    /** A row's second line: the tags it carries in the order given, or [none]. Words, not a dash. */
    fun summary(tags: List<String>, none: String, separator: String): String =
        if (tags.isEmpty()) none else tags.joinToString(separator)
}
