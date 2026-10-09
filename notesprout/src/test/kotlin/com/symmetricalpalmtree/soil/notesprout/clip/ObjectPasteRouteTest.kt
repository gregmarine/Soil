package com.symmetricalpalmtree.soil.notesprout.clip

import org.junit.Assert.assertEquals
import org.junit.Test

class ObjectPasteRouteTest {

    private fun header(kind: String, at: Long) = ClipHeader(kind, "nb", at)

    @Test fun emptyBothIsNone() = assertEquals(ObjectPasteRoute.NONE, ObjectPasteRoute.of(null, null))

    @Test fun objectsAlone() = assertEquals(ObjectPasteRoute.OBJECTS, ObjectPasteRoute.of(header(ClipEnvelope.KIND_OBJECTS, 5), null))

    @Test fun bibleAlone() = assertEquals(ObjectPasteRoute.BIBLE, ObjectPasteRoute.of(null, 5))

    @Test fun newerWins() {
        assertEquals(ObjectPasteRoute.BIBLE, ObjectPasteRoute.of(header(ClipEnvelope.KIND_OBJECTS, 5), 6))
        assertEquals(ObjectPasteRoute.OBJECTS, ObjectPasteRoute.of(header(ClipEnvelope.KIND_OBJECTS, 7), 6))
    }

    /** A page copied after the passage: the lasso cannot paste a page, so the passage pastes. */
    @Test fun pageNewerThanBibleStillPastesBible() =
        assertEquals(ObjectPasteRoute.BIBLE, ObjectPasteRoute.of(header(ClipEnvelope.KIND_PAGE, 9), 6))

    @Test fun pageAloneIsNone() = assertEquals(ObjectPasteRoute.NONE, ObjectPasteRoute.of(header(ClipEnvelope.KIND_PAGE, 9), null))
}
