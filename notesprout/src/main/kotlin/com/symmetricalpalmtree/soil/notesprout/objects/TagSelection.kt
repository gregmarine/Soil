package com.symmetricalpalmtree.soil.notesprout.objects

/** What tapping the selection bar's Tag does with what is lassoed. */
enum class TagFlow {
    /** Not offered: the button is absent for this selection. */
    NONE,

    /** Exactly one heading: its own text becomes a page tag, no screen, a toast. */
    SILENT,

    /** Ink alone: recognised, then the tag screen opens with the result to correct. */
    RECOGNIZE,
}

/**
 * The lasso's Tag, decided: exactly one heading is silent, ink alone is recognised, anything
 * else is not offered. A mixed selection has two answers (the heading's words, the ink's) and no
 * way to ask which is meant; a link is content with a payload, not ink; a text object is a
 * paragraph, not a title; a sticky carries no words.
 */
object TagSelection {

    fun flowFor(mode: SelectionMode): TagFlow = when (mode) {
        SelectionMode.HEADING -> TagFlow.SILENT
        SelectionMode.STROKES -> TagFlow.RECOGNIZE
        SelectionMode.TEXT, SelectionMode.STICKY, SelectionMode.LINK, SelectionMode.MIXED, SelectionMode.MIXED_WITH_LINK -> TagFlow.NONE
    }

    /** Whether the bar shows Tag: a flow to take, and the flow being one this build can take.
     *  Recognition is not here yet, so ink waits. */
    fun offered(mode: SelectionMode, recognitionAvailable: Boolean): Boolean = when (flowFor(mode)) {
        TagFlow.NONE -> false
        TagFlow.SILENT -> true
        TagFlow.RECOGNIZE -> recognitionAvailable
    }
}
