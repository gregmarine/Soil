package com.symmetricalpalmtree.soil.templates

import com.symmetricalpalmtree.soil.data.index.TemplateRow
import com.symmetricalpalmtree.soil.paper.core.FuzzyRank
import com.symmetricalpalmtree.soil.paper.templates.TemplateFit
import com.symmetricalpalmtree.soil.paper.templates.TemplateIds
import com.symmetricalpalmtree.soil.paper.templates.TemplateKind

/**
 * What a card on the Templates screen stands for. Two kinds and no third: a **built-in** is paper
 * drawn from arithmetic, a **static** template is a picture the library keeps. [Blank] is the
 * absence of paper, [Defaults] the one reserved folder, [Folder] a place. The first three carry
 * sentinel ids and no row; only [Folder] and [Static] are rows.
 */
sealed class TemplateCard(val id: String, val name: String) {
    class Blank(name: String) : TemplateCard(TemplateIds.BLANK, name)
    class Defaults(name: String) : TemplateCard(TemplateIds.DEFAULT_FOLDER, name)
    class BuiltIn(id: String, name: String, val kind: TemplateKind) : TemplateCard(id, name)
    class Folder(val row: TemplateRow) : TemplateCard(row.id, row.name)
    class Static(val row: TemplateRow) : TemplateCard(row.id, row.name) {
        val fit: Int get() = TemplateFit.sanitize(row.fit)
    }

    val isSentinel: Boolean get() = TemplateIds.isSentinel(id)

    /** The clock a thumbnail cache keys on; a sentinel never changes. */
    val stamp: Long get() = when (this) {
        is Folder -> row.updatedAt
        is Static -> row.updatedAt
        else -> 0L
    }
}

/**
 * The library's rules, pure and JVM-tested: what the root is made of, what the Default folder
 * holds, what the three shelves show, and how rows are sorted. Labels come in as parameters: the
 * words are the screen's, the order is this file's.
 */
object TemplateLibrary {

    /** The root: Blank, then Default, then the rows, folders first, in the caller's sort. */
    fun rootCards(blankLabel: String, defaultLabel: String, sortedRows: List<TemplateRow>): List<TemplateCard> = buildList {
        add(TemplateCard.Blank(blankLabel))
        add(TemplateCard.Defaults(defaultLabel))
        addAll(rowCards(sortedRows))
    }

    /** Inside Default: the three built-ins and nothing else, ever. */
    fun defaultCards(builtInLabels: List<String>): List<TemplateCard> =
        TemplateIds.BUILT_INS.mapIndexed { i, (id, kind) -> TemplateCard.BuiltIn(id, builtInLabels[i], kind) }

    fun rowCards(sortedRows: List<TemplateRow>): List<TemplateCard> =
        sortedRows.map { if (it.isFolder) TemplateCard.Folder(it) else TemplateCard.Static(it) }

    /** Folders first, then templates; the chosen order within each group, names case-insensitively. */
    fun sorted(rows: List<TemplateRow>, field: SortField, order: SortOrder): List<TemplateRow> {
        val base: Comparator<TemplateRow> = when (field) {
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.MODIFIED -> compareBy { it.updatedAt }
        }
        val cmp = if (order == SortOrder.DESC) base.reversed() else base
        val (folders, rest) = rows.partition { it.isFolder }
        return folders.sortedWith(cmp) + rest.sortedWith(cmp)
    }

    // ── The shelves ──────

    /** The three built-ins by sentinel id. Blank and Default are not pinnable: one is already card #1, the other is a place. */
    val PINNABLE_SENTINELS: Set<String> = TemplateIds.BUILT_INS.map { it.first }.toSet()

    fun isPinnable(id: String): Boolean = id in PINNABLE_SENTINELS || !TemplateIds.isSentinel(id)

    /** The pinned shelf: the built-ins first in their fixed order, then the pinned rows in the caller's sort. */
    fun pinnedCards(pinnedIds: Set<String>, sortedRows: List<TemplateRow>, builtInLabels: List<String>): List<TemplateCard> = buildList {
        TemplateIds.BUILT_INS.forEachIndexed { i, (id, kind) -> if (id in pinnedIds) add(TemplateCard.BuiltIn(id, builtInLabels[i], kind)) }
        addAll(rowCards(sortedRows).filterIsInstance<TemplateCard.Static>())
    }

    /** The recents shelf in **stored order**: a sentinel is always alive, a row only while it is. */
    fun recentCards(recentIds: List<String>, alive: Map<String, TemplateRow>, builtInLabels: List<String>): List<TemplateCard> {
        val seen = HashSet<String>()
        return recentIds.mapNotNull { id ->
            if (!seen.add(id)) return@mapNotNull null
            val i = TemplateIds.BUILT_INS.indexOfFirst { it.first == id }
            if (i >= 0) TemplateCard.BuiltIn(id, builtInLabels[i], TemplateIds.BUILT_INS[i].second)
            else alive[id]?.let { TemplateCard.Static(it) }
        }
    }

    /** What a recents store may keep: every alive row plus every pinnable sentinel. */
    fun pruneable(aliveRowIds: Set<String>): Set<String> = aliveRowIds + PINNABLE_SENTINELS

    /** The ids that need a row read: everything that is not a built-in. */
    fun rowIdsAmong(ids: Collection<String>): List<String> = ids.filterNot { it in PINNABLE_SENTINELS }

    /** The search shelf: Blank, the built-ins and every template, ranked together. Folders never appear. */
    fun searchCards(query: String, blankLabel: String, builtInLabels: List<String>, templates: List<TemplateRow>): List<TemplateCard> {
        val candidates = ArrayList<TemplateCard>(templates.size + 4)
        candidates.add(TemplateCard.Blank(blankLabel))
        candidates.addAll(defaultCards(builtInLabels))
        candidates.addAll(templates.filter { !it.isFolder }.map { TemplateCard.Static(it) })
        return FuzzyRank.rank(candidates, query) { it.name }
    }
}
