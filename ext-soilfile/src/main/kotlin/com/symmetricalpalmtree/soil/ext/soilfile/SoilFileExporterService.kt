package com.symmetricalpalmtree.soil.ext.soilfile

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.soil.ext.ExportResult
import com.symmetricalpalmtree.soil.ext.ExportSpec
import com.symmetricalpalmtree.soil.ext.ExporterInfo
import com.symmetricalpalmtree.soil.ext.HostCallerCheck
import com.symmetricalpalmtree.soil.ext.IExporter
import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.ext.OptionDescriptor

/**
 * The exporter's door. Every call checks the caller first, inside the `try` whose `finally`
 * closes both descriptors; only SecurityException, IllegalArgumentException and
 * IllegalStateException leave the stub, anything else rethrown as an IllegalStateException
 * naming the class, never the cause's text.
 */
class SoilFileExporterService : Service() {

    private val binder = object : IExporter.Stub() {

        override fun describe(): ExporterInfo {
            enforce()
            return ExporterInfo(
                formatLabel = "Soil item (.soil)",
                fileExtension = "soil",
                mimeType = "application/octet-stream",
                options = listOf(
                    OptionDescriptor.choice(
                        ExportContract.OPTION_KEYING, "Encryption",
                        listOf(ExportContract.KEYING_KEEP, ExportContract.KEYING_REKEY, ExportContract.KEYING_PLAIN),
                        listOf("Keep encrypted (this device's key)", "New passphrase…", "Remove encryption"),
                        ExportContract.KEYING_KEEP,
                    ),
                ),
            )
        }

        override fun export(source: ParcelFileDescriptor?, destination: ParcelFileDescriptor?, spec: ExportSpec?): ExportResult {
            try {
                enforce()
                val src = source ?: throw IllegalArgumentException("no source descriptor")
                val dst = destination ?: throw IllegalArgumentException("no destination descriptor")
                val asked = spec ?: throw IllegalArgumentException("no export spec")
                SoilFileExportSpec.keying(asked.values)
                // Soil keyed the file before it arrived; the copy is verbatim.
                return ExportResult(Streams.copy(src, dst, TAG, "export"))
            } catch (e: SecurityException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "export failed: ${e.javaClass.simpleName}")
                throw IllegalStateException("export failed (${e.javaClass.simpleName})")
            } finally {
                runCatching { source?.close() }
                runCatching { destination?.close() }
            }
        }
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.SOIL_PACKAGE)

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "SoilFileExporter"
    }
}
