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
import com.symmetricalpalmtree.soil.markdown.MarkdownFormatter
import com.symmetricalpalmtree.soil.docsprout.data.BibleUnlinked
import com.symmetricalpalmtree.soil.docsprout.data.DocsproutPrefs
import com.symmetricalpalmtree.soil.docsprout.data.DocumentLimits
import com.symmetricalpalmtree.soil.docsprout.data.DocumentSchema
import com.symmetricalpalmtree.soil.docsprout.data.DocumentStore
import com.symmetricalpalmtree.soil.docsprout.databinding.ActivityDocumentBinding
import com.symmetricalpalmtree.soil.docsprout.editor.bible.BibleLinkController
import com.symmetricalpalmtree.soil.docsprout.editor.bible.ReferenceLinker
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
 * bar and its rows, the chords, find and replace, the tidying tools, the text size, the
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
    /** The words a failed save on the way out has already said were not saved. */
    private var unsavedTold: String? = null

    /** Only while the screen is up: once it stops, the file is parked, and the way back saves. */
    private val autosave = Runnable { if (started) save() }

    private lateinit var tools: EditorTools
    private lateinit var format: FormatActions
    private lateinit var shortcuts: EditorShortcuts
    private lateinit var rows: FormatBarRows
    private var barButtons: Map<FormatTool, View> = emptyMap()

    /** The bar wears what the caret is on (2026-10-10); nothing to wear before the document opens. */
    private fun wearState() {
        if (!opened || !::format.isInitialized) return
        FormatBar.wear(barButtons, format.state())
    }
    private lateinit var findBar: FindReplaceBar
    private lateinit var textSize: TextSizeControl
    private lateinit var proofread: ProofreadController
    private lateinit var links: DocumentLinksControl
    private lateinit var bibleLinks: BibleLinkController
    private lateinit var biblePaste: BiblePaste
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

    /** Bumped on every edit of the rendered document and every change of surface: a Markdown
     *  written from an older document is never taken as [richSource]. */
    private var richGen = 0

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
        installRelinkMenu()
        bibleLinks = BibleLinkController(binding.rich, binding.editor, ::rendered, usable = { opened && !closing }, lifecycleScope, unlinked = { unlinked })
        // Words arrive only while the screen is up: the file is parked once it stops, and nothing
        // may be put in that the way out has already passed.
        inkPaste = InkPaste(this, usable = { opened && !closing && started }, insert = ::insertParagraphs)
        biblePaste = BiblePaste(this, usable = { opened && !closing && started }, insertReference = ::insertReference, insertVerses = ::insertPassage)
        // Ctrl+V and the text menu's Paste put in the last thing copied: a passage from the
        // Bible, the clipboard's ink as words, or the text on the device's own clipboard.
        val pasteLatest = {
            when {
                !opened || closing -> false
                biblePaste.newerThan(inkPaste.copiedAt) -> { biblePaste.prompt(); true }
                inkPaste.newerThanText() -> { inkPaste.paste(); true }
                else -> false
            }
        }
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
                richGen++
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
            richGen++
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
            richGen++
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

    /** The bar, its rows, the chords, find, the tools, the text size and the rename. */
    private fun buildChrome() {
        tools = EditorTools(this, binding, ::surface, ::rendered, onEdited = ::save)
        findBar = FindReplaceBar(this, binding, ::surface, ::rendered, keepCaretVisible = { tools.keepCaretVisible() }, onReplacedAll = ::save)
        format = FormatActions(
            binding, ::rendered,
            onSearch = { if (findBar.isOpen()) findBar.close() else findBar.open() },
            onWordCount = { tools.showWordCount() },
            onReflow = { tools.reflow() },
            onProofread = { proofread.promptProofread() },
            // The Paste tool: the passage when one was copied after the ink, else the ink.
            onPasteInk = { if (biblePaste.newerThan(inkPaste.copiedAt)) biblePaste.prompt() else inkPaste.prompt() },
            onBiblePassage = { askPassage() },
            askLink = { current, apply ->
                // The Link tool is for addresses and the library. Only an existing Bible link's
                // Edit reads its field as a reference, since that is what the field shows for one;
                // a reference the writer unlinked comes back through the selection's Relink Bible.
                val editingBible = current != null && BibleLinks.labelOf(current) != null
                LinkDialog.ask(this, current, onChooseFromLibrary = { links.chooseFromLibrary(apply) }, onChooseDay = { links.chooseDay(current, apply) }) { typed ->
                    if (editingBible && typed.isEmpty()) {
                        // Remove on a Bible link is remembered as the link sheet's Remove is, so
                        // the pass does not put it back.
                        val words = RichOps.linkWordsAt(binding.rich)
                        val removed = current?.let { BibleLinks.wireOfAddress(it) }
                        apply("", "")
                        if (words != null && removed != null) rememberUnlinked(words, removed)
                        return@ask
                    }
                    val wire = if (editingBible && typed.isNotEmpty()) BibleLinks.wireOf(typed) else null
                    if (wire != null) apply(BibleLinks.addressOf(wire), typed) else apply(typed, typed)
                }
            },
        )
        val headingMenu = HeadingMenu(this) { level -> if (opened) { format.block(MarkdownFormatter.Block.HEADING, level); wearState() } }
        barButtons = FormatBar.build(
            binding.formatBar,
            onTool = { if (opened) { format.run(it); wearState() } },
            onHeading = { anchor -> if (opened) headingMenu.toggle(anchor, format.state().level) },
        )
        // The bar wears what the caret is on, at every move of it on either surface — chained
        // after whoever else watches the caret (the reference pass), never in its place.
        for (surface in listOf(binding.editor, binding.rich)) {
            val was = surface.onCaretMoved
            surface.onCaretMoved = { was?.invoke(); wearState() }
        }
        rows = FormatBarRows(binding.formatBar, binding.formatBarRows)
        rows.watchWidth()
        shortcuts = EditorShortcuts(format, ::rendered, ::toggleMode)
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

    // ── A Bible passage, as words ──────

    /** Insert a Bible passage: a reference typed, its verses read from the Bible's app through
     *  Soil, and put in at the caret under a link to the passage. */
    private fun askPassage() {
        if (!opened || closing) return
        PassageDialog.ask(this) { typed ->
            val wire = BibleLinks.wireOf(typed)
            if (wire == null) {
                Dialogs.problem(this, R.string.passage_not_reference_title, getString(R.string.passage_not_reference_body, typed))
                return@ask
            }
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { (application as DocsproutApp).soil.seam().passageText(wire) } }
                if (!opened || closing || !started) return@launch
                result.onSuccess { insertPassage(wire, it) }.onFailure { e ->
                    Log.w(TAG, "the verses could not be read: ${e.message ?: e.javaClass.simpleName}")
                    Dialogs.problem(
                        this@DocumentActivity, R.string.passage_failed_title,
                        when (e.message) {
                            Seam.BIBLE_NO_APP -> R.string.passage_no_app_body
                            Seam.BIBLE_TOO_LONG -> R.string.passage_too_long_body
                            else -> R.string.passage_failed_body
                        },
                    )
                }
            }
        }
    }

    /** A reference put in at the caret as a link, under its canonical label. */
    private fun insertReference(wire: String, label: String) {
        if (!opened || closing) return
        val address = BibleLinks.addressOf(wire)
        if (sourceShowing) {
            val text = binding.editor.text ?: return
            val a = minOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, text.length)
            val b = maxOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, text.length)
            val words = "[$label]($address)"
            text.replace(a, b, words)
            binding.editor.setSelection((a + words.length).coerceAtMost(text.length))
        } else {
            val a = minOf(binding.rich.selectionStart, binding.rich.selectionEnd).coerceAtLeast(0)
            RichOps.insertText(binding.rich, label, label.length, label.length)
            binding.rich.text?.let { s -> if (a + label.length <= s.length) RichOps.linkOver(s, a, a + label.length, address) }
            binding.rich.edited(words = false)
        }
        surface().requestFocus()
        surface().post { tools.keepCaretVisible() }
    }

    /**
     * The passage put in: the label as a link to the passage, then the verses, a paragraph per
     * chapter run as the reader wrote them. In the rendered document the words go in as words and
     * the link is a span; in the source they are Markdown.
     */
    private fun insertPassage(wire: String, markdown: String) {
        val label = BibleLinks.labelOf(BibleLinks.addressOf(wire)) ?: wire
        val address = BibleLinks.addressOf(wire)
        val paragraphs = PassageText.paragraphs(markdown)
        if (sourceShowing) {
            val text = binding.editor.text ?: return
            val a = minOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, text.length)
            val b = maxOf(binding.editor.selectionStart, binding.editor.selectionEnd).coerceIn(0, text.length)
            val words = (listOf("[$label]($address)") + paragraphs.map { it.markdown }).joinToString("\n\n")
            text.replace(a, b, words)
            binding.editor.setSelection((a + words.length).coerceAtMost(text.length))
        } else {
            val a = minOf(binding.rich.selectionStart, binding.rich.selectionEnd).coerceAtLeast(0)
            val words = (listOf(label) + paragraphs.map { it.plain }).joinToString("\n")
            RichOps.insertText(binding.rich, words, words.length, words.length)
            binding.rich.text?.let { s -> if (a + label.length <= s.length) RichOps.linkOver(s, a, a + label.length, address) }
            binding.rich.edited(words = false)
        }
        surface().requestFocus()
        surface().post { tools.keepCaretVisible() }
        bibleLinks.checkDocument()
    }

    /** The reference the caret or the selection touches in the rendered document, when it is one
     *  the writer unlinked: the one case the selection's menu offers Relink Bible for. */
    private fun unlinkedReferenceAtCaret(): ReferenceLinker.Hit? {
        val text = binding.rich.text?.toString() ?: return null
        val a = binding.rich.selectionStart
        val b = binding.rich.selectionEnd
        if (a < 0 || b < 0) return null
        val hit = ReferenceLinker.hitAt(text, a, b) ?: return null
        return hit.takeIf { BibleUnlinked.key(it.words, it.wire) in unlinked }
    }

    /**
     * **Relink Bible** on the selection's own menu, beside Cut, Copy and Paste, in the rendered
     * document: there when the caret or the selection touches a reference the writer took the
     * link off (Greg, 2026-10-05). One tap: the words are selected whole, linked, and the removal
     * forgotten, so the pass may link that reference again. The Link tool is left to addresses.
     */
    private fun installRelinkMenu() {
        val callback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                if (opened && !closing && unlinkedReferenceAtCaret() != null) {
                    // Last on the menu: the framework's own items (Cut, Copy, Paste, Select all,
                    // Share…) order below a hundred, and this is an occasional act.
                    menu.add(0, MENU_RELINK_BIBLE, MENU_RELINK_ORDER, R.string.bible_relink_action)
                }
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                if (item.itemId != MENU_RELINK_BIBLE) return false
                relinkBible()
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) = Unit
        }
        binding.rich.customSelectionActionModeCallback = callback
        binding.rich.customInsertionActionModeCallback = callback
    }

    private fun relinkBible() {
        if (!opened || closing || sourceShowing) return
        val hit = unlinkedReferenceAtCaret() ?: return
        binding.rich.setSelection(hit.start, hit.end)
        RichOps.setLink(binding.rich, hit.address, hit.words)
        allowAgain(hit.wire)
    }

    /** A reference linked again by hand: whatever removal was remembered for its wire is forgotten,
     *  in memory and with the document, and the pass may link it once more. */
    private fun allowAgain(wire: String) {
        if (!unlinked.removeAll { BibleUnlinked.names(it, wire) }) return
        bibleLinks.bump()
        val documents = store ?: return
        inQueue { runCatching { documents.allowAgain(wire) }.onFailure { Log.w(TAG, "an allowed reference was not remembered: ${it.javaClass.simpleName}") } }
    }

    /** A Bible link taken off: remembered now, and with the document, so no pass puts it back. */
    private fun rememberUnlinked(words: String, wire: String) {
        unlinked += BibleUnlinked.key(words, wire)
        bibleLinks.bump()
        android.widget.Toast.makeText(this, R.string.bible_unlinked_toast, android.widget.Toast.LENGTH_LONG).show()
        val documents = store ?: return
        inQueue { runCatching { documents.forget(words, wire) }.onFailure { Log.w(TAG, "an unlinked reference was not remembered: ${it.javaClass.simpleName}") } }
    }

    /** A write to the open file in the queue's order, on IO, and finished even if the screen goes. */
    private fun inQueue(body: suspend () -> Unit) {
        appScope.launch { withContext(NonCancellable) { ops.withLock { withContext(Dispatchers.IO) { body() } } } }
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
        wearState()
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
        val pending = saveTask() ?: return
        appScope.launch { withContext(NonCancellable) { pending() } }
    }

    /** [save], awaited: whether the file holds the text as it stood when this was called. */
    private suspend fun saveNow(): Boolean {
        val pending = saveTask() ?: return false
        return withContext(NonCancellable) { pending() }
    }

    /**
     * The text as it stands, read now on Main, and the write of it to run in the queue. The
     * rendered document is written as Markdown off Main, so a long document does not hold the
     * screen up. Null when there is nothing open to save to.
     */
    private fun saveTask(): (suspend () -> Boolean)? {
        main.removeCallbacks(autosave)
        if (!opened) return null
        val documents = store ?: return null
        val ready: String? = if (sourceShowing || !richDirty) currentMarkdown() else null
        val doc = if (ready == null) binding.rich.document() else null
        val gen = richGen
        return {
            ops.withLock {
                val text = ready ?: withContext(Dispatchers.Default) { RichWrite.write(doc!!).text }.also {
                    // Nothing edited since it was read: it is the Markdown of what is shown.
                    if (gen == richGen && !sourceShowing) { richSource = it; richDirty = false }
                }
                if (!DocumentLimits.fits(text)) {
                    if (!tooLongTold && !isFinishing && !isDestroyed) {
                        tooLongTold = true
                        Dialogs.problem(this@DocumentActivity, R.string.too_long_title, R.string.too_long_body)
                    }
                    return@withLock false
                }
                tooLongTold = false
                if (text == savedText) return@withLock true
                // On the way out there is no later save: one more try, then say so, once for
                // these words (the pause and the stop both try; an export says so itself, and stays).
                val leaving = !started || closing || isFinishing
                var landed = write(documents, text)
                if (!landed && leaving) landed = write(documents, text)
                when {
                    landed -> { savedText = text; unsavedTold = null }
                    !leaving -> main.postDelayed(autosave, AUTOSAVE_DELAY_MS)
                    !closing && unsavedTold != text -> {
                        unsavedTold = text
                        android.widget.Toast.makeText(applicationContext, R.string.save_failed_toast, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                landed
            }
        }
    }

    private suspend fun write(documents: DocumentStore, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { documents.save(text) }.onFailure { Log.w(TAG, "save failed: ${it.javaClass.simpleName}") }.isSuccess
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
        if (::biblePaste.isInitialized) biblePaste.refresh()
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
        appScope.launch {
            // The file must hold the words before it is handed over: when the save did not land,
            // one more, awaited, and if that fails too the document stays open and says so.
            if (!saveNow() && opened) saveNow()
            if (!opened || isFinishing || isDestroyed) return@launch
            if (savedText != currentMarkdown()) {
                closing = false
                Dialogs.problem(this@DocumentActivity, R.string.export_failed_title, R.string.export_not_saved_body)
                return@launch
            }
            writeCover()
            // What the ways out would do has been done: they find nothing open.
            opened = false
            letGo()
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
        // Nothing to do when the pause's save landed; when it did not, the last try before the park.
        save()
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
                val cover = SeamShared.write(TextCover.encode(text))
                try {
                    (application as DocsproutApp).soil.seam().setCover(id, cover)
                } finally {
                    cover.memory.close()
                }
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
        private const val MENU_RELINK_BIBLE = 0x5B1B
        private const val MENU_RELINK_ORDER = 100
        private const val TAG = "DocumentActivity"
        private const val NO_SUCH_ITEM = "there is no such item"
        private const val AUTOSAVE_DELAY_MS = 2_000L
    }
}
