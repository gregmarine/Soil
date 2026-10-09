package com.symmetricalpalmtree.soil.templates

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.index.TemplateStore
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.templates.TemplateFit
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Save as template**, Soil's half: a picture an app parked through the seam gets a name and a
 * folder here, then a row. A refusal (reserved, or taken in the folder chosen) re-asks the name
 * and keeps the folder. Fit is pinned to Fit: the picture is a page at a page's aspect, and Fit
 * is the one mode that cannot crop or distort it. A parking that is gone says so and saves
 * nothing. Transparent: the dialogs are the whole screen.
 *
 * The parking is peeked, not taken, until the row is made: a screen recreated while the folder
 * picker is up still has its picture, and its name rides the saved state.
 */
class SaveTemplateActivity : AppCompatActivity() {

    private var bytes: ByteArray? = null
    private var name: String = ""
    private var stagedId: String? = null
    /** The name is accepted and the folder picker is up: a recreation waits for its answer. */
    private var picking = false

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        picking = false
        val folder = result.data?.getStringExtra(com.symmetricalpalmtree.soil.library.FolderPickerActivity.EXTRA_PICKED_FOLDER)
        if (result.resultCode != Activity.RESULT_OK || folder == null) { finish(); return@registerForActivityResult }
        place(folder)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (runCatching { SeamCallerCheck.enforceCaller(this, callingPackage) }.isFailure || !SoilIndex.isReady()) { finish(); return }
        stagedId = intent.getStringExtra(Seam.EXTRA_STAGED_ID)
        val staged = stagedId?.let { TemplateStaging.peek(it) }
        if (staged == null) {
            Dialogs.problem(this, R.string.template_save_lost_title, R.string.template_save_lost_body)
            finishOnDismiss()
            return
        }
        bytes = staged
        if (savedInstanceState != null) {
            name = savedInstanceState.getString(KEY_NAME).orEmpty()
            picking = savedInstanceState.getBoolean(KEY_PICKING)
        }
        if (!picking) askName(name.ifEmpty { intent.getStringExtra(Seam.EXTRA_SEED_NAME).orEmpty() }, folder = null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_NAME, name)
        outState.putBoolean(KEY_PICKING, picking)
    }

    /** A screen that goes for good lets its parking go with it. */
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) stagedId?.let { TemplateStaging.take(it) }
    }

    private fun finishOnDismiss() { window.decorView.postDelayed({ finish() }, 2500) }

    /** [folder] null: not chosen yet, accepting opens the picker. Else the re-ask after a refusal, the folder kept. */
    private fun askName(seed: String, folder: String?) {
        var accepting = false
        NameDialog.show(this, R.string.template_save_name_title, R.string.template_save_confirm, seed, R.string.template_import_name_hint, onCancel = { finish() }) { typed, dismiss ->
            if (accepting) return@show
            if (NameDialog.reject(this, typed, folder ?: "")) return@show
            accepting = true
            name = typed
            dismiss()
            picking = folder == null
            if (folder == null) folderLauncher.launch(com.symmetricalpalmtree.soil.library.FolderPickerActivity.saveIntent(this, com.symmetricalpalmtree.soil.library.FolderPickerActivity.Hierarchy.TEMPLATES)) else place(folder)
        }
    }

    private fun place(folder: String) {
        val image = bytes ?: run { finish(); return }
        lifecycleScope.launch {
            try {
                val problem = withContext(Dispatchers.IO) {
                    when {
                        com.symmetricalpalmtree.soil.paper.templates.TemplateIds.isReservedName(folder, name) -> R.string.template_name_reserved
                        TemplateStore().nameTaken(folder, false, name) -> R.string.template_duplicate_name
                        else -> null
                    }
                }
                if (problem != null) {
                    // The refusal first, the name again only once it has been read: the two in one
                    // beat left the name dialog over the words that explained it.
                    Dialogs.confirm(this@SaveTemplateActivity, getString(R.string.name_problem_title), getString(problem, name)) { askName(name, folder) }
                    return@launch
                }
                withContext(Dispatchers.IO) { TemplateStore().createTemplate(name, folder, TemplateFit.FIT, image) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the template could not be saved: ${e.javaClass.simpleName}")
                // The parking stays: the name is asked again, the folder kept.
                Dialogs.confirm(this@SaveTemplateActivity, getString(R.string.library_change_failed_title), getString(R.string.library_change_failed_body)) { askName(name, folder) }
                return@launch
            }
            stagedId?.let { TemplateStaging.take(it) }
            Toast.makeText(this@SaveTemplateActivity, R.string.template_saved, Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private companion object {
        const val TAG = "SaveTemplate"
        const val KEY_NAME = "saveTemplate.name"
        const val KEY_PICKING = "saveTemplate.picking"
    }
}
