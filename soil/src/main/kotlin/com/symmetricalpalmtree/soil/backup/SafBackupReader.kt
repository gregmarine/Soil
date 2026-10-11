package com.symmetricalpalmtree.soil.backup

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.InputStream

/**
 * The writer's read twin, for a restore: resolve the root, list a directory, open a stream. Read
 * only, and the grant is never persisted: persisting the tree would be setting a destination.
 */
class SafBackupReader(private val resolver: ContentResolver, private val treeUri: Uri) : BackupReader {

    override fun root(): Uri? = try {
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { if (it.moveToFirst()) rootUri else null }
    } catch (e: Exception) {
        Log.w(TAG, "source root did not resolve", e)
        null
    }

    override fun rootName(): String? = try {
        val rootUri = root()
        if (rootUri == null) null else resolver.query(rootUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** The children of [dirUri], or null when the listing failed. */
    override fun list(dirUri: Uri): List<BackupReader.Entry>? = try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(dirUri))
        val out = ArrayList<BackupReader.Entry>()
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(BackupReader.Entry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)),
                    name = c.getString(1) ?: continue,
                    size = if (c.isNull(2)) -1L else c.getLong(2),
                    isDir = c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                ))
            }
            out
        }
    } catch (e: Exception) {
        Log.w(TAG, "source listing failed", e)
        null
    }

    override fun open(uri: Uri): InputStream? = try {
        resolver.openInputStream(uri)
    } catch (e: Exception) {
        Log.w(TAG, "source file did not open", e)
        null
    }

    private companion object { const val TAG = "SafBackupReader" }
}
