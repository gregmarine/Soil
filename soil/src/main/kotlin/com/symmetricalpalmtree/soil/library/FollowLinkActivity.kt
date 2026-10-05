package com.symmetricalpalmtree.soil.library

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.seam.Seam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **A link followed for an app** ([Seam.ACTION_FOLLOW]): the item it points at, opened in the app
 * for its kind, at the page it names. Only Soil knows which app opens what, so a link that
 * leaves its own kind comes through here. It shows nothing when the hop lands, and one dialog
 * when it cannot: the library closed, the item gone, or no app installed for it. What is behind
 * stays in view.
 */
class FollowLinkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val itemId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        val pageId = intent.getStringExtra(Seam.EXTRA_PAGE_ID)?.takeIf { it.isNotEmpty() }
        if (savedInstanceState != null || itemId.isNullOrEmpty()) { finish(); return }
        if (!SoilIndex.isReady()) { explain(R.string.follow_locked_body); return }
        lifecycleScope.launch {
            val item = withContext(Dispatchers.IO) { runCatching { IndexStore().aliveItem(itemId) }.getOrNull() }
            if (item == null) { explain(R.string.follow_gone_body); return@launch }
            when (ItemApps.open(this@FollowLinkActivity, item.id, item.kind, pageId = pageId)) {
                ItemApps.Opened.YES -> finish()
                ItemApps.Opened.NO_APP -> explain(getString(R.string.item_no_app_body, item.name))
                ItemApps.Opened.FAILED -> explain(getString(R.string.item_open_failed_body, item.name))
            }
        }
    }

    private fun explain(bodyRes: Int) = explain(getString(bodyRes))

    private fun explain(body: String) {
        if (isFinishing || isDestroyed) return
        Dialogs.confirm(this, getString(R.string.follow_failed_title), body) { finish() }
    }
}
