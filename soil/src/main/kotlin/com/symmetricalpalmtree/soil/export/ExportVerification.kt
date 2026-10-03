package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract

/** Whether an export can be called done: a verbatim stream must match its source byte for byte,
 *  a transform must at least match what the destination says it holds. */
object ExportVerification {

    enum class Verdict { OK, SHORT, UNCONFIRMED }

    fun verdict(sourceKind: Int, bytesWritten: Long, streamBytes: Long, destinationSizes: List<Long>): Verdict = when (sourceKind) {
        ExportContract.SOURCE_FILE -> when {
            bytesWritten != streamBytes -> Verdict.SHORT
            destinationSizes.isNotEmpty() && destinationSizes.none { it == streamBytes } -> Verdict.UNCONFIRMED
            else -> Verdict.OK
        }
        ExportContract.SOURCE_PAGES -> when {
            bytesWritten <= 0L -> Verdict.SHORT
            destinationSizes.isNotEmpty() && destinationSizes.none { it == bytesWritten } -> Verdict.UNCONFIRMED
            else -> Verdict.OK
        }
        else -> Verdict.SHORT
    }
}
