package com.symmetricalpalmtree.soil.ext

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Soil's side of one exporter: describe, one export, or a held bind for a per-page loop. */
class ExporterClient(private val context: Context, private val extension: Extension) {

    suspend fun describe(): ExporterInfo = withContext(Dispatchers.IO) {
        ExtensionBinder.once(context, ExportContract.ACTION_EXPORTER, extension.component, ExportContract.DESCRIBE_TIMEOUT_MS) { IExporter.Stub.asInterface(it).describe() }
    }

    /** Both descriptors are closed here, success or not; the extension closes its own copies. */
    suspend fun export(source: ParcelFileDescriptor, destination: ParcelFileDescriptor, spec: ExportSpec): ExportResult = withContext(Dispatchers.IO) {
        try {
            ExtensionBinder.once(context, ExportContract.ACTION_EXPORTER, extension.component, ExportContract.EXPORT_TIMEOUT_MS) { IExporter.Stub.asInterface(it).export(source, destination, spec) }
        } finally {
            runCatching { source.close() }
            runCatching { destination.close() }
        }
    }

    /** One bind for many calls. */
    suspend fun hold(): Held = withContext(Dispatchers.IO) { Held(ExtensionBinder.bind(context, ExportContract.ACTION_EXPORTER, extension.component)) }

    class Held(private val bound: ExtensionBinder) {
        suspend fun export(source: ParcelFileDescriptor, destination: ParcelFileDescriptor, spec: ExportSpec): ExportResult = withContext(Dispatchers.IO) {
            try {
                bound.call(ExportContract.EXPORT_TIMEOUT_MS) { IExporter.Stub.asInterface(bound.binder).export(source, destination, spec) }
            } finally {
                runCatching { source.close() }
                runCatching { destination.close() }
            }
        }
        suspend fun close() = withContext(Dispatchers.IO) { bound.close() }
    }
}
