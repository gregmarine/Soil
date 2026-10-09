package com.symmetricalpalmtree.soil.sketchsprout.raster

/**
 * What the lossless encoder is told to spend on a page, chosen by how much of the page is
 * marked — measured on the Nomad, 2026-10-08, against a sparse page and one shaded edge to edge:
 *
 * | Page   | Effort 100      | Effort 0        |
 * |--------|-----------------|-----------------|
 * | Sparse | 108 KB, 0.6 s   | 181 KB, 0.4 s   |
 * | Dense  | 3.25 MB, 54 s   | 3.43 MB, 1.4 s  |
 *
 * The top of the dial, SN's choice, buys 40 % on a sparse page for nothing and 5 % on a dense one
 * for a minute — a minute that Back waits for, since the page is flushed before the screen leaves.
 * So the effort is a function of coverage: a page under [DENSE_FRACTION] covered keeps [FULL], and
 * anything denser takes [FAST]. The image is the same whichever is chosen; only the search differs.
 *
 * Pure: the coverage is counted by the caller (a sampled walk over the raster, Android's).
 */
object RasterEffort {

    /** The top of the dial: the smallest file, and the slowest search. */
    const val FULL: Int = 100

    /** The bottom: the fastest encode, a few per cent larger. */
    const val FAST: Int = 0

    /** The share of the page marked above which a page counts as dense. A tenth: at a tenth the
     *  full search is already seconds, and the gain is already small. */
    const val DENSE_FRACTION: Double = 0.10

    /** The effort for a page with [coverage] of its pixels marked (0.0 … 1.0). */
    fun choose(coverage: Double): Int = if (coverage > DENSE_FRACTION) FAST else FULL
}
