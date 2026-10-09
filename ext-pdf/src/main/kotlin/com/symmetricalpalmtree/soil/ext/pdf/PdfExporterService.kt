package com.symmetricalpalmtree.soil.ext.pdf

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
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * The exporter's door. Every call checks the caller first, inside the `try` whose `finally`
 * closes both descriptors; only SecurityException, IllegalArgumentException and
 * IllegalStateException leave the stub, anything else rethrown as an IllegalStateException
 * naming the class, never the cause's text.
 */
class PdfExporterService : Service() {

    private val binder = object : IExporter.Stub() {

        override fun describe(): ExporterInfo {
            enforce()
            return PdfDescriptor.info()
        }

        override fun export(source: ParcelFileDescriptor?, destination: ParcelFileDescriptor?, spec: ExportSpec?): ExportResult {
            try {
                enforce()
                val src = source ?: throw IllegalArgumentException("no source descriptor")
                val dst = destination ?: throw IllegalArgumentException("no destination descriptor")
                val asked = spec ?: throw IllegalArgumentException("no export spec")
                PdfExportSpec.require(asked.values, asked.exportSecret)
                readyPdfbox()
                PdfAssembly.sweepScratch(cacheDir, TAG)
                return ExportResult(PdfAssembly.assemble(src, dst, asked.exportSecret, TAG, cacheDir, PdfExportSpec.pagePoints(asked.values)))
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

    private fun readyPdfbox() {
        if (pdfboxReady) return
        PDFBoxResourceLoader.init(applicationContext)
        pdfboxReady = true
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "PdfExporter"

        @Volatile
        var pdfboxReady = false
    }
}
