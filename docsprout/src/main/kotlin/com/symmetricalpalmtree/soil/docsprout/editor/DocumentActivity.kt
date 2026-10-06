package com.symmetricalpalmtree.soil.docsprout.editor

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.docsprout.DocsproutApp
import com.symmetricalpalmtree.soil.docsprout.DocsproutApp.Companion.appScope
import com.symmetricalpalmtree.soil.docsprout.R
import com.symmetricalpalmtree.soil.docsprout.data.BibleUnlinked
import com.symmetricalpalmtree.soil.docsprout.data.DocsproutPrefs
import com.symmetricalpalmtree.soil.docsprout.data.DocumentLimits
import com.symmetricalpalmtree.soil.docsprout.data.DocumentSchema
import com.symmetricalpalmtree.soil.docsprout.data.DocumentStore
import com.symmetricalpalmtree.soil.docsprout.databinding.ActivityDocumentBinding
import com.symmetricalpalmtree.soil.docsprout.editor.bible.BibleLinkController
import com.symmetricalpalmtree.soil.docsprout.editor.rich.RichOps
import com.symmetricalpalmtree.soil.markdown.rich.RichParse
import com.symmetricalpalmtree.soil.markdown.rich.RichWrite
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
 * **A document**, saved by itself. There is no Save and no Done: the words are written two
 * seconds after the typing stops, and on every way out.
 *
 * **Two surfaces, one truth.** The file holds Markdown. The document opens rendered: shown as it
 * reads and edited in place, with no marker in sight. The other surface is the Markdown itself.
 * Going from one to the other writes or reads the Markdown once, and carries the caret by block.
 * A document that is opened rendered and not edited is never rewritten: what the file holds is
 * what it was given, until the writer changes something.
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
 * What is done *to* the words lives beside this screen, one collaborator a concern: the format
 * bar and its overflow, the chords, find and replace, the tidying tools, the text size, the
 * rename. Each edits through the field's own `Editable`, so the field's own undo takes it back.
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

    /** The Bible links the writer took off ([BibleUnlinked] keys): never linked again by the pass. */
    private val unlinked = HashSet<String>()
    private var itemId: String? = null

    /** The text the file is known to hold. Read and written only on Main, inside [ops]. */
    private var savedText: String? = null
    private var coverText: String? = null
    private var opened = false
    private var started = false
    private var closing = false
    private var tooLongTold = false

    private val autosave = Runnable { save() }

    private lateinit var tools: EditorTools
    private lateinit var format: FormatActions
    private lateinit var shortcuts: EditorShortcuts
    private lateinit var overflow: FormatBarOverflow
    private lateinit var findBar: FindReplaceBar
    private lateinit var textSize: TextSizeControl
    private lateinit var proofread: ProofreadController
    private lateinit var links: DocumentLinksControl
    private lateinit var bibleLinks: BibleLinkController
    private lateinit var inkPaste: InkPaste

    /** Soil's item picker, for the Link dialog's Choose from library. */
    private val linkPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (::links.isInitialized) links.onPicked(result.resultCode, result.data)
    }

    /** Where a plain newline was just typed: read (and cleared) in `afterTextChanged`, which is
     *  where the text may be edited. Clearing it before use is also the re-entrancy guard: the
     *  list edit re-enters the watcher, and the re-entry finds nothing to do. */
    private var newlineAt = -1

    /** True while the Markdown source is the surface in use. A document opens rendered. */
    private var sourceShowing = false

    /** The Markdown the rendered document was read from, and whether it has been edited since. */
    private var richSource = ""
    private var richDirty = false

    private fun rendered(): Boolean = !sourceShowing
    private fun surface(): EditText = if (sourceShowing) binding.editor else binding.rich

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        prefs = DocsproutPrefs(this)
        binding.btnBack.setOnClickListener { finish() }
        buildChrome()
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Watching the text rather than the Enter key covers both keyboards: a soft
                // keyboard commits "\n" through the input connection and may send no key event.
                newlineAt = if (before == 0 && count == 1 && s?.getOrNull(start) == '\n') start else -1
            }

            override fun afterTextChanged(s: Editable?) {
                val at = newlineAt
                newlineAt = -1
                if (!opened) return
                if (at >= 0 && s != null) tools.continueListAt(s, at)
                main.removeCallbacks(autosave)
                main.postDelayed(autosave, AUTOSAVE_DELAY_MS)
            }
        })
        // After the screen's own watchers, so proofread's run second: never mid-list-continuation.
        proofread = ProofreadController.install(this, listOf(binding.editor, binding.rich), { if (sourceShowing) binding.editor else binding.rich }, lifecycleScope)
        links = DocumentLinksControl(
            this, binding, prefs, itemId = { itemId }, usable = { opened && !closing },
            saveNow = ::save, editLink = { format.run(FormatTool.LINK) }, pickerLauncher = linkPicker,
            unlinkBible = ::rememberUnlinked,
        )
        links.install()
        bibleLinks = BibleLinkController(binding.rich, binding.editor, ::rendered, usable = { opened && !closing }, lifecycleScope, unlinked = { unlinked })
        inkPaste = InkPaste(this, usable = { opened && !closing }, insert = ::insertParagraphs)
        // Ctrl+V and the text menu's Paste put in the last thing copied: the clipboard's ink, as
        // words, when it was copied after the text on the device's own clipboard.
        val pasteLatest = { if (opened && !closing && inkPaste.newerThanText()) { inkPaste.paste(); true } else false }
        binding.editor.onPaste = pasteLatest
        binding.rich.onPaste = pasteLatest
        // A tap on a link follows it; any other tap is proofread's to answer.
        val flagTap = binding.rich.onWordTap
        binding.rich.onWordTap = { offset -> if (!(opened && !sourceShowing && links.followAtChar(binding.rich.tappedChar))) flagTap?.invoke(offset) }
        binding.rich.onEdited = { words ->
            if (opened) {
                // A style or a block may have changed what is prose: a word made code is not
                // judged. A change of words is one proofread's own watcher has already seen.
                if (!words) proofread.styleChanged()
                richDirty = true
                main.removeCallbacks(autosave)
                main.postDelayed(autosave, AUTOSAVE_DELAY_MS)
            }
        }
        lifecycleScope.launch { open() }
    }

    /**
     * The document as Markdown, as it stands now. From the rendered surface it is written only
     * when something was edited there; until then it is the Markdown that was read.
     */
    private fun currentMarkdown(): String {
        if (sourceShowing) return binding.editor.text?.toString().orEmpty()
        if (richDirty) {
            richSource = RichWrite.write(binding.rich.document()).text
            richDirty = false
        }
        return richSource
    }

    /** Between the rendered document and its source, with the caret carried to the same block. */
    private fun toggleMode() {
        if (!opened) return
        if (findBar.isOpen()) findBar.close()
        if (!sourceShowing) {
            val block = binding.rich.blockIndexAt(binding.rich.selectionEnd.coerceAtLeast(0))
            val offsets = if (richDirty) {
                val written = RichWrite.write(binding.rich.document())
                richSource = written.text
                richDirty = false
                written.offsets
            } else {
                RichParse.parse(richSource).offsets
            }
            sourceShowing = true
            binding.editor.setText(richSource)
            binding.editor.setSelection((offsets.getOrNull(block) ?: richSource.length).coerceIn(0, richSource.length))
        } else {
            val markdown = binding.editor.text?.toString().orEmpty()
            val caret = binding.editor.selectionEnd.coerceAtLeast(0)
            val parsed = RichParse.parse(markdown)
            val block = parsed.offsets.indexOfLast { it <= caret }.coerceAtLeast(0)
            sourceShowing = false
            richSource = markdown
            richDirty = false
            binding.rich.load(parsed.doc)
            binding.rich.setSelection(binding.rich.offsetOfBlock(block))
        }
        binding.editor.visibility = if (sourceShowing) View.VISIBLE else View.GONE
        binding.rich.visibility = if (sourceShowing) View.GONE else View.VISIBLE
        binding.btnMode.isSelected = sourceShowing
        surface().requestFocus()
        surface().post { tools.keepCaretVisible() }
        // The flags were on the other surface's text: this one is checked from the top.
        proofread.checkDocument()
        bibleLinks.checkDocument()
    }

    /** The bar, its overflow, the chords, find, the tools, the text size and the rename. */
    private fun buildChrome() {
        tools = EditorTools(this, binding, ::surface, ::rendered, onEdited = ::save)
        findBar = FindReplaceBar(this, binding, ::surface, ::rendered, keepCaretVisible = { tools.keepCaretVisible() }, onReplacedAll = ::save)
        format = FormatActions(
            binding, ::rendered,
            onSearch = { if (findBar.isOpen()) findBar.close() else findBar.open() },
            onWordCount = { tools.showWordCount() },
            onReflow = { tools.reflow() },
            onProofread = { proofread.promptProofread() },
            onPasteInk = { inkPaste.prompt() },
            askLink = { current, apply ->
                LinkDialog.ask(this, current, onChooseFromLibrary = { links.chooseFromLibrary(apply) }) { typed ->
                    // Words that read as a Bible reference link into the Bible, under the words as typed.
                    val wire = if (typed.isEmpty()) null else BibleLinks.wireOf(typed)
                    if (wire != null) apply(BibleLinks.addressOf(wire), typed) else apply(typed, typed)
                }
            },
        )
        val controls = FormatBar.build(
            binding.formatBar,
            onTool = { if (opened) format.run(it) },
            onToolUsed = { overflow.close() },
            onOverflow = { overflow.toggle() },
        )
        overflow = FormatBarOverflow(binding.formatBar, binding.overflowPanel, controls.dividerOverflow, controls.btnOverflow)
        overflow.watchWidth()
        shortcuts = EditorShortcuts(format, ::rendered, ::toggleMode, closeOverflow = { overflow.close() })
        binding.btnMode.setOnClickListener { toggleMode() }
        binding.btnExport.setOnClickListener { export() }
        TooltipCompat.setTooltipText(binding.btnExport, binding.btnExport.contentDescription)
        TooltipCompat.setTooltipText(binding.btnMode, binding.btnMode.contentDescription)
        findBar.install()
        tools.watchHeight()
        textSize = TextSizeControl(this, binding, prefs)
        textSize.restore()
        binding.btnTextSize.setOnClickListener { textSize.prompt() }
        TooltipCompat.setTooltipText(binding.btnTextSize, binding.btnTextSize.contentDescription)
        RenameControl(this, binding, lifecycleScope) { name ->
            val id = itemId ?: throw IllegalStateException("no document")
            kotlinx.coroutines.runBlocking { (application as DocsproutApp).soil.seam() }.renameItem(id, name)
        }.install()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (opened && shortcuts.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** A tap anywhere that is not the bar or its panel puts the overflow away, and still lands. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        overflow.dismissIfOutside(event)
        return super.dispatchTouchEvent(event)
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

    /** A Bible link taken off: remembered now, and with the document, so no pass puts it back. */
    private fun rememberUnlinked(words: String, wire: String) {
        unlinked += BibleUnlinked.key(words, wire)
        bibleLinks.bump()
        val documents = store ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { documents.forget(words, wire) }.onFailure { Log.w(TAG, "an unlinked reference was not remembered: ${it.javaClass.simpleName}") }
        }
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
                    unlinked.clear()
                    unlinked += runCatching { documents.unlinked() }.getOrDefault(emptySet())
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
        richSource = body
        richDirty = false
        // Where the cursor was left, or the top.
        binding.rich.load(RichParse.parse(body).doc, itemId?.let { prefs.caret(it) } ?: 0)
        binding.opening.visibility = View.GONE
        binding.rich.visibility = View.VISIBLE
        binding.rich.requestFocus()
        binding.rich.post { tools.keepCaretVisible() }
        opened = true
        itemId?.let { links.arrived(it) }
        proofread.checkDocument()
        bibleLinks.checkDocument()
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
        val text = currentMarkdown()
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

    // ── Ink from the clipboard, as words ──────

    /**
     * The clipboard's handwriting, read, put in at the cursor of whichever surface is in use. In the
     * rendered document each paragraph is a block and the words are words, whatever characters
     * they hold; in the source each is a Markdown paragraph, a blank line apart.
     */
    private fun insertParagraphs(paragraphs: List<String>) {
        if (!opened || closing || paragraphs.isEmpty()) return
        if (sourceShowing) {
            val text = binding.editor.text ?: return
            val a = minOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, text.length)
            val b = maxOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, text.length)
            val words = paragraphs.joinToString("\n\n")
            text.replace(a, b, words)
            binding.editor.setSelection((a + words.length).coerceAtMost(text.length))
        } else {
            val words = paragraphs.joinToString("\n")
            RichOps.insertText(binding.rich, words, words.length, words.length)
        }
        surface().requestFocus()
        surface().post { tools.keepCaretVisible() }
        bibleLinks.checkDocument()
    }

    override fun onResume() {
        super.onResume()
        // The pad, or a notebook, may have copied ink while this screen was behind.
        if (::inkPaste.isInitialized) inkPaste.refresh()
    }

    // ── Export ──────

    /**
     * Soil's export screen for this document. The file must be free for it, so the words are
     * saved, the cover written and the document closed first, in the queue's order; Soil opens
     * the document again on the way back.
     */
    private fun export() {
        if (!opened || closing) return
        val id = itemId ?: return
        if (isSavedOrSaveable().not()) return
        closing = true
        save()
        writeCover()
        // What the ways out would do has been done: they find nothing open.
        opened = false
        letGo()
        appScope.launch {
            ops.withLock { }
            val started = runCatching {
                startActivity(
                    Intent(Seam.ACTION_EXPORT).setPackage(com.symmetricalpalmtree.soil.docsprout.BuildConfig.SOIL_PACKAGE)
                        .putExtra(Seam.EXTRA_ITEM_ID, id)
                        .putExtra(Seam.EXTRA_RETURN_TO_APP, true),
                )
            }.isSuccess
            if (started) finish()
            else if (!isFinishing && !isDestroyed) {
                Dialogs.style(
                    AlertDialog.Builder(this@DocumentActivity)
                        .setTitle(R.string.export_failed_title)
                        .setMessage(R.string.export_open_failed_body)
                        .setPositiveButton(com.symmetricalpalmtree.soil.paper.R.string.ok) { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .create(),
                ).show()
            }
        }
    }

    /** A document too long to save is not exported as something it is not: it says so instead. */
    private fun isSavedOrSaveable(): Boolean {
        if (DocumentLimits.fits(currentMarkdown())) return true
        Dialogs.problem(this, R.string.too_long_title, R.string.too_long_body)
        return false
    }

    // ── Park and resume ──────

    override fun onStart() {
        super.onStart()
        started = true
        if (opened) links.refreshBacklinks()
        val open = session ?: return
        appScope.launch {
            ops.withLock { withContext(Dispatchers.IO) { runCatching { open.resume() }.onFailure { Log.w(TAG, "resume failed: ${it.javaClass.simpleName}") } } }
        }
    }

    override fun onPause() {
        // The place in the rendered document, which is what a document opens on.
        if (opened && !sourceShowing) itemId?.let { prefs.rememberCaret(it, binding.rich.selectionEnd.coerceAtLeast(0)) }
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
        val text = currentMarkdown()
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
        if (::proofread.isInitialized) proofread.dispose()
        if (::bibleLinks.isInitialized) bibleLinks.dispose()
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
