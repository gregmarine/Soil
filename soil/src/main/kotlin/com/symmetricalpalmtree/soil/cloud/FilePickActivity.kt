package com.symmetricalpalmtree.soil.cloud

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.databinding.ActivityFilePickBinding
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.importing.ImportOverlay
import com.symmetricalpalmtree.soil.importing.ImportSource
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.templates.TemplateImport
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * **A file for a Sprout app** ([Seam.ACTION_PICK_FILE], guarded by the seam permission and the
 * caller check): where the device's picker was, so the cloud can be offered beside it without
 * every app learning the cloud (cleanup, 2026-10-10). The screen asks *this device or the
 * provider* when one is installed, runs the device's picker or Soil's cloud browser, lands the
 * bytes in one folder of Soil's cache, and answers a Uri of Soil's own FileProvider with a read
 * grant, the file's name beside it. A device pick is copied rather than forwarded, so the answer
 * is one shape and good until the next pick clears the folder. Cancel anywhere is RESULT_CANCELED.
 */
class FilePickActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilePickBinding
    private lateinit var cloud: CloudFilePick
    private lateinit var mimes: Array<String>
    private var busy = false

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) copyThenAnswer(uri) else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (runCatching { SeamCallerCheck.enforceCaller(this, callingPackage) }.isFailure) { finish(); return }
        binding = ActivityFilePickBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        cloud = CloudFilePick(this)
        mimes = FilePickRules.mimeTypes(intent.getStringArrayExtra(Seam.EXTRA_MIME_TYPES))
        binding.title.setText(if (FilePickRules.imagesOnly(mimes)) R.string.file_pick_image_title else R.string.file_pick_title)
        binding.btnBack.setOnClickListener { finish() }
        // A recreated screen has its picker or browser's result on the way; the question is not asked twice.
        if (savedInstanceState == null) ask()
    }

    override fun onDestroy() {
        if (::cloud.isInitialized) cloud.close()
        super.onDestroy()
    }

    private fun ask() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            var handed = false
            try {
                withContext(Dispatchers.IO) { runCatching { folder().deleteRecursively() } }
                cloud.discover()
                if (isFinishing || isDestroyed) return@launch
                when (cloud.askSource(R.string.file_pick_source_title)) {
                    ImportSource.Source.LOCAL -> handed = launchPicker()
                    ImportSource.Source.CLOUD -> { handed = true; cloud.pickFile(onPicked = { ref, entry -> downloadThenAnswer(ref, entry) }, onGaveUp = { finish() }) }
                    null -> finish()
                }
            } finally {
                if (!handed) busy = false
            }
        }
    }

    private fun launchPicker(): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(FilePickRules.pickerType(mimes))
            .putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        return try {
            picker.launch(intent); true
        } catch (e: Exception) {
            Log.w(TAG, "no document picker: ${e.javaClass.simpleName}")
            Dialogs.problem(this, R.string.template_import_no_picker_title, R.string.template_import_no_picker_body)
            false
        }
    }

    /** The device's pick, streamed into the cache under the cap; its display name is the answer's. */
    private fun copyThenAnswer(uri: Uri) {
        lifecycleScope.launch {
            ImportOverlay.show(this@FilePickActivity, R.string.file_pick_copying)
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            val file = File(folder(), LANDED)
            val outcome = withContext(Dispatchers.IO) { copy(uri, file) }
            ImportOverlay.hide(this@FilePickActivity)
            if (isFinishing || isDestroyed) return@launch
            when (outcome) {
                Copied.OK -> answer(file, FilePickRules.fileName(name))
                Copied.TOO_LARGE -> { runCatching { file.delete() }; problem(getString(R.string.file_pick_too_large_body, TemplateImport.megabytes(FilePickRules.MAX_BYTES.toInt()))) }
                Copied.FAILED -> { runCatching { file.delete() }; problem(getString(R.string.file_pick_failed_body)) }
            }
        }
    }

    private enum class Copied { OK, TOO_LARGE, FAILED }

    private fun copy(uri: Uri, into: File): Copied = try {
        into.parentFile?.mkdirs()
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(into).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (!FilePickRules.fits(total)) return Copied.TOO_LARGE
                    out.write(buffer, 0, n)
                }
                out.flush()
            }
            Copied.OK
        } ?: Copied.FAILED
    } catch (e: Exception) {
        Log.w(TAG, "copy failed: ${e.javaClass.simpleName}")
        Copied.FAILED
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** The browser's pick, downloaded into the cache; a listed size over the cap is refused before any bytes move. */
    private fun downloadThenAnswer(ref: Extension, entry: CloudEntry) {
        if (entry.sizeBytes > 0 && !FilePickRules.fits(entry.sizeBytes)) { problem(getString(R.string.file_pick_too_large_body, TemplateImport.megabytes(FilePickRules.MAX_BYTES.toInt()))); return }
        lifecycleScope.launch {
            val file = File(folder(), LANDED)
            val failure = cloud.download(ref, entry, file)
            if (isFinishing || isDestroyed) return@launch
            if (failure != null) { runCatching { file.delete() }; cloud.explain(failure, R.string.cloud_pick_failed_title, put = false); busy = false; return@launch }
            answer(file, FilePickRules.fileName(entry.name))
        }
    }

    private fun answer(file: File, name: String) {
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName$AUTHORITY_SUFFIX", file)
        } catch (e: Exception) {
            Log.w(TAG, "no uri for the landed file: ${e.javaClass.simpleName}")
            problem(getString(R.string.file_pick_failed_body)); return
        }
        Slog.d(TAG) { "answered ${file.length()} bytes" }
        setResult(Activity.RESULT_OK, Intent().setData(uri).putExtra(Seam.EXTRA_FILE_NAME, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    /** A failure said, and the screen left: the asking app is where the person was. */
    private fun problem(body: String) {
        busy = false
        if (isFinishing || isDestroyed) return
        Dialogs.problem(this, R.string.file_pick_failed_title, body)
    }

    private fun folder(): File = File(cacheDir, FOLDER)

    private companion object {
        const val TAG = "FilePick"
        const val FOLDER = "pick"
        const val LANDED = "picked"
        const val AUTHORITY_SUFFIX = ".files"
    }
}
