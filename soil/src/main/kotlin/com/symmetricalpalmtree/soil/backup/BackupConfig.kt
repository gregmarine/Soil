package com.symmetricalpalmtree.soil.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything backup remembers, as one JSON value in the index's `meta` table ([BackupStore]).
 *
 * The stamp maps: an item needs copying when it has no stamp or its `updatedAt` is newer than
 * its stamp. A stamp is written per successful copy, with the `updatedAt` the work list read,
 * never the clock, so an edit landing mid-run is never masked. A stamp is a statement about one
 * destination: the cloud has its own map. [decode] never throws: a corrupt value reads as a fresh
 * config, whose worst case is copying everything again.
 */
@Serializable
data class BackupConfig(
    val version: Int = VERSION,
    /** The persisted SAF tree, or null while no folder has been chosen (or the folder is [localDir]). */
    val treeUri: String? = null,
    /** A folder of the shared storage by path, chosen through Soil's own browser (2026-10-10); exactly one of this and [treeUri] stands. */
    val localDir: String? = null,
    val lastRunAt: Long? = null,
    val lastCopied: Int? = null,
    val lastSkipped: Int? = null,
    /** itemId → the `updatedAt` its last successful copy carried. */
    val stamps: Map<String, Long> = emptyMap(),
    /** Whether "Back up now" also uploads to the connected provider. */
    val cloudEnabled: Boolean = false,
    /** This device's folder under `Backups/` in the provider's tree; typed by the person at first use. */
    val cloudDeviceFolder: String? = null,
    val cloudStamps: Map<String, Long> = emptyMap(),
    val cloudLastRunAt: Long? = null,
    val cloudLastCopied: Int? = null,
    val cloudLastSkipped: Int? = null,
) {
    companion object {
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(config: BackupConfig): String? = try { json.encodeToString(serializer(), config) } catch (_: Exception) { null }

        fun decode(text: String?): BackupConfig {
            if (text.isNullOrEmpty()) return BackupConfig()
            val config = try { json.decodeFromString(serializer(), text) } catch (_: Exception) { return BackupConfig() }
            return if (config.version in 1..VERSION) config else BackupConfig()
        }
    }
}
