package com.symmetricalpalmtree.soil.library

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityItemPickerBinding
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck

/**
 * **An item, chosen for an app**: the library browser in pick shape, started for a result by a
 * Sprout app ([Seam.ACTION_PICK_ITEM], guarded by the seam permission). Narrowed to one kind,
 * with the asking app's own item left out; folders are entered, a new one may be made, and a tap
 * on an item is the answer, its id. There is one library, in Soil: an app never browses on its
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
            onOpen = { item ->
                setResult(Activity.RESULT_OK, Intent().putExtra(Seam.EXTRA_ITEM_ID, item.id))
                finish()
            },
            kind = intent.getStringExtra(Seam.EXTRA_KIND),
            excludeId = intent.getStringExtra(Seam.EXTRA_EXCLUDE_ITEM_ID),
            sheets = false,
        )
        browser.restoreState(savedInstanceState)
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnNewFolder.setOnClickListener { browser.showNewFolderDialog() }
        binding.btnSearch.setOnClickListener { browser.openSearchDialog() }
    }

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
