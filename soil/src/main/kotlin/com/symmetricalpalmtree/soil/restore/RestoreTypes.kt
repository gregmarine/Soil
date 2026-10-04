package com.symmetricalpalmtree.soil.restore

import java.io.File

/** Which kind of destination a backup is read back from. The one difference is the WAL rule:
 *  a local folder's `-wal` is that main file's missing writes and travels with it; a cloud
 *  folder never holds a live one (every upload was self-contained), so one found there is stale
 *  and is never taken. */
enum class RestoreLeg { LOCAL, CLOUD }

/** One directory entry as either leg lists it. */
data class Listed(val name: String, val size: Long, val isDir: Boolean, val modifiedAt: Long)

/** What a taken file is, which is also what the commit does with it. */
enum class ItemKind { INDEX, INDEX_WAL, SOIL, SOIL_WAL, STORE, STORE_WAL }

/** One file the restore will stage; [relativePath] mirrors the live layout under the staging root. */
data class Item(val name: String, val size: Long, val kind: ItemKind, val relativePath: String)

/** One backup a source can offer: enough to tell two apart without opening either. [handle] is source-private, never shown or logged. */
data class RestoreBackup(val name: String, val itemCount: Int, val indexModifiedAt: Long, val totalBytes: Long, val handle: String)

/** Why a listing or a fetch could not finish; every one is a message, never a throw. */
sealed class RestoreProblem {
    object SourceUnreachable : RestoreProblem()
    object ListingFailed : RestoreProblem()
    object NotABackup : RestoreProblem()
    /** [fileName] is an id or a store name: safe to show. */
    data class FetchFailed(val fileName: String) : RestoreProblem()
    object CloudNotConnected : RestoreProblem()
    object CloudNetwork : RestoreProblem()
    object CloudUnanswered : RestoreProblem()
    object CloudGone : RestoreProblem()
}

sealed class ListResult {
    data class Backups(val backups: List<RestoreBackup>) : ListResult()
    data class Failed(val problem: RestoreProblem) : ListResult()
}

sealed class FetchResult {
    data class Staged(val manifest: RestoreManifest) : FetchResult()
    data class Failed(val problem: RestoreProblem) : FetchResult()
}

/** Where a restore reads from: a picked folder, or the cloud. The engine knows neither. The manifest a fetch returns is re-planned from a fresh listing at fetch time. */
interface RestoreSource {
    suspend fun listBackups(): ListResult

    /** Stage every manifest item of [backup] under [staging]. Any single file failing fails the whole fetch. */
    suspend fun fetchInto(backup: RestoreBackup, staging: File, onProgress: (done: Int, total: Int) -> Unit): FetchResult
}
