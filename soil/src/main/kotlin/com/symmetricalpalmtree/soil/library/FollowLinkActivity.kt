package com.symmetricalpalmtree.soil.library

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.seam.BibleAddress
import com.symmetricalpalmtree.soil.seam.CalAddress
import com.symmetricalpalmtree.soil.seam.Seam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **A link followed for an app** ([Seam.ACTION_FOLLOW]): the item it points at, opened in the app
 * for its kind, at the page it names; or, with [Seam.EXTRA_BIBLE_WIRE], the passage it names,
 * opened in the Bible's reader; or, with [Seam.EXTRA_CAL_DATE], the day it names, opened in the
 * calendar on that Day page. Only Soil knows which app opens what, so a link that leaves its
 * own kind comes through here. It shows nothing when the hop lands, and one dialog
 * when it cannot: the library closed, the item gone, or no app installed for it. What is behind
 * stays in view.
 */
class FollowLinkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val itemId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        val pageId = intent.getStringExtra(Seam.EXTRA_PAGE_ID)?.takeIf { it.isNotEmpty() }
        val wire = intent.getStringExtra(Seam.EXTRA_BIBLE_WIRE)
        val day = intent.getStringExtra(Seam.EXTRA_CAL_DATE)
        if (savedInstanceState != null) { finish(); return }
        if (!day.isNullOrEmpty()) {
            // A link to a day: no item to look up. Untrusted input, so the shape is checked here.
            if (!CalAddress.isDate(day)) { finish(); return }
            when (ItemApps.openCalendar(this, day)) {
                ItemApps.Opened.YES -> finish()
                ItemApps.Opened.NO_APP -> explain(R.string.follow_no_calendar_body)
                ItemApps.Opened.FAILED -> explain(R.string.follow_calendar_failed_body)
            }
            return
        }
        if (!wire.isNullOrEmpty()) {
            // A link into the Bible: no item to look up, and the wire is the reader's to read.
            // Untrusted input, so only its shape is checked here; the reader says the rest.
            if (!BibleAddress.isWire(wire)) { finish(); return }
            when (ItemApps.openBible(this, wire)) {
                ItemApps.Opened.YES -> finish()
                ItemApps.Opened.NO_APP -> explain(R.string.follow_no_bible_body)
                ItemApps.Opened.FAILED -> explain(R.string.follow_bible_failed_body)
            }
            return
        }
        if (itemId.isNullOrEmpty()) { finish(); return }
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
