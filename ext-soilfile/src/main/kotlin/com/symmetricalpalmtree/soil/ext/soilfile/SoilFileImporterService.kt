package com.symmetricalpalmtree.soil.ext.soilfile

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.symmetricalpalmtree.soil.ext.HostCallerCheck
import com.symmetricalpalmtree.soil.ext.IImporter
import com.symmetricalpalmtree.soil.ext.ImportResult
import com.symmetricalpalmtree.soil.ext.ImportSpec
import com.symmetricalpalmtree.soil.ext.ImporterInfo

/** The importer's door: the picked document's bytes, verbatim, into Soil's cache file. It never
 *  probes them; Soil does, after. */
class SoilFileImporterService : Service() {

    private val binder = object : IImporter.Stub() {

        override fun describe(): ImporterInfo {
            enforce()
            return ImporterInfo("Soil item (.soil)", listOf("soil"), listOf("application/octet-stream"))
        }

        override fun importDocument(source: ParcelFileDescriptor?, destination: ParcelFileDescriptor?, spec: ImportSpec?): ImportResult {
            try {
                enforce()
                val src = source ?: throw IllegalArgumentException("no source descriptor")
                val dst = destination ?: throw IllegalArgumentException("no destination descriptor")
                spec ?: throw IllegalArgumentException("no import spec")
                return ImportResult(Streams.copy(src, dst, TAG, "import"))
            } catch (e: SecurityException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "import failed: ${e.javaClass.simpleName}")
                throw IllegalStateException("import failed (${e.javaClass.simpleName})")
            } finally {
                runCatching { source?.close() }
                runCatching { destination?.close() }
            }
        }
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.SOIL_PACKAGE)

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "SoilFileImporter"
    }
}
