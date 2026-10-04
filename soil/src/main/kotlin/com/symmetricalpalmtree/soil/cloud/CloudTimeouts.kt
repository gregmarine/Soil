package com.symmetricalpalmtree.soil.cloud

import com.symmetricalpalmtree.soil.ext.ExtensionBinder

/**
 * Soil's call budgets for the cloud point, one per method. A Binder call cannot be cancelled:
 * when a budget runs out Soil resumes with a failure while the transaction keeps running in the
 * provider's process, so a timeout undoes nothing and a generous budget is safer than a tight
 * one. Every row carries its Nomad measurement (Notesprout SN, 2026-09-04, home Wi-Fi); a budget
 * is 5–30× its measurement, the margin for a slow link. The store's open is never inside any of
 * these: it happens on IO before the bind.
 */
object CloudTimeouts {

    const val BIND_MS: Long = ExtensionBinder.BIND_TIMEOUT_MS

    /** Measured 772 ms cold / 51 ms warm. Never touches the network. */
    const val STATUS_MS: Long = 4_000L

    /** Measured ≈ 160 ms: one revoke round trip and a store write. Idempotent, so a timeout is recoverable. */
    const val DISCONNECT_MS: Long = 15_000L

    /** Measured 530 / 805 / 1 056 ms at depth 0 / 1 / 2: ≈ 270 ms per hop plus one listing. */
    const val LIST_MS: Long = 20_000L

    /** Measured 3 981 ms for two segments created under a fresh root; ≈ 7 s at the depth cap. */
    const val ENSURE_FOLDER_MS: Long = 30_000L

    /** Upload at or under 5 MiB, one multipart request. Measured 2 901 ms for 1 MiB. */
    const val UPLOAD_SMALL_MS: Long = 60_000L

    /** Upload over 5 MiB, resumable: a rate per 20 MiB slice. Measured 6 435 ms for 20 MiB. */
    const val UPLOAD_LARGE_MS: Long = 120_000L

    /** One metadata fetch and the stream. Measured 4 343 ms for 20 MiB. Flat: an import reads one file. */
    const val DOWNLOAD_MS: Long = 120_000L

    /** Measured 729 ms for one file. */
    const val DELETE_MS: Long = 15_000L

    const val UPLOAD_SMALL_LIMIT_BYTES: Long = 5L * 1024 * 1024
    const val UPLOAD_LARGE_UNIT_BYTES: Long = 20L * 1024 * 1024

    /** The one budget a caller computes: flat under the small limit, a slice rate above it, rounded up. */
    fun uploadBudgetMs(bytes: Long): Long {
        if (bytes <= UPLOAD_SMALL_LIMIT_BYTES) return UPLOAD_SMALL_MS
        val slices = (bytes + UPLOAD_LARGE_UNIT_BYTES - 1) / UPLOAD_LARGE_UNIT_BYTES
        return UPLOAD_LARGE_MS * slices
    }

    /** The download twin, for a caller pulling files of any size (a restore): flat per 20 MiB slice. */
    fun downloadBudgetMs(bytes: Long): Long {
        if (bytes <= UPLOAD_LARGE_UNIT_BYTES) return DOWNLOAD_MS
        val slices = (bytes + UPLOAD_LARGE_UNIT_BYTES - 1) / UPLOAD_LARGE_UNIT_BYTES
        return DOWNLOAD_MS * slices
    }
}
