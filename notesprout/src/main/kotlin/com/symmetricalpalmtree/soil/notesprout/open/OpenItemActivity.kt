package com.symmetricalpalmtree.soil.notesprout.open

import android.os.Binder
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.notesprout.NotesproutApp
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.databinding.ActivityOpenItemBinding
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.store.Statement
import com.symmetricalpalmtree.soil.seam.ISeamItem
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seamkit.SeamRowStore
import com.symmetricalpalmtree.soil.seamkit.SeamUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **A notebook, opened by Soil.** For now it opens the notebook through the seam and says what it
 * found: its name and how many pages it has. The page itself arrives with the notebook screen.
 *
 * What arrives on the Intent is the notebook's id and nothing else. The device has no Back key:
 * the top bar's close button is the way out, and closes the notebook in Soil.
 */
class OpenItemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOpenItemBinding

    /** Any binder of this app's own: Soil watches it, and closes the notebook if the app dies. */
    private val owner = Binder()
    private var session: ISeamItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOpenItemBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnClose.setOnClickListener { finish() }
        val itemId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        if (itemId.isNullOrEmpty()) {
            finish()
            return
        }
        lifecycleScope.launch { open(itemId) }
    }

    private suspend fun open(itemId: String) {
        val soil = (application as NotesproutApp).soil
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val seam = soil.seam()
                val item = seam.item(itemId) ?: throw IllegalStateException(NO_SUCH_ITEM)
                val opened = seam.openItem(itemId, NotebookSchema.SCHEMA, owner)
                session = opened
                val pages = SeamRowStore(opened).query(
                    Statement(
                        "SELECT count(*) AS n FROM ${NotebookSchema.TABLE} WHERE type = ? AND deletedAt IS NULL",
                        NotebookSchema.TYPE_PAGE,
                    ),
                )[0].long("n")
                item.name to pages
            }
        }
        found.fold(
            onSuccess = { (name, pages) ->
                binding.title.text = name
                binding.detail.text =
                    if (pages == 1L) getString(R.string.open_pages_one) else getString(R.string.open_pages_many, pages.toInt())
            },
            onFailure = { refuse(it) },
        )
    }

    /** Why it did not open, in words. Nothing of the failure itself is shown. */
    private fun refuse(failure: Throwable) {
        val body = when {
            failure is SeamUnavailable -> R.string.open_no_soil
            failure.message == SeamLimits.LIBRARY_NOT_OPEN -> R.string.open_locked
            failure.message == NO_SUCH_ITEM -> R.string.open_missing
            failure.message == SeamLimits.SCHEMA_NEWER -> R.string.open_newer
            else -> R.string.open_failed
        }
        binding.detail.text = ""
        Dialogs.confirm(this, getString(R.string.open_failed_title), getString(body)) { finish() }
    }

    override fun onDestroy() {
        val open = session
        session = null
        if (open != null) {
            NotesproutApp.appScope.launch(Dispatchers.IO + NonCancellable) { runCatching { open.close(true) } }
        }
        super.onDestroy()
    }

    private companion object { const val NO_SUCH_ITEM = "there is no such item" }
}
