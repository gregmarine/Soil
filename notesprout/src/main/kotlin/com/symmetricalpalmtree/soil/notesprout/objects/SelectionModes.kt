package com.symmetricalpalmtree.soil.notesprout.objects

/** What a lasso holds, as the bar reads it. */
enum class SelectionMode { STROKES, HEADING, TEXT, SHAPE, STICKY, LINK, MIXED, MIXED_WITH_LINK }

/**
 * What the lasso caught, decided: exactly one object and no ink is that object's lone mode; a
 * link anywhere else makes the set [SelectionMode.MIXED_WITH_LINK] (a link is never nested);
 * ink alone is [SelectionMode.STROKES]; anything else [SelectionMode.MIXED]. Pure.
 */
object SelectionModes {
    fun classify(
        strokeCount: Int,
        contentIds: Collection<String>,
        isHeading: (String) -> Boolean,
        isLink: (String) -> Boolean,
        isText: (String) -> Boolean,
        isShape: (String) -> Boolean,
        isSticky: (String) -> Boolean = { false },
    ): SelectionMode {
        val lone = if (strokeCount == 0 && contentIds.size == 1) contentIds.first() else null
        val hasLink = contentIds.any(isLink)
        return when {
            lone != null && isHeading(lone) -> SelectionMode.HEADING
            lone != null && isText(lone) -> SelectionMode.TEXT
            lone != null && isShape(lone) -> SelectionMode.SHAPE
            lone != null && isSticky(lone) -> SelectionMode.STICKY
            lone != null && hasLink -> SelectionMode.LINK
            hasLink -> SelectionMode.MIXED_WITH_LINK
            contentIds.isEmpty() && strokeCount > 0 -> SelectionMode.STROKES
            else -> SelectionMode.MIXED
        }
    }
}
