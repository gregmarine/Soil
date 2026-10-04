package com.symmetricalpalmtree.soil.backup

import com.symmetricalpalmtree.soil.ext.CloudEntry

/**
 * The two-leg run's rules, pure: which legs a run has, what the progress counts, how a listing
 * answers the stale-sidecar question, what ends a leg, and which blocks the report draws.
 */
object CloudBackupRules {

    data class Legs(val local: Boolean, val cloud: Boolean) {
        val none: Boolean get() = !local && !cloud
    }

    /** The cloud leg needs the tick, a provider installed now, and a device folder named. */
    fun legs(hasFolder: Boolean, cloudEnabled: Boolean, hasProvider: Boolean, hasDeviceFolder: Boolean = true): Legs =
        Legs(local = hasFolder, cloud = cloudEnabled && hasProvider && hasDeviceFolder)

    /** One leg's units: every item it will visit, every store, and the index last. */
    fun units(itemsToCopy: Int, stores: Int): Int = itemsToCopy + stores + 1

    fun total(localUnits: Int, cloudUnits: Int): Int = localUnits + cloudUnits

    /** A file of exactly `<name>-wal` in the listing, never a folder. */
    fun staleSidecar(listing: List<CloudEntry>, mainName: String): CloudEntry? {
        val walName = mainName + BackupPredicates.WAL_SUFFIX
        return listing.firstOrNull { !it.isFolder && it.name == walName }
    }

    enum class Failure { NOT_CONNECTED, NETWORK, UNANSWERED, GONE }

    fun problemFor(failure: Failure): BackupEngine.Problem = when (failure) {
        Failure.NOT_CONNECTED -> BackupEngine.Problem.CLOUD_NOT_CONNECTED
        Failure.NETWORK -> BackupEngine.Problem.CLOUD_NETWORK
        Failure.UNANSWERED -> BackupEngine.Problem.CLOUD_UNANSWERED
        Failure.GONE -> BackupEngine.Problem.CLOUD_GONE
    }

    /** A leg stops where it stands on these, keeping every stamp earned. */
    fun endsLeg(problem: BackupEngine.Problem?): Boolean = when (problem) {
        BackupEngine.Problem.CLOUD_NOT_CONNECTED, BackupEngine.Problem.CLOUD_NETWORK,
        BackupEngine.Problem.CLOUD_UNANSWERED, BackupEngine.Problem.CLOUD_GONE -> true
        else -> false
    }

    fun legClean(result: BackupEngine.Result?): Boolean =
        result == null || (result.problem == null && result.failed == 0 && result.storesFailed == 0 && result.indexCopied)

    fun clean(outcome: BackupEngine.Outcome): Boolean = outcome.problem == null && legClean(outcome.local) && legClean(outcome.cloud)

    fun showsBlock(result: BackupEngine.Result?): Boolean = result != null
}
