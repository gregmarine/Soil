package com.symmetricalpalmtree.soil.data

import android.content.Context
import com.symmetricalpalmtree.soil.crypto.RekeyNames
import java.io.File

/**
 * **The one path authority.** No other code constructs the path of a file Soil owns, and no other
 * code lists the garden.
 *
 * Everything lives in the app's external files folder, where adb can reach it on any build:
 *
 * ```
 * <external files>/soil.db              the library index
 * <external files>/garden/<id>.soil     one item file per notebook, sketchbook or document
 * <external files>/garden/<name>.db     one store per app that keeps its data in Soil
 * ```
 *
 * Every one of them is encrypted. The garden is flat: structure lives in the index.
 */
object SoilFiles {

    const val ITEM_SUFFIX = ".soil"
    const val STORE_SUFFIX = ".db"

    /** Soil's own store: the Scratch Pad's pages. */
    const val STORE_SCRATCHPAD = "scratchpad"

    /** A store name is a file stem and nothing else — never a path segment, never empty. */
    private val STORE_NAME = Regex("[a-zA-Z0-9_.]+")

    fun root(context: Context): File =
        context.getExternalFilesDir(null)
            ?: throw IllegalStateException("the device's storage is not available")

    /** The library index. */
    fun indexFile(context: Context): File = File(root(context), "soil.db")

    /** The one directory that holds every item file and every store. */
    fun gardenDir(context: Context): File = File(root(context), "garden")

    fun itemFile(context: Context, itemId: String): File = File(gardenDir(context), "$itemId$ITEM_SUFFIX")

    fun isValidStoreName(name: String): Boolean =
        STORE_NAME.matches(name) && name != "." && name != ".."

    /** Throws on a name that fails [isValidStoreName]; a `..` or a `/` would escape the directory. */
    fun storeFile(context: Context, name: String): File {
        require(isValidStoreName(name)) { "not a valid store name" }
        return File(gardenDir(context), "$name$STORE_SUFFIX")
    }

    /**
     * The store a garden entry names, or null when it names something else — pure, so the rule is
     * JVM-testable. Only a store ends in [STORE_SUFFIX]: an item is `<id>.soil` and every sidecar
     * and re-key leftover carries its own suffix past the `.db`.
     */
    fun storeName(fileName: String): String? {
        if (!fileName.endsWith(STORE_SUFFIX)) return null
        return fileName.dropLast(STORE_SUFFIX.length).takeIf(::isValidStoreName)
    }

    /** Every store on the device, by name, in a stable order. A store has no index row to be
     *  listed from: the file is the only record that it exists. */
    fun storeNames(context: Context): List<String> =
        (gardenDir(context).list() ?: emptyArray()).mapNotNull(::storeName).sorted()

    /** Every garden original that has a re-key leftover beside it — a `.rekey.tmp` or a
     *  `.old.bak`. The naming rule is [RekeyNames.leftoverOriginals]. */
    fun rekeyLeftovers(context: Context): List<File> {
        val garden = gardenDir(context)
        val names = garden.list()?.toList() ?: return emptyList()
        return RekeyNames.leftoverOriginals(names).map { File(garden, it) }
    }
}
