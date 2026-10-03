package com.symmetricalpalmtree.soil.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.IndexSchema
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.item.ItemFiles
import com.symmetricalpalmtree.soil.data.item.ItemNames
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.databinding.ActivityNewNotebookBinding
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.templates.TemplatePick
import com.symmetricalpalmtree.soil.templates.TemplateBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * **New notebook**: a name, prefilled from the folder's naming scheme (else the date and time),
 * over the whole template browser, ticked to the folder's default paper; Create makes the item
 * in Soil, folder and all, and opens it in its app with the pick, which the app lays onto the
 * first page as it lays any paper. The file is made here with the app's schema, empty: an app
 * finding no pages makes the first.
 */
class NewNotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewNotebookBinding
    private lateinit var browser: TemplateBrowser
    private var pick: TemplatePick = TemplatePick.Blank
    private var creating = false
    private val folderId: String get() = intent.getStringExtra(EXTRA_FOLDER_ID).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SoilIndex.isReady()) { finish(); return }
        binding = ActivityNewNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        browser = TemplateBrowser(
            activity = this,
            binding = binding.browser,
            onPick = { picked -> pick = picked; browser.refreshSelection() },
            selection = { TemplateBrowser.Selection(cardId = pick.cardId) },
        )
        browser.restoreState(savedInstanceState)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnCreate.setOnClickListener { create() }
        lifecycleScope.launch {
            val (name, defaultPick) = withContext(Dispatchers.IO) {
                val store = LibraryStore()
                val scheme = store.resolve(folderId) { it.scheme }
                val prefill = SchemePrefill.expand(scheme, System.currentTimeMillis()) { store.items(folderId).map { it.name } }
                val template = store.resolve(folderId) { it.template }?.let { TemplatePick.decode(it) }
                (prefill ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())) to template
            }
            if (savedInstanceState == null) {
                binding.nameField.setText(name)
                binding.nameField.selectAll()
            }
            defaultPick?.let { pick = it; browser.refreshSelection() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::browser.isInitialized) browser.saveState(outState)
    }

    /** The row and the file, then the app. Guarded by a flag, never a disabled button. */
    private fun create() {
        if (creating) return
        val typed = binding.nameField.text?.toString().orEmpty()
        val name = runCatching { ItemNames.clean(typed) }.getOrNull() ?: run {
            Dialogs.problem(this, R.string.name_problem_title, R.string.name_empty)
            return
        }
        val chosen = pick
        creating = true
        lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    if (ItemApps.find(this@NewNotebookActivity, IndexSchema.KIND_NOTEBOOK) == null) return@withContext null
                    val id = UUID.randomUUID().toString()
                    val now = System.currentTimeMillis()
                    // The file first: a row with no file is an item that cannot be opened.
                    ItemFiles.createEmpty(this@NewNotebookActivity, id, name, now, IndexSchema.KIND_NOTEBOOK)
                    IndexStore().insert(id, IndexSchema.KIND_NOTEBOOK, name, now, folderId)
                    id
                }
                if (id == null) {
                    Dialogs.problem(this@NewNotebookActivity, getString(R.string.item_no_app_title), getString(R.string.item_no_app_body, name))
                    return@launch
                }
                ItemSessions.changed()
                when (ItemApps.open(this@NewNotebookActivity, id, IndexSchema.KIND_NOTEBOOK, chosen.encode())) {
                    ItemApps.Opened.YES -> finish()
                    else -> Dialogs.problem(this@NewNotebookActivity, getString(R.string.item_open_failed_title), getString(R.string.item_open_failed_body, name))
                }
            } catch (e: Exception) {
                Log.w(TAG, "the notebook could not be made: ${e.javaClass.simpleName}")
                Dialogs.problem(this@NewNotebookActivity, R.string.new_notebook_failed_title, R.string.new_notebook_failed_body)
            } finally {
                creating = false
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.onBackPressed()) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        private const val TAG = "NewNotebook"
        private const val EXTRA_FOLDER_ID = "folderId"
        fun intent(context: Context, folderId: String): Intent = Intent(context, NewNotebookActivity::class.java).putExtra(EXTRA_FOLDER_ID, folderId)
    }
}
