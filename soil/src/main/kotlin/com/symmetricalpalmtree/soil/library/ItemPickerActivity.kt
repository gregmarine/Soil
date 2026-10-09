package com.symmetricalpalmtree.soil.library

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.export.AppRenderers
import com.symmetricalpalmtree.soil.importing.ImportDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityItemPickerBinding
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck

/**
 * **An item, chosen for an app**: the library browser in pick shape, started for a result by a
 * Sprout app ([Seam.ACTION_PICK_ITEM], guarded by the seam permission). Narrowed to one kind,
 * with the asking app's own item left out; folders are entered, a new one may be made, and a tap
 * on an item is the answer, its id (and, when the asker wants one, a page of it). There is one library, in Soil: an app never browses on its
 * own.
 */
class ItemPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityItemPickerBinding
    private lateinit var browser: LibraryBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (runCatching { SeamCallerCheck.enforceCaller(this, callingPackage) }.isFailure || !SoilIndex.isReady()) { finish(); return }
        binding = ActivityItemPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        browser = LibraryBrowser(
            activity = this,
            binding = binding.browser,
            onOpen = { item -> if (intent.getBooleanExtra(Seam.EXTRA_PICK_PAGE, false)) pickPageOf(item) else answer(item, null) },
            kind = intent.getStringExtra(Seam.EXTRA_KIND),
            excludeId = intent.getStringExtra(Seam.EXTRA_EXCLUDE_ITEM_ID),
            sheets = false,
        )
        // Narrowed to notebooks it says so; open to every kind, it is the library.
        if (intent.getStringExtra(Seam.EXTRA_KIND) == null) binding.title.setText(R.string.item_picker_title_any)
        browser.restoreState(savedInstanceState)
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnNewFolder.setOnClickListener { browser.showNewFolderDialog() }
        binding.btnSearch.setOnClickListener { browser.openSearchDialog() }
        // The asker has the item already and wants one of its pages: straight to them.
        intent.getStringExtra(Seam.EXTRA_PAGE_OF_ITEM)?.takeIf { it.isNotEmpty() }?.let { id ->
            if (savedInstanceState != null) return@let
            lifecycleScope.launch {
                val item = withContext(Dispatchers.IO) { runCatching { com.symmetricalpalmtree.soil.data.index.IndexStore().aliveItem(id) }.getOrNull() }
                if (item == null) { finish(); return@launch }
                pickPageOf(item, finishOnCancel = true)
            }
        }
    }

    private fun answer(item: Item, pageId: String?) {
        setResult(Activity.RESULT_OK, Intent().putExtra(Seam.EXTRA_ITEM_ID, item.id).putExtra(Seam.EXTRA_ITEM_NAME, item.name).putExtra(Seam.EXTRA_PAGE_ID, pageId))
        finish()
    }

    /**
     * The whole item, or one of its pages: asked of the item's own app, which is the only thing
     * that can name them. An item with no pages to name (a document, or a kind with no app
     * installed) is its own answer.
     */
    private fun pickPageOf(item: Item, finishOnCancel: Boolean = false) {
        if (pickingPage) return
        pickingPage = true
        lifecycleScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) { AppRenderers.find(this@ItemPickerActivity, item.kind) }
                    ?.let { renderer -> runCatching { AppRenderers.pages(this@ItemPickerActivity, renderer, item.id) }.getOrNull() }
                if (pages == null || pages.ids.isEmpty()) { answer(item, null); return@launch }
                val labels = ArrayList<String>(pages.ids.size + 1)
                labels += getString(R.string.pick_page_whole)
                for (i in pages.ids.indices) {
                    val title = pages.titles[i]
                    labels += if (title.isEmpty()) getString(R.string.pick_page_numbered, pages.numbers[i]) else getString(R.string.pick_page_titled, pages.numbers[i], title)
                }
                val picked = ImportDialogs.pickFromList(this@ItemPickerActivity, R.string.pick_page_title, labels)
                if (picked == null) { if (finishOnCancel) finish(); return@launch }
                answer(item, if (picked == 0) null else pages.ids[picked - 1])
            } finally {
                pickingPage = false
            }
        }
    }

    private var pickingPage = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::browser.isInitialized) browser.saveState(outState)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.onBackPressed()) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }
}
