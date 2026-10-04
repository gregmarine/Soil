package com.symmetricalpalmtree.soil.seam

/** What the seam will carry, and no more. Checked on both sides: an app learns of a refusal
 *  before it sends, and Soil refuses again whatever it is sent. */
object SeamLimits {
    /** One statement's SQL, in characters. */
    const val MAX_SQL_CHARS = 8_192

    /** SQLite's own ceiling on the binds of one statement. */
    const val MAX_ARGS = 999

    /** Statements in one `exec`, which is one transaction. */
    const val MAX_BATCH_STATEMENTS = 10_000

    /**
     * One value, in bytes. **Refused on write**: a blob larger than the cursor window can be
     * written and then never read back, so it is stopped on its way in.
     */
    const val MAX_VALUE_BYTES = 6 * 1024 * 1024

    /** One whole payload, in bytes: a batch of statements going in, or a result coming out. */
    const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024

    const val MAX_SCHEMA_STEPS = 64
    const val MAX_STEP_STATEMENTS = 32
    const val MAX_PURGE_STATEMENTS = 8

    /** An item's name, in characters. */
    const val MAX_NAME_CHARS = 200

    /** What a query that would answer more than [MAX_PAYLOAD_BYTES] is refused with. */
    const val RESULT_TOO_LARGE = "the result is too large to carry"

    /** What a value over [MAX_VALUE_BYTES] is refused with. */
    const val VALUE_TOO_LARGE = "a value is too large to store"

    /** What every storage call is refused with while Soil does not hold the key, the recovery key
     *  has not been saved, or a passphrase change is unfinished. The person puts it right in Soil. */
    const val LIBRARY_NOT_OPEN = "the library is not open"

    /** What a call on a session that Soil has ended is refused with. */
    const val SESSION_ENDED = "the session has ended"

    /** What a tag assign is refused with when the library holds as many tags, or as many
     *  assignments, as it may. Compared verbatim. */
    const val TAGS_FULL = TagRules.TAGS_FULL

    /** A staged text, in characters. */
    const val MAX_STAGED_TEXT_CHARS = 1_000

    /** What a recognition call is refused with while no recogniser is installed or chosen. */
    const val NO_RECOGNIZER = "no recogniser"

    /** What a recognition call is refused with while the model is not there yet. */
    const val RECOGNIZER_NOT_READY = "recognizer not ready"

    /** What a recognition call is refused with over the recogniser's caps. */
    const val INK_TOO_LARGE = "too much ink to recognise at once"

    /** What a recognition call is refused with when the recogniser failed or did not answer. */
    const val RECOGNITION_FAILED = "recognition failed"

    /** What a file written by a later build than the app's schema is refused with. */
    const val SCHEMA_NEWER = "the file is newer than this app"
}
