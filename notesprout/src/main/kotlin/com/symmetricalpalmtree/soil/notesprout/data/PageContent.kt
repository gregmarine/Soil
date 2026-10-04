package com.symmetricalpalmtree.soil.notesprout.data

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import com.symmetricalpalmtree.soil.notesprout.objects.PageSticky
import com.symmetricalpalmtree.soil.notesprout.objects.PageText

/** One page as it is stored: its ink with the order each stroke holds, and everything placed among it. */
class PageContent(
    val strokes: List<Pair<Long, Stroke>>,
    val headings: List<Heading> = emptyList(),
    val texts: List<PageText> = emptyList(),
    val stickies: List<PageSticky> = emptyList(),
    /** The page's links in z-order, each with what it wraps. */
    val links: List<PageLink> = emptyList(),
) {
    companion object {
        val EMPTY = PageContent(emptyList())
    }
}

/** What a delete took, by kind: ids for the kinds whose rows revive in place, and whole sticky
 *  notes and links with their content, because their children are revived by id from the
 *  snapshot (a wrapped sticky's content included). */
class DeletedObjects(
    val headingIds: List<String> = emptyList(),
    val textIds: List<String> = emptyList(),
    val stickies: List<PageSticky> = emptyList(),
    val links: List<PageLink> = emptyList(),
) {
    val isEmpty: Boolean get() = headingIds.isEmpty() && textIds.isEmpty() && stickies.isEmpty() && links.isEmpty()
    val ids: List<String> get() = headingIds + textIds + stickies.map { it.id } + stickies.flatMap { it.childIds } +
        links.map { it.id } + links.flatMap { it.childIds } + links.flatMap { l -> l.stickies.flatMap { it.childIds } }
}
