package com.symmetricalpalmtree.soil.importing

/**
 * **The download's corroboration.** What the provider says it wrote and what the file holds are
 * both first-hand: a disagreement is a truncated stream ([Verdict.SHORT]). The listing's size is
 * corroboration, never authority: claiming more than landed cannot be a lag and is SHORT;
 * claiming less is logged ([Verdict.DISAGREE]) and the import goes on, since the probe and the
 * keying answer for what the bytes are. A listing that gave no size contradicts nothing.
 */
object CloudImportRules {

    enum class Verdict { OK, SHORT, DISAGREE }

    fun downloadVerdict(reported: Long, landed: Long, listed: Long): Verdict = when {
        reported != landed -> Verdict.SHORT
        listed > landed -> Verdict.SHORT
        listed >= 0 && listed != landed -> Verdict.DISAGREE
        else -> Verdict.OK
    }
}

/** A cloud step of the import failed, and nothing was imported. Its own type because one of the four offers Connect. */
class CloudImportFailure(val kind: Kind, cause: Throwable? = null) : Exception(cause) {
    enum class Kind { GONE, NOT_CONNECTED, NETWORK, UNANSWERED }
}
