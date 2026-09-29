package com.symmetricalpalmtree.soil.data

/**
 * A database's schema as **steps**: step `n` is the statements that take a file from version
 * `n - 1` to version `n`, and the version is `PRAGMA user_version`. A released step is never
 * edited; a change is a new step on the end. Pure, so the ladder is JVM-tested.
 */
class Schema(val name: String, val steps: List<List<String>>) {

    init {
        require(steps.isNotEmpty()) { "$name has no steps" }
        require(steps.none { it.isEmpty() }) { "$name has an empty step" }
    }

    /** The version a file is at once every step has run. */
    val version: Int get() = steps.size

    /**
     * The steps a file at version [from] still needs, each with the version it lands on.
     * Empty when the file is current.
     *
     * @throws IllegalStateException for a file **newer** than this build knows. It was written by
     *   a later Soil; this one must not guess at it, and never rewrites it.
     */
    fun pending(from: Int): List<Pair<Int, List<String>>> {
        check(from >= 0) { "$name is at version $from" }
        check(from <= version) { "$name is at version $from, newer than this build's $version" }
        return (from until version).map { (it + 1) to steps[it] }
    }
}
