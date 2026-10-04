package com.symmetricalpalmtree.soil.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

/**
 * The local destination, written atomically over `DocumentsContract`: stream to `<name>.part`,
 * verify the landed size, move the previous copy to `<name>.old`, rename the part in, drop the
 * `.old`. A torn write never replaces a good backup; a `.old` standing alone is the last good copy
 * a crash stranded and is renamed back, never swept. Nothing here throws: every failure logs and
 * answers false or null. Content URIs are never logged; file names are ids and safe.
 */
class SafBackupWriter(private val resolver: ContentResolver, private val treeUri: Uri) {

    data class Entry(val uri: Uri, val name: String, val size: Long, val isDir: Boolean)

    /** The tree's root as a document URI, or null when the grant no longer resolves. */
    fun root(): Uri? = try {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { if (it.moveToFirst()) rootUri else null }
    } catch (e: Exception) {
        Log.w(TAG, "destination root did not resolve", e)
        null
    }

    /** The children of [dirUri], or null when the listing itself failed; never "empty" for a failure. */
    fun list(dirUri: Uri): List<Entry>? = try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(dirUri))
        val out = ArrayList<Entry>()
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(Entry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                    name = c.getString(1) ?: continue,
                    size = if (c.isNull(2)) -1L else c.getLong(2),
                    isDir = c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR,
                ))
            }
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "destination listing failed", e)
        null
    }

    fun find(dirUri: Uri, name: String): Entry? = list(dirUri)?.firstOrNull { it.name == name }

    /** Find or create the [name] subdirectory of [dirUri]. */
    fun ensureDir(dirUri: Uri, name: String): Uri? {
        find(dirUri, name)?.let { return if (it.isDir) it.uri else null }
        return try {
            DocumentsContract.createDocument(resolver, dirUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
        } catch (e: Exception) {
            Log.w(TAG, "could not create subdirectory", e)
            null
        }
    }

    fun delete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (e: Exception) {
        Log.w(TAG, "delete failed", e)
        false
    }

    /** Write [source] into [dirUri] as [name], atomically. False leaves the previous copy in place. */
    fun writeAtomic(dirUri: Uri, name: String, source: File): Boolean {
        val partName = name + BackupPredicates.PART_SUFFIX
        val oldName = name + BackupPredicates.OLD_SUFFIX
        try {
            // One listing serves the whole write: the sweep, the crash recovery, the existing copy.
            val before = list(dirUri) ?: return false
            before.firstOrNull { it.name == partName }?.let { delete(it.uri) }
            var existing = before.firstOrNull { it.name == name }
            before.firstOrNull { it.name == oldName }?.let { staleOld ->
                if (existing == null) {
                    // A crash inside a previous swap: the `.old` is the only good copy.
                    val recovered = rename(staleOld.uri, name) ?: return false
                    existing = Entry(recovered, name, staleOld.size, isDir = false)
                } else {
                    delete(staleOld.uri)
                }
            }

            val part = DocumentsContract.createDocument(resolver, dirUri, OCTET_STREAM, partName) ?: return false
            var landed = -1L
            resolver.openOutputStream(part, "w").use { outStream ->
                if (outStream == null) return false
                source.inputStream().use { inStream -> landed = inStream.copyTo(outStream) }
                outStream.flush()
            }
            val expected = source.length()
            val reported = sizeOf(part)
            if (landed != expected || (reported >= 0 && reported != expected)) {
                Log.w(TAG, "short write ($landed streamed, $reported landed, $expected expected)")
                delete(part)
                return false
            }

            var oldUri: Uri? = null
            existing?.let {
                oldUri = rename(it.uri, oldName)
                if (oldUri == null) { delete(part); return false }
            }
            val finalUri = rename(part, name)
            if (finalUri == null) {
                oldUri?.let { rename(it, name) }
                return false
            }
            oldUri?.let { delete(it) }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "write failed for $name", e)
            return false
        }
    }

    private fun sizeOf(uri: Uri): Long = try {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun rename(uri: Uri, newName: String): Uri? = try {
        DocumentsContract.renameDocument(resolver, uri, newName)
    } catch (e: Exception) {
        Log.w(TAG, "rename failed", e)
        null
    }

    private companion object {
        const val TAG = "SafBackupWriter"
        const val OCTET_STREAM = "application/octet-stream"
    }
}
