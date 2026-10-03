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

    /**
     * The cloud leg's one question: does the provider's account of what it now holds agree with
     * what was sent? Corroboration, never authority: a provider's metadata can lag its own write,
     * so a disagreement is "check the file" and never a delete. No SHORT here: the provider
     * refuses a stream that does not match its expected length, and that arrives as a failure.
     */
    fun cloudVerdict(reportedBytes: Long, uploadedBytes: Long): Verdict = if (reportedBytes == uploadedBytes) Verdict.OK else Verdict.UNCONFIRMED
}
