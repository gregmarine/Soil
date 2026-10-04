package com.symmetricalpalmtree.soil.ext

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Soil's side of one importer: describe, and one delivery into a cache file of Soil's. */
class ImporterClient(private val context: Context, private val extension: Extension) {

    suspend fun describe(): ImporterInfo = withContext(Dispatchers.IO) {
        ExtensionBinder.once(context, ExportContract.ACTION_IMPORTER, extension.component, ExportContract.DESCRIBE_TIMEOUT_MS) { IImporter.Stub.asInterface(it).describe() }
    }

    suspend fun importDocument(source: ParcelFileDescriptor, destination: ParcelFileDescriptor, spec: ImportSpec): ImportResult = withContext(Dispatchers.IO) {
        try {
            ExtensionBinder.once(context, ExportContract.ACTION_IMPORTER, extension.component, ExportContract.IMPORT_TIMEOUT_MS) { IImporter.Stub.asInterface(it).importDocument(source, destination, spec) }
        } finally {
            runCatching { source.close() }
            runCatching { destination.close() }
        }
    }
}
