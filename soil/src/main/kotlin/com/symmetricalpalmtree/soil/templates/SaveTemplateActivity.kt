package com.symmetricalpalmtree.soil.templates

import android.app.Activity
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Save as template**, Soil's half: a picture an app parked through the seam gets a name and a
 * folder here, then a row. A refusal (reserved, or taken in the folder chosen) re-asks the name
 * and keeps the folder. Fit is pinned to Fit: the picture is a page at a page's aspect, and Fit
 * is the one mode that cannot crop or distort it. A parking that is gone says so and saves
 * nothing. Transparent: the dialogs are the whole screen.
 */
class SaveTemplateActivity : AppCompatActivity() {

    private var bytes: ByteArray? = null
    private var name: String = ""

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val folder = result.data?.getStringExtra(com.symmetricalpalmtree.soil.library.FolderPickerActivity.EXTRA_PICKED_FOLDER)
        if (result.resultCode != Activity.RESULT_OK || folder == null) { finish(); return@registerForActivityResult }
        place(folder)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (runCatching { SeamCallerCheck.enforceCaller(this, callingPackage) }.isFailure || !SoilIndex.isReady()) { finish(); return }
        val staged = intent.getStringExtra(Seam.EXTRA_STAGED_ID)?.let { TemplateStaging.take(it) }
        if (staged == null) {
            Dialogs.problem(this, R.string.template_save_lost_title, R.string.template_save_lost_body)
            finishOnDismiss()
            return
        }
        bytes = staged
        askName(intent.getStringExtra(Seam.EXTRA_SEED_NAME).orEmpty(), folder = null)
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
            if (folder == null) folderLauncher.launch(com.symmetricalpalmtree.soil.library.FolderPickerActivity.saveIntent(this, com.symmetricalpalmtree.soil.library.FolderPickerActivity.Hierarchy.TEMPLATES)) else place(folder)
        }
    }

    private fun place(folder: String) {
        val image = bytes ?: run { finish(); return }
        lifecycleScope.launch {
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
            Toast.makeText(this@SaveTemplateActivity, R.string.template_saved, Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK)
            finish()
        }
    }
}
