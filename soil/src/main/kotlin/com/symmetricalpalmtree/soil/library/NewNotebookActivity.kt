package com.symmetricalpalmtree.soil.library

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * **New notebook — and New sketchbook**: a name, prefilled from the folder's naming scheme (else
 * the date and time), over the whole template browser, ticked to the folder's default paper;
 * Create makes the item in Soil, folder and all, and opens it in its app with the pick, which the
 * app lays onto the first page as it lays any paper. The file is made here with the app's schema,
 * empty: an app finding no pages makes the first.
 *
 * The screen is one for both kinds (Greg, 2026-10-06: a sketchbook takes a paper from the
 * library too), and [EXTRA_KIND] says which: the kind decides which app is asked for, what the
 * index row says, and the failure's words. Nothing else differs.
 */
class NewNotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewNotebookBinding
    private lateinit var browser: TemplateBrowser
    private var pick: TemplatePick = TemplatePick.Blank
    private var creating = false
    private val folderId: String get() = intent.getStringExtra(EXTRA_FOLDER_ID).orEmpty()

    /** The kind being made: a notebook unless the intent says a sketchbook. */
    private val kind: String get() = intent.getStringExtra(EXTRA_KIND).takeIf { it == IndexSchema.KIND_SKETCHBOOK } ?: IndexSchema.KIND_NOTEBOOK

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
        binding.kindTitle.setText(if (kind == IndexSchema.KIND_SKETCHBOOK) R.string.new_sketchbook_title else R.string.new_notebook_title)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnCreate.setOnClickListener { create() }
        lifecycleScope.launch {
            // The folder's say is a prefill, not a need: a read that fails falls back to the
            // date and time on Blank paper, as a folder with no say does.
            val (name, defaultPick) = try {
                withContext(Dispatchers.IO) {
                    val store = LibraryStore()
                    val scheme = store.resolve(folderId) { it.scheme }
                    val prefill = SchemePrefill.expand(scheme, System.currentTimeMillis()) { store.items(folderId).map { it.name } }
                    val template = store.resolve(folderId) { it.template }?.let { TemplatePick.decode(it) }
                    prefill to template
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the folder's say could not be read: ${e.javaClass.simpleName}")
                null to null
            }.let { (prefill, template) -> (prefill ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())) to template }
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

    override fun onDestroy() {
        if (::browser.isInitialized) browser.close()
        super.onDestroy()
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
        // The box goes up before the file is made: the wait is seconds of key work and an
        // app launch, and a screen that does nothing for that long reads as a hang.
        binding.creatingOverlay.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    if (ItemApps.find(this@NewNotebookActivity, kind) == null) return@withContext null
                    val id = UUID.randomUUID().toString()
                    val now = System.currentTimeMillis()
                    // The file first: a row with no file is an item that cannot be opened.
                    ItemFiles.createEmpty(this@NewNotebookActivity, id, name, now, kind)
                    IndexStore().insert(id, kind, name, now, folderId)
                    id
                }
                if (id == null) {
                    Dialogs.problem(this@NewNotebookActivity, getString(R.string.item_no_app_title), getString(R.string.item_no_app_body, name))
                    return@launch
                }
                ItemSessions.changed()
                when (ItemApps.openItem(this@NewNotebookActivity, id, kind, chosen.encode())) {
                    ItemApps.Opened.YES -> finish()
                    else -> Dialogs.problem(this@NewNotebookActivity, getString(R.string.item_open_failed_title), getString(R.string.item_open_failed_body, name))
                }
            } catch (e: Exception) {
                Log.w(TAG, "the $kind could not be made: ${e.javaClass.simpleName}")
                val titleRes = if (kind == IndexSchema.KIND_SKETCHBOOK) R.string.new_sketchbook_failed_title else R.string.new_notebook_failed_title
                Dialogs.problem(this@NewNotebookActivity, titleRes, R.string.new_notebook_failed_body)
            } finally {
                creating = false
                // Down again only when this screen stays: on success it finishes under the app.
                if (!isFinishing) binding.creatingOverlay.visibility = View.GONE
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
        private const val EXTRA_KIND = "kind"

        /** The screen for a new item of [kind] — [IndexSchema.KIND_NOTEBOOK] or [IndexSchema.KIND_SKETCHBOOK] — in [folderId]. */
        fun intent(context: Context, folderId: String, kind: String = IndexSchema.KIND_NOTEBOOK): Intent =
            Intent(context, NewNotebookActivity::class.java).putExtra(EXTRA_FOLDER_ID, folderId).putExtra(EXTRA_KIND, kind)
    }
}
