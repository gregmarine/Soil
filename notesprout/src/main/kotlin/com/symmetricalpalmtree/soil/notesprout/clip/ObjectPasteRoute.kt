package com.symmetricalpalmtree.soil.notesprout.clip

/**
 * Which slot a lasso paste reads: the notebook kind's (objects) or the `bible` kind's. The two
 * slots are separate; a paste reads one and never retires the other. Pure.
 */
enum class ObjectPasteRoute {
    BIBLE, OBJECTS, NONE;

    companion object {
        /**
         * The newer of the two when both can paste here; the Bible's when the notebook slot holds
         * something that is not objects (a page); nothing when neither can.
         */
        fun of(header: ClipHeader?, bibleCopiedAt: Long?): ObjectPasteRoute {
            val objects = header?.kind == ClipEnvelope.KIND_OBJECTS
            return when {
                bibleCopiedAt != null && (!objects || bibleCopiedAt > header!!.copiedAt) -> BIBLE
                objects -> OBJECTS
                else -> NONE
            }
        }
    }
}
