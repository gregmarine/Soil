package com.symmetricalpalmtree.soil.docsprout.editor

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.docsprout.DocsproutApp
import com.symmetricalpalmtree.soil.docsprout.DocsproutApp.Companion.appScope
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.docsprout.data.DocsproutPrefs
import com.symmetricalpalmtree.soil.docsprout.data.DocumentLimits
import com.symmetricalpalmtree.soil.docsprout.data.DocumentSchema
import com.symmetricalpalmtree.soil.docsprout.data.DocumentStore
import com.symmetricalpalmtree.soil.docsprout.databinding.ActivityDocumentBinding
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.seam.ISeamItem
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seam.SeamShared
import com.symmetricalpalmtree.soil.seamkit.SeamRowStore
import com.symmetricalpalmtree.soil.seamkit.SeamUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * **A document**: its Markdown in one field, saved by itself. There is no Save and no Done:
 * the words are written two seconds after the typing stops, and on every way out.
 *
 * **Everything that touches the open file goes through one queue** ([ops]), in the order it was
 * asked for on the main thread: a save, the park when the screen stops, the resume when it comes
 * back, the close. Each save carries the text as it stood when it was asked for, so the newest
 * words are always the last written, and a way out never overtakes the save it follows.
 *
 * **The IME is never hidden from this screen.** On Ratta hardware a keyboard's keys are
 * delivered only while the IME is shown, so nothing here calls an IME-hide; the window resizes
 * for it instead.
 *
 * The document's text is never logged: lengths only.
 */
class DocumentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentBinding
    private lateinit var prefs: DocsproutPrefs
    private val main = Handler(Looper.getMainLooper())

    /** What Soil holds the open file against: this screen's own death closes it. */
    private val owner = Binder()
    private val ops = Mutex()
    private var session: ISeamItem? = null
    private var store: DocumentStore? = null
    private var itemId: String? = null

    /** The text the file is known to hold. Read and written only on Main, inside [ops]. */
    private var savedText: String? = null
    private var coverText: String? = null
    private var opened = false
    private var started = false
    private var closing = false
    private var tooLongTold = false

    private val autosave = Runnable { save() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        prefs = DocsproutPrefs(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.editor.doAfterTextChanged {
            if (!opened) return@doAfterTextChanged
            main.removeCallbacks(autosave)
            main.postDelayed(autosave, AUTOSAVE_DELAY_MS)
        }
        lifecycleScope.launch { open() }
    }

    /**
     * One document screen at a time. Asked for the document already showing, it stays; asked
     * for another, it starts again with the new ask, and the way out of this one (pause, stop,
     * destroy) saves and closes it as every way out does.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val askedId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        val newName = intent.getStringExtra(Seam.EXTRA_NEW_NAME)
        if (newName.isNullOrBlank() && askedId != null && askedId == itemId) return
        setIntent(intent)
        if (closing) return
        recreate()
    }

    // ── Open ──────

    private suspend fun open() {
        val newName = intent.getStringExtra(Seam.EXTRA_NEW_NAME)
        val askedId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        if (newName.isNullOrBlank() && askedId.isNullOrEmpty()) {
            refuse(R.string.open_missing)
            return
        }
        val soil = (application as DocsproutApp).soil
        val (name, body) = try {
            ops.withLock {
                withContext(Dispatchers.IO) {
                    val seam = soil.seam()
                    val item = if (!newName.isNullOrBlank()) seam.createItem(newName, DocumentSchema.SCHEMA)
                    else seam.item(askedId!!) ?: throw IllegalStateException(NO_SUCH_ITEM)
                    val opened = seam.openItem(item.id, DocumentSchema.SCHEMA, owner)
                    session = opened
                    itemId = item.id
                    val documents = DocumentStore(SeamRowStore(opened), item.id)
                    store = documents
                    val text = documents.load()
                    prefs.lastDocumentId = item.id
                    item.name to text
                }
            }
        } catch (e: CancellationException) {
            // The screen went while the file was being opened: its destroy found nothing to close.
            letGo()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the document could not be opened: ${e.javaClass.simpleName}")
            // A document that is gone is not offered again by the icon.
            if (e.message == NO_SUCH_ITEM && prefs.lastDocumentId == askedId) prefs.lastDocumentId = null
            refuse(
                when {
                    e is SeamUnavailable -> R.string.open_no_soil
                    e.message == SeamLimits.LIBRARY_NOT_OPEN -> R.string.open_locked
                    e.message == NO_SUCH_ITEM -> R.string.open_missing
                    e.message == SeamLimits.SCHEMA_NEWER -> R.string.open_newer
                    else -> R.string.open_failed
                },
            )
            return
        }
        if (isFinishing || isDestroyed || closing) return
        savedText = body
        coverText = body
        binding.title.text = name
        binding.editor.setText(body)
        binding.editor.setSelection(0)
        binding.opening.visibility = View.GONE
        binding.editor.visibility = View.VISIBLE
        binding.editor.requestFocus()
        opened = true
        // The screen stopped while the file was being read: it is put down as a stop puts it.
        if (!started) park()
        Slog.d(TAG) { "opened: ${body.length} chars" }
    }

    /** A document that did not open is explained, and then the screen leaves. */
    private fun refuse(bodyRes: Int) {
        binding.opening.visibility = View.GONE
        if (isFinishing || isDestroyed) return
        closing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.open_failed_title)
                .setMessage(bodyRes)
                .setPositiveButton(com.symmetricalpalmtree.soil.paper.R.string.ok) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .create(),
        ).show()
    }

    // ── Save ──────

    /**
     * Write the text as it stands now, if the file does not already hold it. Queued behind
     * whatever was asked before it; a save that fails leaves the text unsaved and is tried
     * again while the screen is up.
     */
    private fun save() {
        main.removeCallbacks(autosave)
        if (!opened) return
        val documents = store ?: return
        val text = binding.editor.text?.toString().orEmpty()
        if (!DocumentLimits.fits(text)) {
            if (!tooLongTold) {
                tooLongTold = true
                Dialogs.problem(this, R.string.too_long_title, R.string.too_long_body)
            }
            return
        }
        tooLongTold = false
        appScope.launch {
            withContext(NonCancellable) {
                ops.withLock {
                    if (text == savedText) return@withLock
                    val landed = withContext(Dispatchers.IO) {
                        runCatching { documents.save(text) }.onFailure { Log.w(TAG, "save failed: ${it.javaClass.simpleName}") }.isSuccess
                    }
                    if (landed) savedText = text
                    else if (started && !closing) main.postDelayed(autosave, AUTOSAVE_DELAY_MS)
                }
            }
        }
    }

    // ── Park and resume ──────

    override fun onStart() {
        super.onStart()
        started = true
        val open = session ?: return
        appScope.launch {
            ops.withLock { withContext(Dispatchers.IO) { runCatching { open.resume() }.onFailure { Log.w(TAG, "resume failed: ${it.javaClass.simpleName}") } } }
        }
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        started = false
        main.removeCallbacks(autosave)
        if (!opened) return
        writeCover()
        park()
    }

    /** After the pause's save, which was queued first. */
    private fun park() {
        val open = session ?: return
        appScope.launch {
            withContext(NonCancellable) {
                ops.withLock { withContext(Dispatchers.IO) { runCatching { open.park() }.onFailure { Log.w(TAG, "park failed: ${it.javaClass.simpleName}") } } }
            }
        }
    }

    /** The library card's cover, when the words have changed since the last one. Never worth
     *  failing a way out for. */
    private fun writeCover() {
        val id = itemId ?: return
        val text = binding.editor.text?.toString().orEmpty()
        if (text == coverText) return
        coverText = text
        appScope.launch(Dispatchers.IO) {
            try {
                (application as DocsproutApp).soil.seam().setCover(id, SeamShared.write(TextCover.encode(text)))
            } catch (e: Throwable) {
                Log.w(TAG, "the cover was not written: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(autosave)
        letGo()
    }

    /** The file closed for good, after every save and the park, which were queued first. */
    private fun letGo() {
        val open = session ?: return
        session = null
        appScope.launch {
            withContext(NonCancellable) {
                ops.withLock { withContext(Dispatchers.IO) { runCatching { open.close(true) }.onFailure { Log.w(TAG, "close failed: ${it.javaClass.simpleName}") } } }
            }
        }
    }

    companion object {
        private const val TAG = "DocumentActivity"
        private const val NO_SUCH_ITEM = "there is no such item"
        private const val AUTOSAVE_DELAY_MS = 2_000L
    }
}
