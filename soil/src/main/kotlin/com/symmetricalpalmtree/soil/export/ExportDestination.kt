package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.CloudContract
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.CloudStatus

/**
 * **Where an export goes**: the Destination row's decision core, pure. The row asks one question,
 * this device or the cloud, and exists only when a provider is installed: GONE otherwise, never
 * disabled. A standing cloud answer is forced back to local whenever the row is not on screen,
 * so a provider uninstalled under a standing screen never leaves an export aimed at a cloud that
 * is no longer there. A tap on the cloud radio is judged by the last status: a build with no
 * credentials says so first; no account, or no answer, is the Connect offer; connected selects.
 *
 * **Remembered** (2026-10-10): the last destination opens the next screen, the cloud only while
 * the account is connected (the memory itself is kept, so a reconnected account finds it again);
 * and the cloud folder, one per kind of item, shown on a row under the cloud radio and used as
 * it stands. With nothing remembered the folder is `Exports` itself.
 */
object ExportDestination {

    enum class Choice { LOCAL, CLOUD }

    enum class Tap { SELECT, NOT_CONFIGURED, OFFER_CONNECT }

    fun rowVisible(providerInstalled: Boolean): Boolean = providerInstalled

    /** The local Folder row (Greg, 2026-10-10): shown while this device is the choice and Soil browses it itself. */
    fun localFolderRowVisible(choice: Choice, browsesLocally: Boolean): Boolean = choice == Choice.LOCAL && browsesLocally

    fun settled(choice: Choice, rowVisible: Boolean): Choice = if (rowVisible) choice else Choice.LOCAL

    fun onCloudTap(status: CloudStatus?): Tap = when {
        status == null -> Tap.OFFER_CONNECT
        !status.configured -> Tap.NOT_CONFIGURED
        !status.connected -> Tap.OFFER_CONNECT
        else -> Tap.SELECT
    }

    /** What every cloud sentence calls the provider: the name it gave, or the extension's label. */
    fun providerName(status: CloudStatus?, extensionLabel: String): String =
        status?.providerName?.takeIf { it.isNotBlank() } ?: extensionLabel

    /** The folder under the provider's root that exports go to, and the browser's floor. */
    const val EXPORTS_FOLDER = "Exports"

    /** The folder every kind starts at until one is picked. */
    val DEFAULT_FOLDER: List<String> = listOf(EXPORTS_FOLDER)

    /**
     * The choice the screen opens on: the remembered one, except that the cloud is only opened
     * on while the account is connected. The memory is not this function's to change.
     */
    fun opening(rememberedCloud: Boolean, status: CloudStatus?): Choice =
        if (rememberedCloud && status?.connected == true) Choice.CLOUD else Choice.LOCAL

    /** The stored form of a folder path: its names joined by a slash, which no name can hold. */
    fun encodeFolder(path: List<String>): String = path.joinToString(FOLDER_SEPARATOR)

    /**
     * The folder a kind remembers, or the default. A stored value that is not a path under
     * `Exports/` (damaged, or from a build that spelt it differently) falls back to the default.
     */
    fun decodeFolder(stored: String?): List<String> {
        if (stored.isNullOrEmpty()) return DEFAULT_FOLDER
        val path = stored.split(FOLDER_SEPARATOR)
        if (path.first() != EXPORTS_FOLDER) return DEFAULT_FOLDER
        if (path.size > CloudContract.MAX_PATH_DEPTH) return DEFAULT_FOLDER
        if (path.any { !CloudContract.isName(it) }) return DEFAULT_FOLDER
        return path
    }

    /** The row's text: the whole path, `Exports › Notes › 2026`. */
    fun folderLabel(path: List<String>, separator: String): String = path.joinToString(separator)

    /** `Exports` itself is made on the way by every upload; only a deeper folder can be missing. */
    fun folderNeedsCheck(path: List<String>): Boolean = path.size > DEFAULT_FOLDER.size

    /** Whether the remembered folder is still where it was, judged on a listing of its parent. */
    fun folderStillThere(path: List<String>, parentListing: List<CloudEntry>): Boolean {
        val name = path.lastOrNull() ?: return true
        return parentListing.any { it.isFolder && it.name == name }
    }

    private const val FOLDER_SEPARATOR = "/"
}
