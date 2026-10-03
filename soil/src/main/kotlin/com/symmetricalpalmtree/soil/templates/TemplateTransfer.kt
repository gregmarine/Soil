package com.symmetricalpalmtree.soil.templates

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.TemplateRow
import com.symmetricalpalmtree.soil.data.index.TemplateStore
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.templates.BuiltInTemplates
import com.symmetricalpalmtree.soil.paper.templates.PagePaper
import com.symmetricalpalmtree.soil.paper.templates.TemplateFit
import com.symmetricalpalmtree.soil.paper.templates.TemplateImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * **Pictures in and pictures out**: the library's two doors to the rest of the device, through the
 * system file picker. Import decodes bounds-first, samples down, resizes to the page's long edge,
 * re-encodes, and refuses over the cap **before** asking the fit and the name: refusing after
 * would waste the only two decisions the person makes. Export is a PNG at this device's page size,
 * the same render the page gets. The one piece of state that outlives a call is the export's row
 * id: DocumentsUI is another process on a memory-tight device.
 */
class TemplateTransfer(
    private val activity: AppCompatActivity,
    private val store: () -> TemplateStore,
    private val currentFolder: () -> String,
    private val onChanged: () -> Unit,
) {
    private val pageWidthPx: Int
    private val pageHeightPx: Int
    private var pendingExportId: String? = null
    private var landingFolder: String = ""

    private val importLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) ingest(uri)
    }

    private val exportLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val id = pendingExportId
        pendingExportId = null
        if (result.resultCode == Activity.RESULT_OK && uri != null && id != null) write(id, uri)
    }

    init {
        val metrics = activity.resources.displayMetrics
        pageWidthPx = minOf(metrics.widthPixels, metrics.heightPixels)
        pageHeightPx = maxOf(metrics.widthPixels, metrics.heightPixels)
    }

    fun saveState(outState: Bundle) = outState.putString(KEY_PENDING_EXPORT, pendingExportId)
    fun restoreState(saved: Bundle?) { pendingExportId = saved?.getString(KEY_PENDING_EXPORT) }

    /** The landing folder is read at the tap: the picker is up from here, so nowhere else can be walked to. */
    fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, TemplateImport.MIME_TYPES)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            landingFolder = currentFolder()
            importLauncher.launch(intent)
        } catch (e: Exception) {
            Log.w(TAG, "no document picker: ${e.javaClass.simpleName}")
            Dialogs.problem(activity, R.string.template_import_no_picker_title, R.string.template_import_no_picker_body)
        }
    }

    private sealed class Loaded {
        class Ok(val bytes: ByteArray, val suggestedName: String) : Loaded()
        class TooBig(val bytes: Int) : Loaded()
        object Failed : Loaded()
    }

    private fun ingest(uri: Uri) {
        activity.lifecycleScope.launch {
            when (val loaded = withContext(Dispatchers.IO) { decodeAndEncode(uri) }) {
                is Loaded.Failed -> Dialogs.problem(activity, R.string.template_import_failed_title, R.string.template_import_failed_body)
                is Loaded.TooBig -> tooBig(loaded.bytes)
                is Loaded.Ok -> fitSheet(null) { fit -> askName(loaded, fit) }
            }
        }
    }

    private fun tooBig(bytes: Int) = Dialogs.problem(
        activity, R.string.template_import_too_big_title,
        activity.getString(R.string.template_import_too_big_body, TemplateImport.megabytes(bytes), TemplateImport.megabytes(TemplateImport.MAX_BLOB_BYTES)),
    )

    /** Two opens of the Uri rather than one slurp: the bounds pass needs a stream and so does the decode. */
    private fun decodeAndEncode(uri: Uri): Loaded {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            // The stream's absence is the failure, not the decode's return: a bounds pass answers
            // null by contract and fills outWidth/outHeight instead.
            val stream = open(uri) ?: return Loaded.Failed
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            Log.w(TAG, "import bounds failed: ${e.javaClass.simpleName}")
            return Loaded.Failed
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Loaded.Failed
        val maxEdge = pageHeightPx
        val opts = BitmapFactory.Options().apply { inSampleSize = TemplateImport.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
        val decoded = try {
            open(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            Log.w(TAG, "import decode failed: ${e.javaClass.simpleName}"); null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "import decode ran out of memory"); null
        } ?: return Loaded.Failed
        var source = decoded
        try {
            TemplateImport.scaledSize(decoded.width, decoded.height, maxEdge)?.let { (w, h) -> source = Bitmap.createScaledBitmap(decoded, w, h, true) }
            val bytes = BuiltInTemplates.toWebp(source)
            Slog.d(TAG) { "import ${bounds.outWidth}x${bounds.outHeight} → ${source.width}x${source.height}, ${bytes.size} bytes" }
            if (bytes.isEmpty()) return Loaded.Failed
            if (TemplateImport.overCap(bytes.size)) return Loaded.TooBig(bytes.size)
            return Loaded.Ok(bytes, TemplateImport.nameFrom(displayName(uri), activity.getString(R.string.template_import_default_name)))
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "import resize ran out of memory")
            return Loaded.Failed
        } finally {
            if (source !== decoded) source.recycle()
            decoded.recycle()
        }
    }

    private fun open(uri: Uri): InputStream? = activity.contentResolver.openInputStream(uri)

    private fun displayName(uri: Uri): String? = try {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun askName(loaded: Loaded.Ok, fit: Int) {
        val parentId = landingFolder
        var accepting = false
        NameDialog.show(activity, R.string.template_import_name_title, R.string.template_import_confirm, loaded.suggestedName, R.string.template_import_name_hint) { name, dismiss ->
            if (accepting) return@show
            if (NameDialog.reject(activity, name, parentId)) return@show
            accepting = true
            activity.lifecycleScope.launch {
                try {
                    val taken = withContext(Dispatchers.IO) { store().nameTaken(parentId, false, name) }
                    if (taken) {
                        Dialogs.problem(activity, R.string.name_problem_title, activity.getString(R.string.template_duplicate_name, name))
                        return@launch
                    }
                    withContext(Dispatchers.IO) { store().createTemplate(name, parentId, fit, loaded.bytes) }
                    dismiss()
                    onChanged()
                    Toast.makeText(activity, R.string.template_imported, Toast.LENGTH_SHORT).show()
                } finally {
                    accepting = false
                }
            }
        }
    }

    // ── Fit… ──────

    /** Re-fit a template already in the library: the picture never changes, only how it is laid on. */
    fun chooseFit(row: TemplateRow) = fitSheet(TemplateFit.sanitize(row.fit)) { fit ->
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { store().setFit(row.id, fit) }
            if (!ok) Dialogs.problem(activity, R.string.template_duplicate_gone_title, R.string.template_duplicate_gone_body)
            onChanged()
        }
    }

    private fun fitSheet(current: Int?, onPick: (Int) -> Unit) {
        val sheet = ActionSheetDialog(activity).title(activity.getString(R.string.template_fit_title))
        for (mode in TemplateFit.MODES) {
            sheet.addAction(if (mode == current) com.symmetricalpalmtree.soil.paper.R.drawable.ic_check else null, activity.getString(fitLabel(mode))) { onPick(mode) }
        }
        sheet.show()
    }

    private fun fitLabel(mode: Int): Int = when (mode) {
        TemplateFit.STRETCH -> R.string.template_fit_stretch
        TemplateFit.FILL -> R.string.template_fit_fill
        else -> R.string.template_fit_fit
    }

    // ── Export ──────

    fun export(row: TemplateRow) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("image/png")
            .putExtra(Intent.EXTRA_TITLE, "${row.name}.png")
        try {
            pendingExportId = row.id
            exportLauncher.launch(intent)
        } catch (e: Exception) {
            pendingExportId = null
            Log.w(TAG, "no document creator: ${e.javaClass.simpleName}")
            Dialogs.problem(activity, R.string.template_import_no_picker_title, R.string.template_import_no_picker_body)
        }
    }

    private fun write(id: String, uri: Uri) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { renderAndWrite(id, uri) }
            if (ok) Toast.makeText(activity, R.string.template_exported, Toast.LENGTH_SHORT).show()
            else Dialogs.problem(activity, R.string.template_export_failed_title, R.string.template_export_failed_body)
        }
    }

    private fun renderAndWrite(id: String, uri: Uri): Boolean {
        val row = runCatching { store().template(id) }.getOrNull() ?: return false
        val bytes = runCatching { store().image(id) }.getOrNull() ?: return false
        val page = PagePaper.renderImage(bytes, TemplateFit.sanitize(row.fit), pageWidthPx, pageHeightPx) ?: return false
        return try {
            activity.contentResolver.openOutputStream(uri)?.use { out ->
                page.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
                true
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "export write failed: ${e.javaClass.simpleName}")
            false
        } finally {
            page.recycle()
        }
    }

    private companion object {
        const val TAG = "TemplateTransfer"
        const val KEY_PENDING_EXPORT = "templateTransfer.pendingExport"
    }
}
