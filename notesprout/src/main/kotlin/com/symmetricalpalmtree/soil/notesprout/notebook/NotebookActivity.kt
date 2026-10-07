package com.symmetricalpalmtree.soil.notesprout.notebook

import android.graphics.drawable.Drawable
import android.os.Binder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperListener
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.engine.GPaper
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.SelectionMove
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.soil.markdown.HeadingPrefix
import com.symmetricalpalmtree.soil.notesprout.NotesproutApp
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.data.NotebookAction
import com.symmetricalpalmtree.soil.notesprout.data.NotebookDocument
import com.symmetricalpalmtree.soil.notesprout.data.NotebookPrefs
import com.symmetricalpalmtree.soil.notesprout.convert.ConvertFlow
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.data.NotebookStore
import com.symmetricalpalmtree.soil.notesprout.data.PageContent
import com.symmetricalpalmtree.soil.notesprout.data.PageRef
import com.symmetricalpalmtree.soil.notesprout.links.LinkFollowFlow
import com.symmetricalpalmtree.soil.notesprout.links.LinkPickerActivity
import com.symmetricalpalmtree.soil.notesprout.links.LinkPickerRelay
import com.symmetricalpalmtree.soil.notesprout.links.LinkTrail
import com.symmetricalpalmtree.soil.notesprout.links.PickerSource
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import com.symmetricalpalmtree.soil.notesprout.objects.PageLink
import com.symmetricalpalmtree.soil.notesprout.objects.TrailEntry
import com.symmetricalpalmtree.soil.notesprout.objects.PageLabels
import com.symmetricalpalmtree.soil.paper.templates.Bitmaps
import com.symmetricalpalmtree.soil.paper.templates.PaperSource
import com.symmetricalpalmtree.soil.paper.templates.TemplateFit
import com.symmetricalpalmtree.soil.paper.templates.TemplateImport
import com.symmetricalpalmtree.soil.paper.templates.TemplateNames
import com.symmetricalpalmtree.soil.paper.templates.TemplatePick
import com.symmetricalpalmtree.soil.seamkit.SeamUnavailable
import com.symmetricalpalmtree.soil.seam.SeamBacklink
import com.symmetricalpalmtree.soil.notesprout.objects.FreePlacement
import com.symmetricalpalmtree.soil.notesprout.objects.Heading
import com.symmetricalpalmtree.soil.notesprout.objects.OutlineTree
import com.symmetricalpalmtree.soil.notesprout.objects.PageSticky
import com.symmetricalpalmtree.soil.notesprout.objects.PageText
import com.symmetricalpalmtree.soil.notesprout.objects.SelectionMode
import com.symmetricalpalmtree.soil.notesprout.objects.SelectionModes
import com.symmetricalpalmtree.soil.notesprout.objects.StickyDefaults
import com.symmetricalpalmtree.soil.paper.chrome.PenIdle
import com.symmetricalpalmtree.soil.notesprout.databinding.ActivityNotebookBinding
import com.symmetricalpalmtree.soil.paper.chrome.CollapsedChrome
import com.symmetricalpalmtree.soil.paper.chrome.EraserBar
import com.symmetricalpalmtree.soil.paper.chrome.InkSelectionBar
import com.symmetricalpalmtree.soil.paper.chrome.LassoPopup
import com.symmetricalpalmtree.soil.paper.chrome.PageMath
import com.symmetricalpalmtree.soil.notesprout.clip.BibleClipboard
import com.symmetricalpalmtree.soil.notesprout.clip.ClipEnvelope
import com.symmetricalpalmtree.soil.notesprout.clip.ObjectPlacement
import com.symmetricalpalmtree.soil.notesprout.clip.SoilClipboard
import com.symmetricalpalmtree.soil.seam.TagRules
import com.symmetricalpalmtree.soil.paper.ink.InkWire
import com.symmetricalpalmtree.soil.notesprout.recognition.InkRecognition
import com.symmetricalpalmtree.soil.notesprout.recognition.SeamRecognizerPort
import com.symmetricalpalmtree.soil.paper.chrome.PageGestures
import com.symmetricalpalmtree.soil.paper.chrome.PaletteBar
import com.symmetricalpalmtree.soil.paper.chrome.PaperChrome
import com.symmetricalpalmtree.soil.paper.chrome.PaperToolbar
import com.symmetricalpalmtree.soil.paper.chrome.ShadeIcon
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.CoverSnapshot
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.InkTones
import com.symmetricalpalmtree.soil.paper.core.Immersive
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.ink.InkAction
import com.symmetricalpalmtree.soil.paper.ink.InkPage
import com.symmetricalpalmtree.soil.paper.ink.InkScreenActivity
import com.symmetricalpalmtree.soil.seam.ISeamItem
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamItem
import com.symmetricalpalmtree.soil.seam.SeamLimits
import com.symmetricalpalmtree.soil.seamkit.SeamRowStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * **The notebook.** A full screen of paper, opened by Soil with a notebook's id, or with the name
 * of a notebook to make. The skeleton is `:paper`'s [InkScreenActivity]; what is here is the
 * notebook's own: its pages and their persistence through the seam, the session's park and
 * resume, and its place as the app in front.
 *
 * **The rows live in Soil.** The notebook is held open through an [ISeamItem]; the screen parks
 * it when it leaves the front and takes it up again when it returns, so a notebook left in the
 * background holds no file. The device has no Back key: the top bar's close button is the way
 * out, and it closes the notebook in Soil.
 *
 * Frame silence: no app frame while `paper.isPenActive`. The exceptions are the pad's.
 */
class NotebookActivity : InkScreenActivity<NotebookAction>(), NotesproutApp.FrontPaper {

    private lateinit var binding: ActivityNotebookBinding
    private lateinit var toolbar: NotebookToolbar
    private lateinit var paletteBar: PaletteBar
    private lateinit var insertBar: InsertBar
    private lateinit var bibleRefs: BibleRefFlow
    private lateinit var objectBar: ObjectSelectionBar
    private lateinit var lassoPopup: LassoPopup
    private lateinit var tagsPopup: TagsPopup

    /** Whether the contact going down took the lasso popup with it: rewritten at every pointer
     *  down, read by `onPaperTapped`, so a tap spent on a dismissal is never also a paste. */
    private var tapDismissedPopup = false
    private lateinit var recents: RecentsPanel
    private lateinit var contents: ContentsPanel
    private lateinit var prefs: NotebookPrefs
    private lateinit var headingRenderer: HeadingRenderer
    private lateinit var textRenderer: TextRenderer
    private lateinit var stickyRenderer: StickyRenderer
    private lateinit var linkRenderer: LinkRenderer
    private lateinit var followFlow: LinkFollowFlow
    private lateinit var backlinks: EdgeListPanel<SeamBacklink>
    private var document: NotebookDocument? = null
    private var backlinksShowing = false

    /** What the picker's answer applies to, captured at its launch: the selection may not survive the round trip. */
    private var pendingWrap: Selection? = null
    private var pendingEdit: PageLink? = null
    private var pickerShowing = false
    /** A page landed through the picker: the history's page snapshots name a list that is gone. */
    private var pagesChangedUnderPicker = false
    private var recentsShowing = false
    private var contentsShowing = false
    private var contentsAvailable = false

    /** The tool armed before a landing took the lasso; put back at the selection's dismissal. */
    private var toolBeforeLanding: Tool? = null

    /** A selection to land inside the next dismissal, so a smart-lasso session stays alive. */
    private var pendingSelection: (() -> Unit)? = null

    /** The sticky editor showing: the note, and whether this is its first showing after an insert. */
    private var stickyInFlight: Pair<String, Boolean>? = null

    /** True between launching an in-app paper screen and its return: the session is not parked. */
    private var inAppHandoff = false

    private val density: Float get() = resources.displayMetrics.density
    private val scaledDensity: Float get() = resources.displayMetrics.scaledDensity

    private val editorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onEditorClosed() }
    private val pickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onPickerClosed(it.resultCode, it.data?.getStringExtra(LinkPickerActivity.EXTRA_RESULT_PAYLOAD)) }
    private val templatePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onTemplatePicked(it.resultCode, it.data?.getStringExtra(Seam.EXTRA_PICK)) }
    private val saveTemplateLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onSoilScreenClosed() }
    private val tagsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onSoilScreenClosed() }

    /** The pad is up over this notebook. It lives in a task of its own, so it cannot answer a
     *  result: the pipeline is reclaimed on resume instead. */
    private var padShowing = false

    /** The paper under the pages shown lately, by template row id: decoded off Main before the frame that paints it. */
    private val paperCache = LinkedHashMap<String, android.graphics.Bitmap>()
    private var soilScreenShowing = false

    /** Any binder of this app's own: Soil watches it, and closes the notebook if the app dies. */
    private val owner = Binder()
    private var session: ISeamItem? = null
    private var itemId: String? = null

    override val logTag: String get() = TAG
    override val screenRoot: View? get() = if (::binding.isInitialized) binding.root else null
    override val eraserButtonView: View? get() = if (::binding.isInitialized) binding.btnEraser else null
    override val topBarView: View? get() = if (::binding.isInitialized) binding.topBar else null
    override val bottomBarView: View? get() = if (::binding.isInitialized) binding.bottomBar else null
    override val openingOverlay: View? get() = if (::binding.isInitialized) binding.openingOverlay else null
    override val backButtonView: View? get() = if (::binding.isInitialized) binding.btnBack else null
    override val collapsedKnobView: ImageButton? get() = if (::binding.isInitialized) binding.collapsedKnob else null
    override val collapsedBarView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedBar else null
    override val collapsedOverflowView: LinearLayout? get() = if (::binding.isInitialized) binding.collapsedOverflow else null
    override val inkPage: InkPage? get() = document
    override val storeFailedTitleRes: Int get() = R.string.store_failed_title
    override val storeFailedBodyRes: Int get() = R.string.store_failed_body

    override val initialChromeHidden: Boolean get() = prefs.chromeHidden
    override fun onChromeChanged(hidden: Boolean) { prefs.chromeHidden = hidden }
    override fun record(action: InkAction) = undo.record(NotebookAction.Ink(action))
    override fun syncTool(tool: Tool) = toolbar.sync(tool)
    override fun armTool(tool: Tool) = toolbar.arm(tool)
    override fun showPage() = showPage(firstLoad = false)
    override suspend fun revert(action: NotebookAction) { document?.revert(action); preparePaper() }
    override suspend fun reapply(action: NotebookAction) { document?.reapply(action); preparePaper() }

    // ── Create ──────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = NotebookPrefs(this)
        binding = ActivityNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(window, binding.root)
        TopGuard.applyRootPadding(binding.root)

        paper = GPaper.create(this).also {
            binding.paperContainer.addView(
                it.asView(),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
        }
        Slog.d(TAG) { "engine=${paper.engineId}" }
        paper.smartLassoEnabled = true
        paper.scribbleEraseEnabled = true
        paper.directInk = true
        paper.setPaperListener(notebookListener)
        // Draw order is registration order: headings · texts · links below the ink, stickies above it.
        headingRenderer = HeadingRenderer(density, scaledDensity)
        textRenderer = TextRenderer(density, scaledDensity)
        val stickyIcon = { checkNotNull(AppCompatResources.getDrawable(this, com.symmetricalpalmtree.soil.paper.R.drawable.ic_sticker_2)).mutate() }
        stickyRenderer = StickyRenderer(stickyIcon())
        linkRenderer = LinkRenderer(density, scaledDensity, stickyIcon())
        paper.addContentRenderer(headingRenderer)
        paper.addContentRenderer(textRenderer)
        paper.addContentRenderer(linkRenderer)
        paper.addContentRenderer(stickyRenderer)
        followFlow = LinkFollowFlow(
            activity = this,
            soil = (application as NotesproutApp).soil,
            itemId = { itemId },
            displayedPageId = { document?.pageId.orEmpty() },
            pageIds = { document?.pages?.map { it.id }.orEmpty() },
            liveLinks = { document?.links?.values.orEmpty() },
            alive = { opened && !closing },
            navigateToPage = { pageId -> runPageOp { flipTo(document?.pages?.indexOfFirst { it.id == pageId } ?: -1) } },
            leaveFor = ::leaveFor,
            openElsewhere = ::openElsewhere,
            openBible = ::openBible,
            openCalendar = ::openCalendar,
            editLink = ::beginEdit,
            removeLink = ::unlink,
        )
        backlinks = EdgeListPanel(
            this, emptyRes = R.string.backlinks_empty,
            rowTitle = { it.sourceName },
            rowDetail = { getString(if (it.targetPageId == null) R.string.backlink_to_notebook else R.string.backlink_to_page) },
            onPick = ::followBacklink,
        )

        toolbar = NotebookToolbar(
            paper = paper,
            onSynced = { syncCollapsed() },
            bottomBar = binding.bottomBar,
            btnBack = binding.btnBack,
            btnPen = binding.btnPen,
            btnEraser = binding.btnEraser,
            btnLasso = binding.btnLasso,
            btnPrevPage = binding.btnPrevPage,
            btnNextPage = binding.btnNextPage,
            btnRecents = binding.btnRecents,
            title = binding.title,
            pageIndicator = binding.pageIndicator,
            penLevel = prefs.penLevel,
            onBack = { exit() },
            onPrevPage = { runPageOp { flipTo(pageIndex() - 1) } },
            // Past the last page the arrow makes one, as the swipe does (Greg, 2026-10-07).
            onNextPage = { gestureListener.onFlipNext() },
            onRecents = { showRecents() },
            // A second tap on the armed pen toggles its shade panel; on the armed eraser, its
            // sub-bar. Arming a different tool takes any bar with it.
            onPenReTap = { if (paletteBar.isShowing) hidePaletteBar() else showPaletteBar() },
            onEraserReTap = { hidePaletteBar(); toggleEraserBar() },
            onLassoReTap = { if (lassoPopup.isShowing) hideLassoPopup() else showLassoPopup() },
            onToolTapped = { hideFloatingBars() },
        )
        lassoPopup = LassoPopup(
            root = binding.root, bar = binding.lassoPopup, anchor = binding.btnLasso, bandBottom = { chromeBand()?.last },
            releaseRender = { paper.releaseRender() },
            onPaste = { hideLassoPopup(); doObjectPaste(tapX = null, tapY = null) },
            onClear = { hideLassoPopup(); doClipboardClear() },
        )
        tagsPopup = TagsPopup(
            root = binding.root, bar = binding.tagsPopup, anchor = binding.btnTag, bandBottom = { chromeBand()?.last },
            releaseRender = { paper.releaseRender() },
            onTagNotebook = { hideTagsPopup(); openTags(pageTarget = false, mode = Seam.TAG_MODE_ADD) },
            onTagPage = { hideTagsPopup(); openTags(pageTarget = true, mode = Seam.TAG_MODE_ADD) },
            onManage = { hideTagsPopup(); openTags(pageTarget = false, mode = Seam.TAG_MODE_MANAGE) },
        )
        binding.btnTag.setOnClickListener { paper.releaseRender(); if (tagsPopup.isShowing) hideTagsPopup() else showTagsPopup() }
        eraserBar = EraserBar(
            root = binding.root,
            bar = binding.eraserBar,
            anchor = binding.btnEraser,
            bandBottom = { chromeBand()?.last },
            paper = paper,
            onPicked = { hideFloatingBars(); toolbar.arm(it) },
        )
        paletteBar = PaletteBar(
            root = binding.root,
            bar = binding.paletteBar,
            anchor = binding.btnPen,
            bandBottom = { chromeBand()?.last },
            paper = paper,
            armedLevel = { prefs.penLevel },
            onPicked = { level ->
                prefs.penLevel = level
                applyPenShade()
            },
        )
        recents = RecentsPanel(this, onPick = ::switchTo)
        contents = ContentsPanel(this, onPick = { pageId -> runPageOp { flipTo(document?.pages?.indexOfFirst { it.id == pageId } ?: -1) } })
        insertBar = InsertBar(
            root = binding.root, bar = binding.insertBar, anchor = binding.btnInsert, bandBottom = { chromeBand()?.last },
            releaseRender = { paper.releaseRender() },
            onInsert = { kind -> hideInsertBar(); insert(kind) },
        )
        binding.btnInsert.setOnClickListener { paper.releaseRender(); if (insertBar.isShowing) hideInsertBar() else showInsertBar() }
        binding.btnContents.setOnClickListener { PenIdle.releaseRenderIfIdle(paper); showContents() }
        bibleRefs = BibleRefFlow(
            this,
            object : BibleRefFlow.Host {
                override val alive: Boolean get() = opened && !closing
                override val document: NotebookDocument? get() = this@NotebookActivity.document
                override val recognizerPort: SeamRecognizerPort get() = this@NotebookActivity.recognizerPort
                override val density: Float get() = this@NotebookActivity.density
                override val scaledDensity: Float get() = this@NotebookActivity.scaledDensity
                override fun occupied(): List<Bounds> = this@NotebookActivity.occupied()
                override fun landReference(pageId: String, strokeIds: List<String>, text: PageText, payload: String, label: String) =
                    this@NotebookActivity.landReference(pageId, strokeIds, text, payload, label)
                override fun relandReference(pageId: String, link: PageLink, before: PageText, after: PageText, payload: String, label: String) =
                    this@NotebookActivity.relandReference(pageId, link, before, after, payload, label)
                override suspend fun passageText(wire: String): String? = withContext(Dispatchers.IO) {
                    runCatching { (application as NotesproutApp).soil.seam().passageText(wire) }
                        .onFailure { Log.w(TAG, "the verses could not be read: ${it.message ?: it.javaClass.simpleName}") }
                        .getOrNull()
                }
            },
        )
        objectBar = ObjectSelectionBar(
            root = binding.root, paperView = paper.asView(), bar = binding.selectionToolbar, subBar = binding.selectionSubBar,
            band = { chromeBand() }, releaseRender = { paper.releaseRender() },
            onLevelPicked = ::setHeadingLevel,
            onDelete = { currentSelection?.let { deleteSelected(it) } },
            onLink = { currentSelection?.let { beginWrap(it) } },
            onEditLink = { loneLink()?.let { beginEdit(it) } },
            onUnlink = { loneLink()?.let { unlink(it) } },
            onCopy = { cut -> currentSelection?.let { doObjectCopy(it, cut) } },
            onTag = { currentSelection?.let { tagSelection(it) } },
            onSend = { currentSelection?.let { askPadPlacement(it) } },
            onMakeText = { currentSelection?.let { convertToText(it) } },
            onBible = { currentSelection?.let { bibleRefs.convert(it) } },
            onVerses = { loneLink()?.let { bibleRefs.expand(it) } },
        )
        // The base's own bar is never shown here: the notebook's selection bar knows objects.
        selectionBar = InkSelectionBar(
            root = binding.root, paperView = paper.asView(), bar = LinearLayout(this), band = { chromeBand() },
            releaseRender = {}, deleteHint = "", onDelete = {},
        )
        chrome = PaperChrome(
            paper = paper,
            topBar = binding.topBar,
            bottomStrip = binding.bottomBar,
            extraRects = { floatingRects() },
            extraContains = { x, y -> floatingContains(x, y) },
            blockAll = { !opened || recentsShowing || contentsShowing || backlinksShowing },
        )
        gestures = PageGestures(
            host = paper.asView(),
            isPenActive = { paper.isPenActive },
            standDown = { selectionActive },
            overChrome = { chrome.overChrome(it) },
            listener = gestureListener,
        )
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> binding.root.post { pushExclusions() } }
        initChrome(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { exit() }
        })
        pushExclusions()

        lifecycleScope.launch { openNotebook() }
    }

    /**
     * One notebook screen at a time. Asked for the notebook already showing, it stays; asked for
     * another, or a new one, it lets this one go, flushed and closed, and starts again with the
     * new ask. What was undoable in the old notebook goes with it.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val askedId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        val newName = intent.getStringExtra(Seam.EXTRA_NEW_NAME)
        if (newName.isNullOrBlank() && askedId != null && askedId == itemId) {
            // The library's search landed on a page of the notebook already showing.
            intent.getStringExtra(Seam.EXTRA_PAGE_ID)?.let { pageId -> runPageOp { flipTo(document?.pages?.indexOfFirst { it.id == pageId } ?: -1) } }
            return
        }
        setIntent(intent)
        if (closing) return
        closing = true
        val page = inkPage
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock { runCatching { page?.flushUntilClean() }.onFailure { Log.w(TAG, "flush failed: ${it.javaClass.simpleName}") } }
            }
            if (!isFinishing && !isDestroyed) {
                paper.releaseForHandoff()
                recreate()
            }
        }
    }

    // ── Open ──────

    private suspend fun openNotebook() {
        val newName = intent.getStringExtra(Seam.EXTRA_NEW_NAME)
        val askedId = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        if (newName.isNullOrBlank() && askedId.isNullOrEmpty()) {
            refuse(R.string.open_missing)
            return
        }
        val soil = (application as NotesproutApp).soil
        val (w, h) = surfaceSize()
        val doc = try {
            withContext(Dispatchers.IO) {
                val seam = soil.seam()
                val item = if (!newName.isNullOrBlank()) seam.createItem(newName, NotebookSchema.SCHEMA)
                else seam.item(askedId!!) ?: throw IllegalStateException(NO_SUCH_ITEM)
                val opened = seam.openItem(item.id, NotebookSchema.SCHEMA, owner)
                session = opened
                itemId = item.id
                val store = NotebookStore(SeamRowStore(opened), item.id)
                storeRef = store
                // A file Soil made and nobody has written yet has no pages: it gets its first here,
                // the size of this surface, like a notebook made by name.
                val loaded = if (!newName.isNullOrBlank()) {
                    store.initialize(item.name, w, h)
                } else {
                    try {
                        store.load()
                    } catch (e: NotebookStore.NoPages) {
                        store.initialize(item.name, w, h)
                    }
                }
                // The library learns the page order at every open and every change: it names a
                // tagged page by it without ever opening the file.
                runCatching { seam.setPages(item.id, loaded.pages.map { it.id }) }.onFailure { Log.w(TAG, "the pages were not told: ${it.javaClass.simpleName}") }
                // Read again, never once per process: the calendar or the pad may have copied
                // since the last notebook this process opened read it (Greg, 2026-10-06).
                SoilClipboard.refresh(seam)
                BibleClipboard.refresh(seam)
                val document = NotebookDocument(store) { pages ->
                    withContext(Dispatchers.IO) { runCatching { soil.seam().setPages(item.id, pages.map { it.id }) } }
                }
                document.onObjectsChanged = ::syncRenderers
                document.density = density
                document.measureHeading = { h -> HeadingRenderer.measure(h.text, density, scaledDensity).let { (w, hh) -> if (w == h.width && hh == h.height) h else h.copy(width = w, height = hh) } }
                document.measureText = { t, pageW -> TextRenderer.measure(t.text, (pageW - t.x).toInt(), density, scaledDensity).let { (w, hh) -> if (w == t.width && hh == t.height) t else t.copy(width = w, height = hh) } }
                document.load(loaded)
                // Soil's New Notebook chose the paper: the first page takes it, as any pick is
                // taken, and not as an undo step. Consumed once.
                intent.getStringExtra(Seam.EXTRA_TEMPLATE_PICK)?.let { encoded ->
                    intent.removeExtra(Seam.EXTRA_TEMPLATE_PICK)
                    val pick = TemplatePick.decode(encoded)
                    if (pick != null && pick !is TemplatePick.Blank) {
                        runCatching {
                            val paper = paperOf(pick)
                            if (paper != null) {
                                document.changeTemplate(paper, resources.displayMetrics.densityDpi.toFloat())
                                runCatching { seam.templateUsed(pick.cardId) }
                            }
                        }.onFailure { Log.w(TAG, "the new notebook's paper could not be laid: ${it.javaClass.simpleName}") }
                    }
                }
                // Opened via a link: land on the page the link named, once. Any other open
                // starts a new story, and the old trail would walk back into someone else's.
                val initialPage = intent.getStringExtra(EXTRA_INITIAL_PAGE_ID) ?: intent.getStringExtra(Seam.EXTRA_PAGE_ID)
                if (initialPage != null) {
                    intent.removeExtra(EXTRA_INITIAL_PAGE_ID)
                    intent.removeExtra(Seam.EXTRA_PAGE_ID)
                    document.pages.firstOrNull { it.id == initialPage }?.let { document.goTo(it) }
                }
                if (!intent.getBooleanExtra(EXTRA_VIA_LINK, false)) LinkTrail(this@NotebookActivity).clear()
                intent.removeExtra(EXTRA_VIA_LINK)
                prefs.lastNotebookId = item.id
                document to item.name
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the notebook could not be opened: ${e.javaClass.simpleName}")
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
        document = doc.first
        toolbar.setTitle(doc.second)
        preparePaper()
        showPage(firstLoad = true, prebuilt = linkRenderer.prebuild(doc.first.links.values.toList()))
        opened = true
        markClipboard(SoilClipboard.hasObjects || BibleClipboard.has)
        pushExclusions()
        // Not pen-idle-gated: the pen is already over the glass on its way to write. A boundary
        // frame, not a frame during writing.
        binding.openingOverlay.visibility = View.GONE
        refreshContents()
        Slog.d(TAG) { "opened: ${doc.first.pageCount} pages" }
    }

    /** A notebook that did not open is explained, and then the screen leaves as every exit does. */
    private fun refuse(bodyRes: Int) {
        openingOverlay?.visibility = View.GONE
        if (isFinishing || isDestroyed) return
        closing = true
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.open_failed_title)
                .setMessage(bodyRes)
                .setPositiveButton(com.symmetricalpalmtree.soil.paper.R.string.ok) { _, _ -> finishWithHandoff() }
                .setOnCancelListener { finishWithHandoff() }
                .create(),
        ).show()
    }

    // ── Page gestures → operations ──────

    private val gestureListener = object : PageGestures.Listener {
        override fun onFlipNext() = runPageOp {
            // Swiping past the last page makes one: the notebook grows where you write.
            val doc = document ?: return@runPageOp
            if (doc.pageIndex < doc.pageCount - 1) flipTo(doc.pageIndex + 1) else doInsert(after = true)
        }
        override fun onFlipPrevious() = runPageOp { flipTo(pageIndex() - 1) }
        override fun onInsertAfter() = runPageOp { doInsert(after = true) }
        override fun onInsertBefore() = runPageOp { doInsert(after = false) }
        override fun onUndo() = runPageOp { doUndo() }
        override fun onRedo() = runPageOp { doRedo() }
        override fun onPageSheetRequested() = showPageSheet()
        override fun onTwoFingerSwipeDown() = showRecents()
        override fun onSwipeDown() = showContents()
        /** A sticky's icon sits above everything, so it is asked first; then the links. */
        override fun onFingerTap(x: Float, y: Float) { if (!openStickyAt(x, y)) followFlow.followAt(x, y) }
        override fun onSwipeUp() { if (opened && !closing) followFlow.walkBack { } }
        override fun onFingerDoubleTap(x: Float, y: Float) = toggleChrome()
    }

    // ── The paper's callbacks: ink to the base, objects here ──────

    private val notebookListener = object : PaperListener {
        override fun onStrokeCommitted(stroke: Stroke) = paperListener.onStrokeCommitted(stroke)
        override fun onStrokesErased(strokeIds: List<String>) = paperListener.onStrokesErased(strokeIds)
        override fun onToolChanged(tool: Tool) = paperListener.onToolChanged(tool)
        override fun onPenLifted() = paperListener.onPenLifted()

        /** The eraser swept an object: it goes, and comes back whole on undo. */
        override fun onContentErased(contentIds: List<String>) = erased(emptyList(), contentIds)

        /** One gesture that took ink and objects together is one undo step. */
        override fun onScribbleErased(strokeIds: List<String>, contentIds: List<String>) = erased(strokeIds, contentIds)
        override fun onLassoErased(strokeIds: List<String>, contentIds: List<String>) = erased(strokeIds, contentIds)

        override fun onSelectionCreated(selection: Selection) {
            selectionActive = true
            currentSelection = selection
            // Shown at once, not through the pen-idle gate: a lasso ends with the pen hovering.
            showObjectBar(selection)
        }

        /** A stylus tap on bare paper under the lasso with nothing selected: paste here, centred
         *  on the tap. Silent when the clipboard holds no objects: nothing was offering a paste. A
         *  contact spent taking the popup down is not a placement. */
        override fun onPaperTapped(x: Float, y: Float) {
            if (!opened || closing) return
            if (tapDismissedPopup) return
            if (!SoilClipboard.hasObjects && !BibleClipboard.has) return
            doObjectPaste(tapX = x, tapY = y)
        }

        override fun onSelectionDragStarted() {
            objectBar.hide()
            pushExclusions()
        }

        override fun onSelectionMoved(move: SelectionMove) {
            if (!opened || closing) return
            val doc = document ?: return
            val ink = doc.move(move.strokeIds, move.dx, move.dy)
            currentSelection = currentSelection?.let { it.copy(bounds = it.bounds.offset(move.dx, move.dy)) }
            if (move.contentIds.isEmpty()) {
                ink?.let { record(it); scheduleSave() }
            } else {
                // The working copies move now, in this callback, and the engine re-records at once:
                // its committed picture still holds the objects where they were. The rows follow.
                val moved = doc.translateObjects(move.contentIds, move.dx, move.dy)
                paper.notifyContentChanged()
                if (!moved.isEmpty || ink != null) {
                    undo.record(NotebookAction.Moved(doc.pageId, ink, moved.headingIds, moved.textIds, moved.stickyIds, move.dx, move.dy, moved.linkIds))
                }
                runPageOp {
                    doc.writeMove(moved, move.dx, move.dy)
                    doc.flushUntilClean()
                }
            }
            currentSelection?.let { showObjectBar(it) }
        }

        override fun onSelectionDismissed() {
            selectionActive = false
            currentSelection = null
            objectBar.hide()
            pushExclusions()
            val successor = pendingSelection
            pendingSelection = null
            if (successor != null) successor() else restoreToolAfterLanding()
        }

        /**
         * A stylus tap inside the selection. On a lone heading or text it opens the words. On a
         * selection that holds more, a tap on one of its objects **narrows** the selection to that
         * object alone, topmost first (a sticky, then a link, then a text, then a heading): the
         * way to pick one thing out of a cluster the lasso caught whole.
         */
        override fun onSelectionTapped(x: Float, y: Float) {
            val sel = currentSelection ?: return
            val doc = document ?: return
            val lone = if (sel.strokeIds.isEmpty()) sel.contentIds.singleOrNull() else null
            if (lone != null) {
                doc.headings[lone]?.let { editHeading(it); return }
                doc.texts[lone]?.let { editText(it) }
                return
            }
            val hit = sel.contentIds.firstOrNull { doc.stickies[it]?.bounds?.contains(x, y) == true }
                ?: sel.contentIds.lastOrNull { doc.links[it]?.bounds?.contains(x, y) == true }
                ?: sel.contentIds.lastOrNull { doc.texts[it]?.bounds?.contains(x, y) == true }
                ?: sel.contentIds.lastOrNull { doc.headings[it]?.bounds?.contains(x, y) == true }
                ?: return
            val bounds = doc.stickies[hit]?.bounds ?: doc.links[hit]?.bounds ?: doc.texts[hit]?.bounds ?: doc.headings[hit]?.bounds ?: return
            paper.setSelection(emptySet(), setOf(hit), bounds)
            val narrowed = Selection(emptySet(), setOf(hit), bounds)
            currentSelection = narrowed
            showObjectBar(narrowed)
        }

    }

    /** Hand the working copies to the renderers. The repaint is the caller's. A link's composite
     *  is built here when none was [prebuilt] off Main. */
    private fun syncRenderers(prebuilt: Map<String, android.graphics.Bitmap> = emptyMap()) {
        val doc = document ?: return
        headingRenderer.headings = doc.headings.values.toList()
        textRenderer.texts = doc.texts.values.toList()
        stickyRenderer.stickies = doc.stickies.values.toList()
        linkRenderer.update(doc.links.values.toList(), prebuilt)
    }

    private fun syncRenderers() = syncRenderers(emptyMap())

    /** Every box already on the page, for a drop that must not land on what is there. */
    private fun occupied(): List<Bounds> {
        val doc = document ?: return emptyList()
        return doc.headings.values.map { it.bounds } + doc.texts.values.map { it.bounds } + doc.stickies.values.map { it.bounds } +
            doc.links.values.map { it.bounds } + doc.strokes.map { it.bounds }
    }

    // ── Selection ──────

    private fun showObjectBar(sel: Selection) {
        val doc = document ?: return
        val mode = SelectionModes.classify(
            strokeCount = sel.strokeIds.size, contentIds = sel.contentIds,
            isHeading = { it in doc.headings }, isLink = { it in doc.links }, isText = { it in doc.texts },
            isSticky = { it in doc.stickies },
        )
        val level = sel.contentIds.singleOrNull()?.let { doc.headings[it]?.level }
        val lone = sel.contentIds.singleOrNull()?.takeIf { sel.strokeIds.isEmpty() }?.let { doc.links[it] }
        val reference = lone != null && LinkPayload.referenceOf(lone.payload) != null && !LinkPayload.isBibleText(lone.payload)
        objectBar.show(sel.bounds, mode, level, bibleReference = reference)
        pushExclusions()
    }

    /** Arm the lasso before a selection lands under another tool: a selection under the pen is a
     *  picture the pen inks through. The prior tool comes back at the dismissal. */
    private fun armLassoForLanding() {
        if (paper.tool == Tool.LASSO) return
        toolBeforeLanding = paper.tool
        toolbar.arm(Tool.LASSO)
    }

    /** Only while the lasso is still armed (a tool picked meanwhile wins), and pen-idle: a tool
     *  change under a gesture in flight leaves the engine with a selection no tool owns. */
    private fun restoreToolAfterLanding() {
        val tool = toolBeforeLanding ?: return
        toolBeforeLanding = null
        if (paper.tool != Tool.LASSO) return
        whenPenIdle {
            if (isFinishing || isDestroyed || paper.tool != Tool.LASSO) return@whenPenIdle
            toolbar.arm(tool)
        }
    }

    /** Land the selection on one object, host-initiated: no `onSelectionCreated` echoes. */
    private fun selectObject(id: String, bounds: Bounds) {
        armLassoForLanding()
        paper.setSelection(emptySet(), setOf(id), bounds)
        val sel = Selection(emptySet(), setOf(id), bounds)
        selectionActive = true
        currentSelection = sel
        showObjectBar(sel)
    }

    /** Delete on the bar: ink and objects together, one undo step. */
    private fun deleteSelected(sel: Selection) {
        if (!opened || closing) return
        val doc = document ?: return
        val strokeIds = sel.strokeIds.toList()
        val ink = doc.erase(strokeIds)
        if (sel.contentIds.isEmpty()) {
            ink?.let { record(it); scheduleSave() }
            paper.removeStrokes(strokeIds)
            return
        }
        runPageOp {
            val gone = doc.deleteObjects(sel.contentIds)
            undo.record(NotebookAction.Deleted(doc.pageId, ink, gone))
            doc.flushUntilClean()
            // Both in one Main block: one frame.
            if (strokeIds.isNotEmpty()) paper.removeStrokes(strokeIds) else paper.clearSelection()
            paper.notifyContentChanged()
            refreshContents()
        }
    }

    /** An erase gesture that took [contentIds], and maybe ink with them. */
    private fun erased(strokeIds: List<String>, contentIds: List<String>) {
        if (!opened || closing) return
        val doc = document ?: return
        val ink = doc.erase(strokeIds)
        if (contentIds.isEmpty()) {
            ink?.let { record(it); scheduleSave() }
            return
        }
        runPageOp {
            val gone = doc.deleteObjects(contentIds)
            undo.record(NotebookAction.Deleted(doc.pageId, ink, gone))
            doc.flushUntilClean()
            paper.notifyContentChanged()
            refreshContents()
        }
    }

    // ── Insert ──────

    private fun showInsertBar(anchor: View? = null) {
        if (!opened || closing) return
        hideFloatingBars()
        val shown = if (anchor == null) insertBar.show() else insertBar.show(anchor)
        if (shown) pushExclusions()
    }

    private fun hideInsertBar() {
        if (!::insertBar.isInitialized || !insertBar.isShowing) return
        insertBar.hide()
        pushExclusions()
    }

    private fun insert(kind: InsertBar.Kind) {
        if (!opened || closing) return
        when (kind) {
            InsertBar.Kind.HEADING -> ObjectDialogs.heading(this, "", onSave = { words -> if (words.isNotEmpty()) insertHeading(words) })
            InsertBar.Kind.TEXT -> ObjectDialogs.text(this, "", onSave = { source -> if (source.isNotEmpty()) insertText(source) })
            InsertBar.Kind.STICKY -> insertSticky()
            InsertBar.Kind.BIBLE -> bibleRefs.insertAtCentre()
        }
    }

    private fun insertHeading(words: String) {
        val doc = document ?: return
        val pageId = doc.pageId
        val text = HeadingPrefix.applyLevel(words, DEFAULT_HEADING_LEVEL)
        val (w, h) = HeadingRenderer.measure(text, density, scaledDensity)
        val (x, y) = FreePlacement.nearCentre(doc.pageWidth, doc.pageHeight, w, h, occupied(), density)
        runPageOp {
            if (doc.pageId != pageId) return@runPageOp
            val heading = doc.createHeading(Heading(UUID.randomUUID().toString(), text, DEFAULT_HEADING_LEVEL, x, y, w, h, 0))
            undo.record(NotebookAction.HeadingCreated(pageId, heading))
            selectObject(heading.id, heading.bounds)
            paper.notifyContentChanged()
            refreshContents()
        }
    }

    private fun insertText(source: String) {
        val doc = document ?: return
        val pageId = doc.pageId
        val (w0, h0) = TextRenderer.measure(source, doc.pageWidth.toInt(), density, scaledDensity)
        val (x, y) = FreePlacement.nearCentre(doc.pageWidth, doc.pageHeight, w0, h0, occupied(), density)
        val (w, h) = if (doc.pageWidth - x < w0) TextRenderer.measure(source, (doc.pageWidth - x).toInt(), density, scaledDensity) else w0 to h0
        runPageOp {
            if (doc.pageId != pageId) return@runPageOp
            val text = doc.createText(PageText(UUID.randomUUID().toString(), source, x, y, w, h, 0))
            undo.record(NotebookAction.TextCreated(pageId, text))
            selectObject(text.id, text.bounds)
            paper.notifyContentChanged()
        }
    }

    private fun insertSticky() {
        val doc = document ?: return
        if (stickyInFlight != null) return
        val pageId = doc.pageId
        val (cw, ch) = StickyDefaults.contentSize(binding.root.width, binding.root.height)
        val built = StickyDefaults.at(UUID.randomUUID().toString(), doc.pageWidth, doc.pageHeight, density, cw, ch)
        val (x, y) = FreePlacement.nearCentre(doc.pageWidth, doc.pageHeight, built.width, built.height, occupied(), density)
        runPageOp {
            if (doc.pageId != pageId) return@runPageOp
            val sticky = doc.createSticky(built.copy(x = x, y = y))
            undo.record(NotebookAction.StickyInserted(pageId, sticky))
            paper.notifyContentChanged()
            openSticky(sticky.id, initialCreate = true)
        }
    }

    // ── Headings and texts ──────

    private fun editHeading(heading: Heading) {
        val pageId = document?.pageId ?: return
        ObjectDialogs.heading(this, HeadingPrefix.stripHeadingPrefix(heading.text), onSave = { words ->
            val doc = document ?: return@heading
            if (!opened || closing || doc.pageId != pageId) return@heading
            val before = doc.headings[heading.id] ?: return@heading
            if (words.isEmpty()) {
                runPageOp {
                    val gone = doc.deleteObjects(listOf(before.id))
                    undo.record(NotebookAction.Deleted(pageId, null, gone))
                    paper.clearSelection()
                    paper.notifyContentChanged()
                    refreshContents()
                }
                return@heading
            }
            val text = HeadingPrefix.applyLevel(words, before.level)
            if (text == before.text) return@heading
            val (w, h) = HeadingRenderer.measure(text, density, scaledDensity)
            val after = before.copy(text = text, width = w, height = h)
            runPageOp {
                doc.updateHeading(after)
                undo.record(NotebookAction.HeadingEdited(pageId, before, after))
                selectObject(after.id, after.bounds)
                paper.notifyContentChanged()
                refreshContents()
            }
        })
    }

    /** An H1–H6 tap: a lone heading changes level; ink alone is recognised into a heading at that level. */
    private fun setHeadingLevel(level: Int) {
        val doc = document ?: return
        val sel = currentSelection ?: return
        if (sel.contentIds.isEmpty() && sel.strokeIds.isNotEmpty()) { convertToHeading(sel, level); return }
        val id = sel.contentIds.singleOrNull() ?: return
        val before = doc.headings[id] ?: return
        if (before.level == level) return
        val text = HeadingPrefix.applyLevel(HeadingPrefix.stripHeadingPrefix(before.text), level)
        val (w, h) = HeadingRenderer.measure(text, density, scaledDensity)
        val after = before.copy(text = text, level = level, width = w, height = h)
        runPageOp {
            doc.updateHeading(after)
            undo.record(NotebookAction.HeadingEdited(doc.pageId, before, after))
            selectObject(after.id, after.bounds)
            paper.notifyContentChanged()
            refreshContents()
        }
    }

    private fun editText(text: PageText) {
        val pageId = document?.pageId ?: return
        ObjectDialogs.text(this, text.text, onSave = { source ->
            val doc = document ?: return@text
            if (!opened || closing || doc.pageId != pageId) return@text
            val before = doc.texts[text.id] ?: return@text
            if (source.isEmpty()) {
                runPageOp {
                    val gone = doc.deleteObjects(listOf(before.id))
                    undo.record(NotebookAction.Deleted(pageId, null, gone))
                    paper.clearSelection()
                    paper.notifyContentChanged()
                }
                return@text
            }
            if (source == before.text) return@text
            val (w, h) = TextRenderer.measure(source, (doc.pageWidth - before.x).toInt(), density, scaledDensity)
            val after = before.copy(text = source, width = w, height = h)
            runPageOp {
                doc.updateText(after)
                undo.record(NotebookAction.TextEdited(pageId, before, after))
                selectObject(after.id, after.bounds)
                paper.notifyContentChanged()
            }
        })
    }

    // ── Sticky notes ──────

    /** A finger tap on a sticky icon opens the note; the topmost one under the finger. */
    private fun openStickyAt(x: Float, y: Float): Boolean {
        if (!opened || closing || stickyInFlight != null) return false
        val hit = document?.stickyAt(x, y) ?: return false
        openSticky(hit.id, initialCreate = false)
        return true
    }

    /** Read the note's content, stage the showing, hand the pipeline over, launch. */
    private fun openSticky(stickyId: String, initialCreate: Boolean) {
        val doc = document ?: return
        val pageId = doc.pageId
        stickyInFlight = stickyId to initialCreate
        runPageOp {
            try {
                val sticky = doc.stickyById(stickyId)
                if (sticky == null || !opened || closing || doc.pageId != pageId) { stickyInFlight = null; return@runPageOp }
                doc.flushUntilClean()
                val initial = withContext(Dispatchers.IO) { (storeOf(doc) ?: return@withContext emptyList()).readStrokesOf(stickyId) }
                StickyEditorTransfer.stage(StickyEditorTransfer.Showing(store = requireNotNull(storeOf(doc)), pageId = pageId, stickyId = stickyId, contentW = sticky.contentW, contentH = sticky.contentH, initial = initial))
                hideFloatingBars()
                dismissCollapsed()
                inAppHandoff = true
                paper.releaseForHandoff()
                editorLauncher.launch(StickyEditorActivity.intent(this@NotebookActivity))
            } catch (e: Exception) {
                Log.w(TAG, "the note could not be opened: ${e.javaClass.simpleName}")
                StickyEditorTransfer.clear()
                stickyInFlight = null
                inAppHandoff = false
                paper.resumeDrawing()
            }
        }
    }

    /** The result callback runs before `onResume`: the pipeline is reclaimed first of all. */
    private fun onEditorClosed() {
        inAppHandoff = false
        if (opened) paper.resumeDrawing()
        val flight = stickyInFlight
        stickyInFlight = null
        val showing = StickyEditorTransfer.current
        StickyEditorTransfer.clear()
        val doc = document
        if (flight == null || showing == null || doc == null || showing.stickyId != flight.first) return
        runPageOp {
            val after = doc.stickyContent(showing.stickyId)
            val before = showing.initial.map { it.second }
            if (after != before) undo.record(NotebookAction.StickyContentEdited(showing.pageId, showing.stickyId, before, after))
            if (!flight.second || doc.pageId != showing.pageId) return@runPageOp
            val sticky = doc.stickies[showing.stickyId] ?: return@runPageOp
            // The icon lands selected so the next drag places it.
            selectObject(sticky.id, sticky.bounds)
        }
    }

    /** The store behind [document], for the sticky editor, which writes through it. */
    private var storeRef: NotebookStore? = null
    private fun storeOf(@Suppress("UNUSED_PARAMETER") doc: NotebookDocument): NotebookStore? = storeRef

    // ── Links ──────

    private fun loneLink(): PageLink? {
        val sel = currentSelection ?: return null
        if (sel.strokeIds.isNotEmpty()) return null
        return sel.contentIds.singleOrNull()?.let { document?.links?.get(it) }
    }

    /** Link on the bar: the picker, in create shape, for the selection as it stands. */
    private fun beginWrap(sel: Selection) {
        if (sel.contentIds.any { it in document?.links.orEmpty() }) return
        launchPicker(wrap = sel, edit = null)
    }

    /** Edit link on the bar, or the dead-target dialog's Edit: the picker prefilled. */
    /** Edit link: a Bible link's reference dialog, any other's the page picker. */
    private fun beginEdit(link: PageLink) {
        when {
            LinkPayload.isBibleText(link.payload) -> bibleRefs.editVerses(link)
            LinkPayload.referenceOf(link.payload) != null -> bibleRefs.edit(link)
            else -> launchPicker(wrap = null, edit = link)
        }
    }

    /**
     * A Bible reference landed as one step: the ink it replaced erased, the text written, the
     * link wrapped around it, the page read again and the link selected; the wrapped ink comes
     * off the paper in the same frame.
     */
    private fun landReference(pageId: String, strokeIds: List<String>, text: PageText, payload: String, label: String) {
        val doc = document ?: return
        runPageOp {
            if (doc.pageId != pageId) return@runPageOp
            val ink = if (strokeIds.isNotEmpty()) doc.erase(strokeIds) else null
            val placed = doc.createText(text)
            val link = doc.wrap(emptySet(), setOf(placed.id), payload)
            if (link == null) {
                // Nothing to wrap: the text stays as a conversion would have left it.
                undo.record(NotebookAction.Converted(pageId, ink, null, placed))
            } else {
                undo.record(NotebookAction.BibleRefCreated(pageId, ink, placed, link))
            }
            doc.flushUntilClean()
            val prebuilt = linkRenderer.prebuild(doc.links.values.toList())
            syncRenderers(prebuilt)
            val landed = link?.let { doc.links[it.id] }
            pendingSelection = landed?.let { l -> { selectObject(l.id, l.bounds) } } ?: { selectObject(placed.id, placed.bounds) }
            if (strokeIds.isNotEmpty()) paper.removeStrokes(strokeIds) else paper.clearSelection()
            pendingSelection?.let { pendingSelection = null; it() }
            paper.notifyContentChanged()
            refreshContents()
            if (link != null) bibleRefs.toastLinked(label)
        }
    }

    /** A Bible reference edited: its words and its payload rewritten together, one step, the
     *  page read again so the link's box follows the words. */
    private fun relandReference(pageId: String, link: PageLink, before: PageText, after: PageText, payload: String, label: String) {
        val doc = document ?: return
        runPageOp {
            if (doc.pageId != pageId || link.id !in doc.links) return@runPageOp
            doc.updateText(after)
            doc.setLinkPayload(link, payload)
            undo.record(NotebookAction.BibleRefEdited(pageId, link.id, before, after, link.payload, payload))
            doc.reloadCurrent()
            syncRenderers(linkRenderer.prebuild(doc.links.values.toList()))
            doc.links[link.id]?.let { selectObject(it.id, it.bounds) }
            paper.notifyContentChanged()
            bibleRefs.toastLinked(label)
        }
    }

    /** A link into the Bible: Soil opens the reader on the passage, over this notebook. */
    private fun openBible(wire: String) {
        if (!opened || closing) return
        hideFloatingBars()
        dismissCollapsed()
        val started = runCatching {
            startActivity(
                android.content.Intent(Seam.ACTION_FOLLOW).setPackage(com.symmetricalpalmtree.soil.notesprout.BuildConfig.SOIL_PACKAGE)
                    .putExtra(Seam.EXTRA_BIBLE_WIRE, wire),
            )
        }.isSuccess
        if (!started) Dialogs.problem(this, R.string.link_target_gone_title, R.string.link_follow_failed_body)
    }

    /** A link to a day: Soil opens the calendar on it, over this notebook. */
    private fun openCalendar(date: String) {
        if (!opened || closing) return
        hideFloatingBars()
        dismissCollapsed()
        val started = runCatching {
            startActivity(
                android.content.Intent(Seam.ACTION_FOLLOW).setPackage(com.symmetricalpalmtree.soil.notesprout.BuildConfig.SOIL_PACKAGE)
                    .putExtra(Seam.EXTRA_CAL_DATE, date),
            )
        }.isSuccess
        if (!started) Dialogs.problem(this, R.string.link_target_gone_title, R.string.link_follow_failed_body)
    }

    /**
     * The relay carries this notebook's pages to the picker through the live store, never a
     * second session on the file; the Intent carries only the prefill. What the answer applies
     * to is captured here. The ink is flushed first, so the previews show what was just written.
     */
    private fun launchPicker(wrap: Selection?, edit: PageLink?) {
        if (!opened || closing || pickerShowing) return
        val doc = document ?: return
        val store = storeRef ?: return
        pickerShowing = true
        runPageOp {
            try {
                doc.flushUntilClean()
                pendingWrap = wrap
                pendingEdit = edit
                pagesChangedUnderPicker = false
                LinkPickerRelay.showing = LinkPickerRelay.Showing(
                    notebookId = requireNotNull(itemId), currentPageId = doc.pageId,
                    pageWidth = doc.pageWidth, pageHeight = doc.pageHeight,
                    source = object : PickerSource {
                        override suspend fun pages(): List<PageRef> = doc.pages
                        override suspend fun content(page: PageRef): PageContent? =
                            withContext(Dispatchers.IO) { runCatching { store.readPage(page) }.getOrNull() }
                        override suspend fun createPage(anchorId: String?, before: Boolean): PageRef? =
                            createPageForPicker(anchorId, before)
                    },
                )
                hideFloatingBars()
                dismissCollapsed()
                inAppHandoff = true
                paper.releaseForHandoff()
                pickerLauncher.launch(LinkPickerActivity.intent(this@NotebookActivity, edit?.payload))
            } catch (e: Exception) {
                Log.w(TAG, "the picker could not be opened: ${e.javaClass.simpleName}")
                LinkPickerRelay.showing = null
                pendingWrap = null
                pendingEdit = null
                pickerShowing = false
                inAppHandoff = false
                paper.resumeDrawing()
            }
        }
    }

    /** The picker's New page in this notebook: under the page-op lock, the showing page kept. */
    private suspend fun createPageForPicker(anchorId: String?, before: Boolean): PageRef? {
        val doc = document ?: return null
        if (!opened || closing) return null
        return try {
            pageOps.withLock {
                val page = doc.insertPageQuietly(anchorId, before)
                pagesChangedUnderPicker = true
                page
            }
        } catch (e: Exception) {
            Log.w(TAG, "the picker's page could not be made: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The result callback runs before `onResume`: the pipeline is reclaimed first of all. */
    private fun onPickerClosed(resultCode: Int, payload: String?) {
        pickerShowing = false
        inAppHandoff = false
        LinkPickerRelay.showing = null
        if (opened) paper.resumeDrawing()
        val wrap = pendingWrap.also { pendingWrap = null }
        val edit = pendingEdit.also { pendingEdit = null }
        val doc = document ?: return
        if (pagesChangedUnderPicker) {
            pagesChangedUnderPicker = false
            undo.clear()
            toolbar.setPage(doc.pageNumber, doc.pageCount)
        }
        if (resultCode != android.app.Activity.RESULT_OK || payload.isNullOrEmpty()) return
        when {
            wrap != null -> applyWrap(wrap, payload)
            edit != null -> if (payload != edit.payload) applyEdit(edit, payload)
            else -> Dialogs.problem(this, R.string.link_result_lost_title, R.string.link_result_lost_body)
        }
    }

    /**
     * The wrap: rows re-parented and the page read again, then the wrapped ink taken off the
     * paper and the link landed as the **successor** of the selection that removal dismisses, so
     * the lasso session stays one session and the pen comes back at the link's own dismissal,
     * never in the middle of the wrap. One Main block: one frame.
     */
    private fun applyWrap(sel: Selection, payload: String) {
        val doc = document ?: return
        val pageId = doc.pageId
        runPageOp {
            if (doc.pageId != pageId) return@runPageOp
            val link = doc.wrap(sel.strokeIds, sel.contentIds, payload) ?: return@runPageOp
            undo.record(NotebookAction.LinkCreated(pageId, link))
            val prebuilt = linkRenderer.prebuild(doc.links.values.toList())
            syncRenderers(prebuilt)
            val landed = doc.links[link.id]
            pendingSelection = landed?.let { l -> { selectObject(l.id, l.bounds) } }
            if (sel.strokeIds.isNotEmpty()) paper.removeStrokes(sel.strokeIds.toList()) else paper.clearSelection()
            // No dismissal fired: land it directly.
            pendingSelection?.let { pendingSelection = null; it() }
            paper.notifyContentChanged()
            refreshContents()
        }
    }

    private fun applyEdit(link: PageLink, payload: String) {
        val doc = document ?: return
        val pageId = doc.pageId
        runPageOp {
            if (doc.pageId != pageId || link.id !in doc.links) return@runPageOp
            doc.setLinkPayload(link, payload)
            undo.record(NotebookAction.LinkEdited(pageId, link.id, link.payload, payload))
            paper.notifyContentChanged()
            currentSelection?.let { showObjectBar(it) }
        }
    }

    /** Unlink on the bar: the children are the page's again. */
    private fun unlink(link: PageLink) {
        val doc = document ?: return
        val pageId = doc.pageId
        runPageOp {
            if (doc.pageId != pageId || link.id !in doc.links) return@runPageOp
            doc.unlink(link)
            undo.record(NotebookAction.LinkUnlinked(pageId, link))
            showPage()
            refreshContents()
        }
    }

    // ── Contents ──────

    /** The button shows and the swipe acts only while the notebook holds a heading. */
    private fun refreshContents() {
        val doc = document ?: return
        lifecycleScope.launch {
            val any = runCatching { doc.allHeadings().isNotEmpty() }.getOrDefault(contentsAvailable)
            if (!opened || closing) return@launch
            contentsAvailable = any
            whenPenIdle {
                val vis = if (contentsAvailable) View.VISIBLE else View.GONE
                if (binding.btnContents.visibility != vis) { binding.btnContents.visibility = vis; pushExclusions() }
            }
        }
    }

    private fun showContents() {
        if (!opened || closing || contentsShowing || !contentsAvailable) return
        val doc = document ?: return
        contentsShowing = true
        hideFloatingBars()
        dismissCollapsed()
        if (!paper.isPenActive) paper.releaseRender()
        lifecycleScope.launch {
            val outline = runCatching {
                val pageIndexById = doc.pages.withIndex().associate { (i, p) -> p.id to i }
                OutlineTree.items(doc.allHeadings(), pageIndexById)
            }.getOrNull()
            if (outline == null || isFinishing || isDestroyed || closing) { contentsShowing = false; return@launch }
            val (items, truncated) = outline
            if (items.isEmpty()) { contentsShowing = false; refreshContents(); return@launch }
            pushExclusions()
            contents.show(OutlineTree.build(items), doc.pageIndex, truncated) { contentsShowing = false; pushExclusions() }
        }
    }

    // ── The pen's shade ──────

    /** Arm the pen with the device's shade: at open, at every resume, and after a pick. The
     *  toolbar wears it and the collapsed chrome repaints from the toolbar's funnel. */
    private fun applyPenShade() {
        toolbar.applyShade(InkTones.tone(prefs.penLevel))
        syncCollapsed()
    }

    /** Under the pen button, or under [anchor], the mini toolbar's own pen while the chrome is
     *  collapsed. Not pen-idle gated: one chrome frame at a deliberate tap. */
    private fun showPaletteBar(anchor: View? = null) {
        if (!opened || closing) return
        hideEraserBar()
        val shown = if (anchor == null) paletteBar.show() else paletteBar.show(anchor)
        if (shown) pushExclusions()
    }

    private fun hidePaletteBar() {
        if (!::paletteBar.isInitialized || !paletteBar.isShowing) return
        paletteBar.hide()
        pushExclusions()
    }

    override fun hideFloatingBars() {
        super.hideFloatingBars()
        hidePaletteBar()
        hideInsertBar()
        hideLassoPopup()
        hideTagsPopup()
    }

    override fun onCollapsedClosing() {
        if (::paletteBar.isInitialized) paletteBar.hide()
    }


    /** The shade panel's outside-tap dismissal: the pen button excluded (its re-tap toggles the
     *  bar), and the collapsed rows it may hang under kept. */
    override fun dismissFloatingOnContact(ev: android.view.MotionEvent, index: Int) {
        val x = ev.getX(index).toInt()
        val y = ev.getY(index).toInt()
        if (::paletteBar.isInitialized && paletteBar.isShowing) {
            if (PaperToolbar.rectOf(binding.btnPen)?.contains(x, y) != true && !paletteBar.contains(x, y) && !collapsedContains(x, y)) hidePaletteBar()
        }
        if (::insertBar.isInitialized && insertBar.isShowing) {
            if (PaperToolbar.rectOf(binding.btnInsert)?.contains(x, y) != true && !insertBar.contains(x, y) && !collapsedContains(x, y)) hideInsertBar()
        }
        if (::tagsPopup.isInitialized && tagsPopup.isShowing) {
            if (PaperToolbar.rectOf(binding.btnTag)?.contains(x, y) != true && !tagsPopup.contains(x, y) && !collapsedContains(x, y)) hideTagsPopup()
        }
        // The lasso popup's latch is rewritten at every pointer down, so it can never go stale: a
        // contact that took the popup down is not also a paste. The lasso button is excluded, or
        // its re-tap would close the popup here and reopen it in the toolbar.
        val dismissedLasso = ::lassoPopup.isInitialized && lassoPopup.isShowing &&
            PaperToolbar.rectOf(binding.btnLasso)?.contains(x, y) != true && !lassoPopup.contains(x, y) && !collapsedContains(x, y)
        tapDismissedPopup = dismissedLasso
        if (dismissedLasso) hideLassoPopup()
    }

    override fun keepCollapsedUnder(x: Int, y: Int): Boolean =
        (::paletteBar.isInitialized && paletteBar.isShowing && paletteBar.contains(x, y)) ||
            (::insertBar.isInitialized && insertBar.isShowing && insertBar.contains(x, y)) ||
            (::tagsPopup.isInitialized && tagsPopup.isShowing && tagsPopup.contains(x, y)) ||
            (::lassoPopup.isInitialized && lassoPopup.isShowing && lassoPopup.contains(x, y))

    override fun extraFloatingRects(): List<android.graphics.Rect> =
        super.extraFloatingRects() +
            (if (::paletteBar.isInitialized) paletteBar.rects() else emptyList()) +
            (if (::insertBar.isInitialized) insertBar.rects() else emptyList()) +
            (if (::objectBar.isInitialized) objectBar.rects() else emptyList()) +
            (if (::lassoPopup.isInitialized) lassoPopup.rects() else emptyList()) +
            (if (::tagsPopup.isInitialized) tagsPopup.rects() else emptyList())

    override fun extraFloatingContains(x: Int, y: Int): Boolean =
        super.extraFloatingContains(x, y) ||
            (::paletteBar.isInitialized && paletteBar.contains(x, y)) ||
            (::insertBar.isInitialized && insertBar.contains(x, y)) ||
            (::objectBar.isInitialized && objectBar.contains(x, y)) ||
            (::lassoPopup.isInitialized && lassoPopup.contains(x, y)) ||
            (::tagsPopup.isInitialized && tagsPopup.contains(x, y))

    /** The corner button's overflow row: Back, and Insert, whose bar hangs under the row's own button. */
    override fun collapsedOverflow(): List<CollapsedChrome.Entry> = listOfNotNull(
        backEntry(),
        CollapsedChrome.Entry(com.symmetricalpalmtree.soil.paper.R.drawable.ic_plus, getString(R.string.cd_insert)) { anchor -> if (insertBar.isShowing) hideInsertBar() else showInsertBar(anchor) },
        CollapsedChrome.Entry(com.symmetricalpalmtree.soil.paper.R.drawable.ic_tag, getString(R.string.cd_tags)) { anchor -> if (tagsPopup.isShowing) hideTagsPopup() else showTagsPopup(anchor) },
    )

    /** The mini toolbar's pen wears the shade too, and its re-tap hangs the panel under it. */
    override fun collapsedPenIcon(): (() -> CollapsedChrome.PenIcon) = {
        val ink = toolbar.penInk
        CollapsedChrome.PenIcon(ink) { ShadeIcon.pen(this, ink) }
    }

    override fun collapsedPenReTap(): ((anchor: View) -> Unit) =
        { anchor -> if (paletteBar.isShowing) hidePaletteBar() else showPaletteBar(anchor) }

    // ── Recents ──────

    /** The notebooks opened recently, from Soil, this one left out; the panel blocks ink. */
    private fun showRecents() {
        if (!opened || closing || recentsShowing) return
        recentsShowing = true
        hideFloatingBars()
        dismissCollapsed()
        paper.releaseRender()
        val me = itemId
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { (application as NotesproutApp).soil.seam().recentItems(NotebookSchema.KIND, RECENTS_LIMIT) }
                    .getOrDefault(emptyList())
                    .filter { it.id != me }
            }
            if (isFinishing || isDestroyed || closing) { recentsShowing = false; return@launch }
            pushExclusions()
            recents.show(items) { recentsShowing = false; pushExclusions() }
        }
    }

    /** The panel is a snapshot: the notebook is checked against Soil first. Then the box goes
     *  up, and the switch is a new ask of this screen ([onNewIntent]). */
    private fun switchTo(item: SeamItem) {
        if (!opened || closing) return
        lifecycleScope.launch {
            val alive = withContext(Dispatchers.IO) {
                runCatching { (application as NotesproutApp).soil.seam().item(item.id) != null }.getOrDefault(false)
            }
            if (!alive) {
                Dialogs.problem(this@NotebookActivity, R.string.recents_gone_title, R.string.recents_gone_body)
                return@launch
            }
            binding.openingOverlay.visibility = View.VISIBLE
            startActivity(
                android.content.Intent(this@NotebookActivity, NotebookActivity::class.java)
                    .setAction(Seam.ACTION_OPEN_ITEM)
                    .putExtra(Seam.EXTRA_ITEM_ID, item.id),
            )
        }
    }

    // ── The page sheet ──────

    /** A long press asks; it never acts. What can be done with this page, and, when anything in
     *  the library links to it, what links here. */
    private fun showPageSheet() {
        if (!opened || closing) return
        val doc = document ?: return
        val pageId = doc.pageId
        paper.releaseRender()
        lifecycleScope.launch {
            val into = backlinksTo(pageId)
            if (!opened || closing || doc.pageId != pageId) return@launch
            val sheet = ActionSheetDialog(this@NotebookActivity)
                .title(getString(R.string.page_sheet_title))
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_copy, getString(R.string.copy_page_action)) { runPageOp { doCopyPage(cut = false) } }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_cut, getString(R.string.cut_page_action)) { runPageOp { doCopyPage(cut = true) } }
            // Absent, never disabled, while the clipboard holds no page: a sheet whose row count is
            // its content is simply one row shorter.
            if (SoilClipboard.hasPage) {
                sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_clipboard, getString(R.string.paste_page_action)) { showPasteSheet() }
            }
            sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_template, getString(R.string.page_template_action)) { openTemplatePicker() }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_erase_page, getString(R.string.page_sheet_erase)) { confirmErasePage() }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_trash, getString(R.string.page_sheet_delete)) { confirmDeletePage() }
            if (into.isNotEmpty()) {
                sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_link, resources.getQuantityString(R.plurals.page_sheet_backlinks, into.size, into.size)) { showBacklinks(into) }
            }
            if (doc.pageWidth > 0f && doc.pageHeight > 0f) {
                sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_photo_plus, getString(R.string.save_as_template_action)) { saveAsTemplate() }
            }
            sheet.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_file_export, getString(R.string.export_page_action)) { exportVia(pageId) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_file_export, getString(R.string.export_notebook_action)) { exportVia(null) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_file_text, getString(R.string.convert_page_action)) { convertToDocument(wholeNotebook = false) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_file_text, getString(R.string.convert_notebook_action)) { convertToDocument(wholeNotebook = true) }
            sheet.show()
        }
    }

    /**
     * Soil's export screen for this notebook, or one page of it. The notebook closes first, so
     * the file is free for a copy, and Soil opens it again on the way back.
     */
    /**
     * Convert: this page, or every page, read into a new document beside this notebook. The page
     * is flushed first, so what is read is what is on it; the notebook itself is not changed.
     */
    private fun convertToDocument(wholeNotebook: Boolean) {
        if (!opened || closing) return
        val doc = document ?: return
        val store = storeRef ?: return
        val id = itemId ?: return
        runPageOp {
            doc.flushUntilClean()
            val numbered = doc.pages.mapIndexed { i, page -> page to i + 1 }
            val pages = if (wholeNotebook) numbered else numbered.filter { it.first.id == doc.pageId }
            val notebookName = withContext(Dispatchers.IO) { runCatching { (application as NotesproutApp).soil.seam().item(id)?.name }.getOrNull() }.orEmpty().ifBlank { getString(R.string.convert_default_name) }
            val name = if (wholeNotebook) notebookName else getString(R.string.convert_page_name, notebookName, pages.firstOrNull()?.second ?: 1)
            hideFloatingBars()
            dismissCollapsed()
            paper.releaseRender()
            pushExclusions()
            ConvertFlow.run(this@NotebookActivity, recognizerPort, store, id, pages, name, onOpen = ::openElsewhere)
        }
    }

    private fun exportVia(pageId: String?) {
        if (!opened || closing) return
        val id = itemId ?: return
        afterExit = {
            startActivity(
                android.content.Intent(Seam.ACTION_EXPORT).setPackage(com.symmetricalpalmtree.soil.notesprout.BuildConfig.SOIL_PACKAGE)
                    .putExtra(Seam.EXTRA_ITEM_ID, id)
                    .putExtra(Seam.EXTRA_PAGE_ID, pageId)
                    .putExtra(Seam.EXTRA_RETURN_TO_APP, true),
            )
        }
        exit()
    }

    // ── The clipboard ──────

    private suspend fun seam() = withContext(Dispatchers.IO) { (application as NotesproutApp).soil.seam() }

    /** The clipboard's header read again from Soil, and the lasso's mark set by it. */
    private fun refreshClipboardMark() {
        if (!opened || closing) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { SoilClipboard.refresh((application as NotesproutApp).soil.seam()) }
                runCatching { BibleClipboard.refresh((application as NotesproutApp).soil.seam()) }
            }
            if (opened && !closing) markClipboard(SoilClipboard.hasObjects || BibleClipboard.has)
        }
    }

    /** The lasso's clipboard mark, on the bar and on the collapsed chrome alike. */
    private fun markClipboard(loaded: Boolean) {
        if (::toolbar.isInitialized) toolbar.showClipboardLoaded(loaded)
        collapsedClipboardLoaded(loaded)
    }

    /** Where the pasted page goes. From a row of the page sheet, so the pen is idle. */
    private fun showPasteSheet() {
        if (!opened || closing) return
        ActionSheetDialog(this)
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_prev, getString(R.string.paste_before_action)) { runPageOp { doPastePage(before = true) } }
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page_next, getString(R.string.paste_after_action)) { runPageOp { doPastePage(before = false) } }
            .show()
    }

    /**
     * Copy the page, or cut it: a copy followed by the ordinary delete, so undo puts the page and
     * its ink back exactly as Delete page would. Anything that did not work is a dialog: a copy
     * that failed silently would leave a stale clipboard standing ready to paste the wrong page.
     */
    private suspend fun doCopyPage(cut: Boolean) {
        val doc = document ?: return
        val env = runCatching { doc.copyPage() }.onFailure { Log.w(TAG, "page capture failed: ${it.javaClass.simpleName}") }.getOrNull()
        if (env == null) {
            Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_capture_failed)
            return
        }
        val write = runCatching { withContext(Dispatchers.IO) { SoilClipboard.write((application as NotesproutApp).soil.seam(), env) } }
            .onFailure { Log.w(TAG, "clipboard write failed: ${it.javaClass.simpleName}") }
        if (write.getOrNull() == null) {
            Dialogs.problem(this, R.string.clip_failed_title, if (write.isSuccess) R.string.clip_too_large else R.string.clip_write_failed)
            return
        }
        // One slot, kind wins: the lasso's mark stops promising a paste it no longer holds.
        markClipboard(false)
        hideLassoPopup()
        if (cut) doDelete()
        toast(getString(if (cut) R.string.page_cut_toast else R.string.page_copied_toast))
    }

    /** Paste the clipboard's page beside this one and land on it. */
    private suspend fun doPastePage(before: Boolean) {
        val doc = document ?: return
        val env = runCatching { SoilClipboard.read(seam()) }.getOrNull()
        if (env == null || env.kind != ClipEnvelope.KIND_PAGE || env.rows.none { it.type == NotebookSchema.TYPE_PAGE }) {
            // Gone, foreign, or claiming a page it does not carry: stop advertising a Paste that
            // cannot work, in memory and in Soil.
            retireClipboard()
            Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_paste_failed)
            return
        }
        // The anchor's number as it reads once the paste has landed (every pasted page ahead of
        // it, for a paste before): what the indicator shows.
        val anchor = PageMath.anchorNumberAfterPaste(doc.pageIndex, before, env.rows.count { it.type == NotebookSchema.TYPE_PAGE })
        undo.record(doc.pastePage(env, before))
        preparePaper()
        showPage(firstLoad = false, prebuilt = linkRenderer.prebuild(doc.links.values.toList()))
        refreshContents()
        toast(getString(if (before) R.string.pasted_before_toast else R.string.pasted_after_toast, anchor))
    }

    /**
     * Copy or cut what is caught. Three orderings, each a bug designed out: the ink is flushed
     * before the rows are read; the clipboard is written before anything is deleted; and the lasso
     * is armed again afterwards, since the dismissal of a selection can hand the pen back and the
     * placement tap that follows would ink the page.
     */
    private fun doObjectCopy(sel: Selection, cut: Boolean) {
        if (!opened || closing) return
        val doc = document ?: return
        val pageId = doc.pageId
        runPageOp {
            val env = runCatching { doc.copyObjects(sel.strokeIds, sel.contentIds) }.onFailure { Log.w(TAG, "selection capture failed: ${it.javaClass.simpleName}") }.getOrNull()
            if (env == null) {
                Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_objects_capture_failed)
                return@runPageOp
            }
            val write = runCatching { withContext(Dispatchers.IO) { SoilClipboard.write((application as NotesproutApp).soil.seam(), env) } }
                .onFailure { Log.w(TAG, "clipboard write failed: ${it.javaClass.simpleName}") }
            if (write.getOrNull() == null) {
                Dialogs.problem(this, R.string.clip_failed_title, if (write.isSuccess) R.string.clip_objects_too_large else R.string.clip_objects_write_failed)
                return@runPageOp
            }
            markClipboard(true)
            if (cut) {
                if (doc.pageId != pageId) {
                    Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_objects_cut_moved)
                    return@runPageOp
                }
                deleteSelected(sel)
            } else {
                paper.clearSelection()
            }
            armLassoAfterCopy()
            toast(getString(if (cut) R.string.objects_cut_toast else R.string.objects_copied_toast))
        }
    }

    /** The lasso stays armed after a copy, whatever the dismissal handed back, once the pen is idle. */
    private fun armLassoAfterCopy() {
        toolBeforeLanding = null
        whenPenIdle {
            if (isFinishing || isDestroyed || closing || paper.tool == Tool.LASSO) return@whenPenIdle
            toolbar.arm(Tool.LASSO)
        }
    }

    /**
     * Paste the clipboard's objects onto the showing page: centred on the pen tap, or at the
     * source coordinates for the popup's Paste; both clamp onto the page. The pasted set lands
     * selected, bar up, so the pen can drag it into place.
     */
    private fun doObjectPaste(tapX: Float?, tapY: Float?) {
        if (!opened || closing) return
        val doc = document ?: return
        val pageId = doc.pageId
        // A passage from the Bible, copied after whatever the notebook kind's slot holds: the
        // paste asks which of it goes in, where the pen tapped.
        if (BibleClipboard.newerThan(SoilClipboard.header)) {
            lifecycleScope.launch {
                val clip = withContext(Dispatchers.IO) { runCatching { BibleClipboard.read(seam()) }.getOrNull() }
                if (!opened || closing || document?.pageId != pageId) return@launch
                if (clip == null) {
                    withContext(Dispatchers.IO) { runCatching { BibleClipboard.clear(seam()) } }
                    markClipboard(SoilClipboard.hasObjects)
                    Dialogs.problem(this@NotebookActivity, R.string.clip_failed_title, R.string.clip_objects_paste_failed)
                    return@launch
                }
                bibleRefs.pasteFromClipboard(clip, tapX, tapY)
            }
            return
        }
        runPageOp {
            val env = runCatching { SoilClipboard.read(seam()) }.getOrNull()
            if (env == null || env.kind != ClipEnvelope.KIND_OBJECTS || env.rows.isEmpty()) {
                retireClipboard()
                Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_objects_paste_failed)
                return@runPageOp
            }
            if (doc.pageId != pageId) return@runPageOp
            val w = doc.pageWidth
            val h = doc.pageHeight
            val written = runCatching {
                doc.pasteObjects(env) { box ->
                    if (tapX != null && tapY != null) ObjectPlacement.centredOn(box, tapX, tapY, w, h) else ObjectPlacement.atSource(box, w, h)
                }
            }.onFailure { Log.w(TAG, "object paste failed: ${it.javaClass.simpleName}") }
            val plan = written.getOrNull()
            if (plan == null || plan.isEmpty) {
                // A payload that decoded but carries nothing this build can place is retired; a
                // write that threw is this attempt failing, and the clipboard is kept for a retry.
                if (written.isSuccess) retireClipboard()
                Dialogs.problem(this, R.string.clip_failed_title, R.string.clip_objects_paste_failed)
                return@runPageOp
            }
            undo.record(NotebookAction.ObjectsPasted(pageId, plan.contentIds))
            preparePaper()
            showPage(firstLoad = false, prebuilt = linkRenderer.prebuild(doc.links.values.toList()))
            refreshContents()
            // The pasted set, as the page now holds it (re-measured for this device), selected.
            val contentIds = (plan.headings.map { it.id } + plan.links.map { it.id } + plan.texts.map { it.id } + plan.stickies.map { it.id }).toSet()
            val strokeIds = plan.strokes.mapTo(HashSet()) { it.id }
            var box: Bounds? = null
            for (id in contentIds) {
                val b = doc.headings[id]?.bounds ?: doc.links[id]?.bounds ?: doc.texts[id]?.bounds ?: doc.stickies[id]?.bounds ?: continue
                box = box?.union(b) ?: b
            }
            for (st in doc.strokes) if (st.id in strokeIds) box = box?.union(st.bounds) ?: st.bounds
            box?.let { bounds ->
                armLassoForLanding()
                paper.setSelection(strokeIds, contentIds, bounds)
                val selection = Selection(strokeIds, contentIds, bounds)
                selectionActive = true
                currentSelection = selection
                showObjectBar(selection)
            }
            toast(getString(R.string.objects_pasted_toast))
        }
    }

    /** The popup's Clear: the clipboard goes, in memory and in Soil. */
    private fun doClipboardClear() {
        if (!opened || closing) return
        lifecycleScope.launch {
            retireClipboard()
            toast(getString(R.string.clipboard_cleared_toast))
        }
    }

    /** Retire the clipboard and everything that advertises it. Never throws. */
    private suspend fun retireClipboard() {
        markClipboard(false)
        runCatching { withContext(Dispatchers.IO) { SoilClipboard.clear((application as NotesproutApp).soil.seam()) } }
        runCatching { withContext(Dispatchers.IO) { BibleClipboard.clear((application as NotesproutApp).soil.seam()) } }
    }

    /** Open the clipboard popup under the armed lasso, or keep the re-tap's silent no-op with nothing of ours to offer. */
    private fun showLassoPopup(anchor: View? = null) {
        if (!opened || closing || !(SoilClipboard.hasObjects || BibleClipboard.has)) return
        hideFloatingBars()
        if (lassoPopup.show(anchor)) pushExclusions()
    }

    private fun hideLassoPopup() {
        if (!::lassoPopup.isInitialized || !lassoPopup.isShowing) return
        lassoPopup.hide()
        pushExclusions()
    }

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) return
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ── Recognition ──────

    private val recognizerPort by lazy { SeamRecognizerPort { (application as NotesproutApp).soil.seam() } }

    /** The selection's ink in writing order: the recogniser reads a sequence. */
    private fun inkOf(doc: NotebookDocument, sel: Selection): List<Stroke> = doc.strokes.filter { it.id in sel.strokeIds }

    /**
     * H on ink: recognise the lassoed handwriting into a heading at [level]. Everything the
     * creation needs is captured now, since the selection may die before the answer comes. On
     * failure the ink is left as it was, and the flow has said why.
     */
    private fun convertToHeading(sel: Selection, level: Int) {
        if (!opened || closing) return
        val doc = document ?: return
        val pageId = doc.pageId
        val strokes = inkOf(doc, sel)
        if (strokes.isEmpty()) return
        val bounds = sel.bounds
        InkRecognition.run(this, recognizerPort, strokes, bounds.width, bounds.height, onRecognized = { title ->
            val text = HeadingPrefix.applyLevel(title, level)
            val (w, h) = HeadingRenderer.measure(text, density, scaledDensity)
            val heading = Heading(UUID.randomUUID().toString(), text, level, bounds.left, bounds.top, w, h, 0)
            placeConverted(pageId, strokes.map { it.id }, heading = heading, text = null)
        })
    }

    /** Make text: the same flow, the recogniser's line breaks kept, into a text object at the ink's top-left. */
    private fun convertToText(sel: Selection) {
        if (!opened || closing) return
        val doc = document ?: return
        val pageId = doc.pageId
        val strokes = inkOf(doc, sel)
        if (strokes.isEmpty()) return
        val bounds = sel.bounds
        InkRecognition.run(this, recognizerPort, strokes, bounds.width, bounds.height, multiLine = true, onRecognized = { source ->
            val pageW = doc.pageWidth
            val (w, h) = TextRenderer.measure(source, (pageW - bounds.left).toInt().coerceAtLeast(1), density, scaledDensity)
            val text = PageText(UUID.randomUUID().toString(), source, bounds.left, bounds.top, w, h, 0)
            placeConverted(pageId, strokes.map { it.id }, heading = null, text = text)
        })
    }

    /**
     * The success half of a conversion: the consumed ink erased, the object made, one undo step,
     * and the object landed selected through the dismissal the stroke removal performs, so a
     * smart-lasso session stays alive across it (the wrap's rule).
     */
    private fun placeConverted(pageId: String, strokeIds: List<String>, heading: Heading?, text: PageText?) {
        if (!opened || closing) return
        val doc = document ?: return
        runPageOp {
            if (doc.pageId != pageId) return@runPageOp
            val ink = doc.erase(strokeIds)
            val placedHeading = heading?.let { doc.createHeading(it) }
            val placedText = text?.let { doc.createText(it) }
            undo.record(NotebookAction.Converted(pageId, ink, placedHeading, placedText))
            doc.flushUntilClean()
            val landed: Pair<String, Bounds>? = placedHeading?.let { it.id to it.bounds } ?: placedText?.let { it.id to it.bounds }
            pendingSelection = landed?.let { (id, b) -> { selectObject(id, b) } }
            if (strokeIds.isNotEmpty()) paper.removeStrokes(strokeIds) else paper.clearSelection()
            pendingSelection?.let { pendingSelection = null; it() }
            paper.notifyContentChanged()
            refreshContents()
            Slog.d(TAG) { "converted ${strokeIds.size} strokes" }
        }
    }

    /** Tag on ink: recognised, then the tag screen opens prefilled for the person to correct. */
    private fun tagFromInk(sel: Selection) {
        if (!opened || closing) return
        val doc = document ?: return
        val strokes = inkOf(doc, sel)
        if (strokes.isEmpty()) return
        val bounds = sel.bounds
        InkRecognition.run(this, recognizerPort, strokes, bounds.width, bounds.height, onRecognized = { words ->
            val prefill = TagRules.prefill(words)
            lifecycleScope.launch {
                val staged = if (prefill == null) null else runCatching { withContext(Dispatchers.IO) { (application as NotesproutApp).soil.seam().stageText(prefill) } }.getOrNull()
                paper.clearSelection()
                openTags(pageTarget = true, mode = Seam.TAG_MODE_ADD, stagedId = staged)
            }
        })
    }

    // ── The Scratch Pad ──────

    /** Send on the bar: where the ink lands on the pad, then the send. The strokes are read now:
     *  the selection can die between the tap and the lock. */
    private fun askPadPlacement(sel: Selection) {
        if (!opened || closing || soilScreenShowing) return
        val ids = sel.strokeIds.toHashSet()
        if (ids.isEmpty()) return
        paper.releaseRender()
        ActionSheetDialog(this)
            .title(getString(R.string.scratch_placement_title))
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_plus, getString(R.string.scratch_placement_new_page)) { sendToPad(ids, Seam.PAD_PLACEMENT_NEW_PAGE) }
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_page, getString(R.string.scratch_placement_current_page)) { sendToPad(ids, Seam.PAD_PLACEMENT_CURRENT_PAGE) }
            .show()
    }

    /**
     * A copy of the lassoed ink to the pad: flushed, checked against the caps before anything
     * crosses, parked in Soil, and the pad opened over this notebook to land it. What the pad
     * gives back it copies to the clipboard, and it is pasted here like anything copied.
     */
    private fun sendToPad(ids: Set<String>, placement: Int) {
        if (!opened || closing || soilScreenShowing) return
        val doc = document ?: return
        runPageOp {
            doc.flushUntilClean()
            val picked = doc.strokes.filter { it.id in ids && it.points.isNotEmpty() }
            if (picked.isEmpty()) return@runPageOp
            if (!InkWire.withinLimits(picked)) {
                Dialogs.problem(this, R.string.scratch_too_large_title, R.string.scratch_too_large_body)
                return@runPageOp
            }
            val bytes = InkWire.encode(picked, doc.pageWidth, doc.pageHeight)
            val sent = runCatching { withContext(Dispatchers.IO) { (application as NotesproutApp).soil.seam().sendInkToPad(com.symmetricalpalmtree.soil.seam.SeamShared.write(bytes), placement) } }
            if (sent.isFailure) {
                Log.w(TAG, "send to the pad failed: ${sent.exceptionOrNull()?.javaClass?.simpleName}")
                Dialogs.problem(this, R.string.scratch_failed_title, R.string.scratch_send_failed_body)
                return@runPageOp
            }
            paper.clearSelection()
            openPad()
        }
    }

    /** Soil's pad over this notebook: another process, handed over as any Soil screen. Not for a
     *  result, since the pad's own task could not deliver one; [onResume] takes it up. */
    private fun openPad() {
        val intent = android.content.Intent(Seam.ACTION_SCRATCH_PAD).setPackage(com.symmetricalpalmtree.soil.notesprout.BuildConfig.SOIL_PACKAGE)
        padShowing = true
        startSoilScreen { startActivity(intent) }
    }

    // ── Tags ──────

    private fun showTagsPopup(anchor: View? = null) {
        if (!opened || closing) return
        hideFloatingBars()
        if (tagsPopup.show(anchor)) pushExclusions()
    }

    private fun hideTagsPopup() {
        if (!::tagsPopup.isInitialized || !tagsPopup.isShowing) return
        tagsPopup.hide()
        pushExclusions()
    }

    /** Soil's tag screen, on this notebook or on the page whose ink is on the paper, with a text
     *  parked for its field when there is one. Another process over this one: handed over as any
     *  Soil screen is. */
    private fun openTags(pageTarget: Boolean, mode: Int, stagedId: String? = null) {
        if (!opened || closing || soilScreenShowing) return
        val me = itemId ?: return
        val pageId = if (pageTarget) document?.pageId ?: return else null
        val intent = android.content.Intent(Seam.ACTION_TAGS)
            .setPackage(com.symmetricalpalmtree.soil.notesprout.BuildConfig.SOIL_PACKAGE)
            .putExtra(Seam.EXTRA_ITEM_ID, me)
            .putExtra(Seam.EXTRA_PAGE_ID, pageId)
            .putExtra(Seam.EXTRA_TAG_MODE, mode)
            .putExtra(Seam.EXTRA_STAGED_ID, stagedId)
        startSoilScreen { tagsLauncher.launch(intent) }
    }

    /**
     * The bar's Tag on a lone heading: its words become a tag on the page, silently, with a toast
     * once the write has landed. A title that is not a tag as it stands (blank, or over the cap)
     * opens the screen prefilled instead, so the act still finishes in one more gesture. The page
     * is captured at the tap: a flip racing the write still lands the tag on the right page.
     */
    private fun tagSelection(sel: Selection) {
        if (!opened || closing) return
        val doc = document ?: return
        val me = itemId ?: return
        val pageId = doc.pageId
        val lone = if (sel.strokeIds.isEmpty()) sel.contentIds.singleOrNull() else null
        val heading = lone?.let { doc.headings[it] }
        if (heading == null) {
            if (sel.contentIds.isEmpty() && sel.strokeIds.isNotEmpty()) tagFromInk(sel)
            return
        }
        val title = PageLabels.titleOf(listOf(heading)).orEmpty()
        if (!TagRules.isValid(title)) {
            val prefill = TagRules.prefill(title)
            lifecycleScope.launch {
                val staged = if (prefill == null) null else runCatching { withContext(Dispatchers.IO) { (application as NotesproutApp).soil.seam().stageText(prefill) } }.getOrNull()
                paper.clearSelection()
                openTags(pageTarget = true, mode = Seam.TAG_MODE_ADD, stagedId = staged)
            }
            return
        }
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { (application as NotesproutApp).soil.seam().assignTag(me, pageId, title) } }
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess { display ->
                paper.clearSelection()
                toast(getString(R.string.tag_applied_toast, display))
            }.onFailure { e ->
                Log.w(TAG, "tag failed: ${e.javaClass.simpleName}")
                Dialogs.problem(this@NotebookActivity, R.string.tags_failed_title, if (e.message == SeamLimits.TAGS_FULL) R.string.tags_full_body else R.string.tags_assign_failed_body)
            }
        }
    }

    // ── Paper ──────

    /** The showing page's paper decoded into [paperCache], off Main. Nothing to do for blank or a cached row. */
    private suspend fun preparePaper() {
        val doc = document ?: return
        val id = doc.currentPage?.templateId?.takeIf { it.isNotEmpty() } ?: return
        if (id in paperCache) return
        val bitmap = withContext(Dispatchers.IO) { Bitmaps.decodeBounded(doc.templateBlobOf(id), MAX_TEMPLATE_EDGE) } ?: return
        if (paperCache.size >= PAPER_CACHE_SIZE) paperCache.remove(paperCache.keys.first())
        paperCache[id] = bitmap
    }

    /**
     * Page template on the sheet: Soil's picker, started for a result with the page's token so
     * the paper in force is ticked. Soil's screen is another process over this one, so the
     * pipeline is handed over as for any Soil screen, and the session is not parked.
     */
    private fun openTemplatePicker() {
        if (!opened || closing || soilScreenShowing) return
        val doc = document ?: return
        lifecycleScope.launch {
            val token = runCatching { doc.currentTemplateToken() }.getOrNull()
            if (!opened || closing || soilScreenShowing) return@launch
            val intent = android.content.Intent(Seam.ACTION_PICK_TEMPLATE)
                .setPackage(com.symmetricalpalmtree.soil.notesprout.BuildConfig.SOIL_PACKAGE)
                .putExtra(Seam.EXTRA_CURRENT_TOKEN, token)
            startSoilScreen { templatePickerLauncher.launch(intent) }
        }
    }

    private fun startSoilScreen(launch: () -> Unit) {
        soilScreenShowing = true
        hideFloatingBars()
        dismissCollapsed()
        inAppHandoff = true
        paper.releaseForHandoff()
        try {
            launch()
        } catch (e: Exception) {
            Log.w(TAG, "Soil's screen would not open: ${e.javaClass.simpleName}")
            onSoilScreenClosed()
            Dialogs.problem(this, R.string.open_failed_title, R.string.open_no_soil)
        }
    }

    /** The result callback runs before `onResume`: the pipeline is reclaimed first of all. */
    private fun onSoilScreenClosed() {
        soilScreenShowing = false
        inAppHandoff = false
        if (opened) paper.resumeDrawing()
    }

    private fun onTemplatePicked(resultCode: Int, encoded: String?) {
        onSoilScreenClosed()
        if (resultCode != android.app.Activity.RESULT_OK) return
        // A pick this build cannot read is a cancel, never Blank: the two are one from here and only one is safe.
        val pick = TemplatePick.decode(encoded) ?: return
        applyPick(pick)
    }

    /** The pick names a card; the pixels are read here, through the seam, and the page takes them. */
    private fun applyPick(pick: TemplatePick) {
        val doc = document ?: return
        val pageId = doc.pageId
        runPageOp {
            if (doc.pageId != pageId) return@runPageOp
            val paper = withContext(Dispatchers.IO) { paperOf(pick) }
            if (paper == null) {
                Dialogs.problem(this@NotebookActivity, R.string.template_gone_title, R.string.template_gone_body)
                return@runPageOp
            }
            val change = try {
                doc.changeTemplate(paper, resources.displayMetrics.densityDpi.toFloat())
            } catch (e: NotebookDocument.PaperRenderFailed) {
                Dialogs.problem(this@NotebookActivity, R.string.template_render_failed_title, R.string.template_render_failed_body)
                return@runPageOp
            }
            // An apply is the one thing that makes paper recent, re-picking the paper in force included.
            withContext(Dispatchers.IO) { runCatching { (application as NotesproutApp).soil.seam().templateUsed(pick.cardId) } }
            if (change == null) return@runPageOp
            undo.record(change)
            preparePaper()
            showPage()
        }
    }

    private suspend fun paperOf(pick: TemplatePick): PaperSource? = when (pick) {
        TemplatePick.Blank -> PaperSource.Blank
        is TemplatePick.BuiltIn -> PaperSource.BuiltIn(pick.kind)
        is TemplatePick.Static -> try {
            val seam = (application as NotesproutApp).soil.seam()
            val info = seam.template(pick.id)
            if (info == null) null else PaperSource.Image(com.symmetricalpalmtree.soil.seam.SeamShared.readAndClose(seam.templateImage(pick.id)), TemplateFit.sanitize(info.fit))
        } catch (e: Exception) {
            Log.w(TAG, "the template could not be read: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Save as template: the page rastered as it would export, capped as an import is, parked in
     * Soil through the seam, then Soil's screen for the name and the folder. Nothing is written
     * to the notebook, no undo step, nothing recorded as recent.
     */
    private fun saveAsTemplate() {
        if (!opened || closing || soilScreenShowing) return
        val doc = document ?: return
        val store = storeRef ?: return
        val page = doc.currentPage ?: return
        val pageNumber = doc.pageNumber
        runPageOp {
            doc.flushUntilClean()
            if (doc.pageId != page.id) return@runPageOp
            val staged = try {
                withContext(Dispatchers.IO) {
                    val content = store.readPage(page)
                    val template = Bitmaps.decodeBounded(store.templateBlob(page.templateId), MAX_TEMPLATE_EDGE)
                    val bytes = try {
                        PageRaster.toWebp(
                            page.width.toInt(), page.height.toInt(), template, content, density,
                            PagePaints.of(scaledDensity, AppCompatResources.getDrawable(this@NotebookActivity, com.symmetricalpalmtree.soil.paper.R.drawable.ic_sticker_2)?.mutate()),
                        )
                    } finally {
                        template?.recycle()
                    }
                    val seed = TemplateNames.seedFor(PageLabels.titleOf(content.headings + content.links.flatMap { it.headings }), pageNumber)
                    if (TemplateImport.overCap(bytes.size)) throw TooBig(bytes.size)
                    (application as NotesproutApp).soil.seam().stageTemplate(com.symmetricalpalmtree.soil.seam.SeamShared.write(bytes)) to seed
                }
            } catch (e: TooBig) {
                Dialogs.problem(
                    this@NotebookActivity, R.string.template_import_too_big_title,
                    getString(R.string.template_import_too_big_body, TemplateImport.megabytes(e.bytes), TemplateImport.megabytes(TemplateImport.MAX_BLOB_BYTES)),
                )
                return@runPageOp
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the page could not be rastered: ${e.javaClass.simpleName}")
                Dialogs.problem(this@NotebookActivity, R.string.template_save_failed_title, R.string.template_save_failed_body)
                return@runPageOp
            }
            val intent = android.content.Intent(Seam.ACTION_SAVE_TEMPLATE)
                .setPackage(com.symmetricalpalmtree.soil.notesprout.BuildConfig.SOIL_PACKAGE)
                .putExtra(Seam.EXTRA_STAGED_ID, staged.first)
                .putExtra(Seam.EXTRA_SEED_NAME, staged.second)
            startSoilScreen { saveTemplateLauncher.launch(intent) }
        }
    }

    private class TooBig(val bytes: Int) : Exception()

    // ── Backlinks ──────

    /** What links to this page, and to the notebook as a whole, from Soil's link index. */
    private suspend fun backlinksTo(pageId: String): List<SeamBacklink> {
        val me = itemId ?: return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching { (application as NotesproutApp).soil.seam().backlinks(me) }.getOrDefault(emptyList())
        }.filter { it.targetPageId == null || it.targetPageId == pageId }
            .sortedWith(compareBy({ it.targetPageId == null }, { it.sourceName.lowercase() }))
    }

    private fun showBacklinks(into: List<SeamBacklink>) {
        if (!opened || closing || backlinksShowing) return
        backlinksShowing = true
        hideFloatingBars()
        dismissCollapsed()
        paper.releaseRender()
        pushExclusions()
        backlinks.show(into) { backlinksShowing = false; pushExclusions() }
    }

    /** Go to where the link was made: a page of this notebook, or another's. The origin is
     *  pushed, so a swipe up comes back here. */
    private fun followBacklink(b: SeamBacklink) {
        val doc = document ?: return
        val me = itemId ?: return
        if (!opened || closing) return
        // A link made in a document: the document opens over this notebook, and closing it
        // comes back here, so there is no hop to remember.
        if (b.sourceKind != NotebookSchema.KIND) { openElsewhere(b.sourceItemId); return }
        LinkTrail(this).push(TrailEntry(me, doc.pageId))
        if (b.sourceItemId == me) {
            runPageOp { flipTo(doc.pages.indexOfFirst { it.id == b.sourcePageId }) }
        } else {
            leaveFor(b.sourceItemId, b.sourcePageId)
        }
    }

    /** An item that is not a notebook, opened by Soil in the app for its kind, over this screen. */
    private fun openElsewhere(itemId: String) {
        if (!opened || closing) return
        hideFloatingBars()
        dismissCollapsed()
        val started = runCatching {
            startActivity(
                android.content.Intent(Seam.ACTION_FOLLOW).setPackage(com.symmetricalpalmtree.soil.notesprout.BuildConfig.SOIL_PACKAGE)
                    .putExtra(Seam.EXTRA_ITEM_ID, itemId),
            )
        }.isSuccess
        if (!started) Dialogs.problem(this, R.string.link_target_gone_title, R.string.link_follow_failed_body)
    }

    /** Leave this notebook for another, at [pageId] or at its own remembered page. The box
     *  goes up, and the switch is a new ask of this screen ([onNewIntent]). */
    private fun leaveFor(itemId: String, pageId: String?) {
        if (!opened || closing) return
        hideFloatingBars()
        dismissCollapsed()
        binding.openingOverlay.visibility = View.VISIBLE
        startActivity(intent(this, itemId, viaLink = true, initialPageId = pageId))
    }

    private fun confirmErasePage() {
        if (!opened || closing) return
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.erase_page_title)
                .setPositiveButton(R.string.erase_confirm) { _, _ -> runPageOp { doErase() } }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create(),
        ).show()
    }

    /** An empty page's erase is silent: nothing recorded, nothing repainted. */
    private suspend fun doErase() {
        val doc = document ?: return
        val erased = doc.eraseCurrent() ?: return
        undo.record(erased)
        showPage()
    }

    private fun pageIndex(): Int = document?.pageIndex ?: 0

    private suspend fun flipTo(index: Int) {
        val doc = document ?: return
        if (index < 0 || index >= doc.pageCount) return
        doc.goToIndex(index)
        // The paper and the composites off Main, before the frame that paints the page.
        preparePaper()
        showPage(firstLoad = false, prebuilt = linkRenderer.prebuild(doc.links.values.toList()))
    }

    private suspend fun doInsert(after: Boolean) {
        val doc = document ?: return
        undo.record(doc.insert(after))
        preparePaper()
        showPage()
    }

    private suspend fun doDelete() {
        val doc = document ?: return
        undo.record(doc.deleteCurrent())
        preparePaper()
        showPage()
    }

    /** The page-swap order is g-paper's law: clear for the swap, size, template, then strokes. */
    private fun showPage(firstLoad: Boolean, prebuilt: Map<String, android.graphics.Bitmap> = emptyMap()) {
        val doc = document ?: return
        paper.clearSelection()
        selectionActive = false
        currentSelection = null
        selectionBar.hide()
        hideFloatingBars()
        dismissCollapsed()
        if (!firstLoad) paper.clearForContentSwap()
        paper.setPageSize(doc.pageWidth.toInt(), doc.pageHeight.toInt())
        paper.setTemplate(doc.currentPage?.templateId?.takeIf { it.isNotEmpty() }?.let { paperCache[it] })
        // The objects are handed over before `loadStrokes`, which is the frame that paints the page.
        syncRenderers(prebuilt)
        paper.loadStrokes(doc.strokes)
        toolbar.setPage(doc.pageNumber, doc.pageCount)
    }

    private fun confirmDeletePage() {
        if (!opened || closing) return
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.delete_page_title)
                .setPositiveButton(R.string.delete_confirm) { _, _ -> runPageOp { doDelete() } }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create(),
        ).show()
    }

    // ── The app in front ──────

    /** Whether the pen is down, hovering, or just lifted; the menu stays away while it is. */
    override fun penIsActive(): Boolean = opened && penRecentlyActive()

    /** Let the panel go for a frame: the side menu is about to be drawn over this screen. */
    override fun letPanelGo() {
        if (opened && !closing && !paper.isPenActive) paper.releaseRender()
    }

    /** Release the pipeline: a paper screen of Soil's is about to open over this one. */
    override fun letPipelineGo() {
        if (opened && !closing) paper.releaseForHandoff()
    }

    /** The bars reach Soil's shell from this window while it is in front (its own filter is off over paper). */
    override fun onBarKey(event: android.view.KeyEvent) = (application as NotesproutApp).barKey(event)

    override fun onResume() {
        super.onResume()
        // The shade is device-wide: another screen may have picked since.
        if (::toolbar.isInitialized) applyPenShade()
        (application as NotesproutApp).front(this)
        // Back from the pad: the pipeline first of all.
        if (padShowing) { padShowing = false; onSoilScreenClosed() }
        // The clipboard is the library's, and something else may have copied to it while this
        // notebook was behind: the Scratch Pad, opened over it or from the menu.
        refreshClipboardMark()
    }

    override fun onPause() {
        (application as NotesproutApp).left(this)
        super.onPause()
    }

    // ── Park and resume ──────

    override fun onStart() {
        super.onStart()
        val open = session ?: return
        runPageOp { withContext(Dispatchers.IO) { open.resume() } }
    }

    override fun onStop() {
        super.onStop()
        val open = session ?: return
        // The cover on every way out but a hand-off to a screen of this app's own, the close
        // included: a notebook put down shows the library what it last showed.
        if (!inAppHandoff) captureCover()
        if (closing || inAppHandoff) return
        // After the pause flush, which holds the same lock and was queued first.
        appScope.launch {
            withContext(NonCancellable) {
                pageOps.withLock {
                    withContext(Dispatchers.IO) { runCatching { open.park() }.onFailure { Log.w(TAG, "park failed: ${it.javaClass.simpleName}") } }
                }
            }
        }
    }

    /**
     * The library card's cover: the showing page as the engine has it, rendered here on Main
     * (a frame of the window, not of the panel), encoded and sent off it. Never while the pen is
     * down, and never worth failing a park for.
     */
    private fun captureCover() {
        if (!opened || paper.isPenActive) return
        val id = itemId ?: return
        val full = runCatching { paper.renderToBitmap() }.getOrNull() ?: return
        appScope.launch(Dispatchers.IO) {
            try {
                val bytes = CoverSnapshot.encode(full)
                (application as NotesproutApp).soil.seam().setCover(id, com.symmetricalpalmtree.soil.seam.SeamShared.write(bytes))
            } catch (e: Exception) {
                Log.w(TAG, "the cover was not written: ${e.javaClass.simpleName}")
            } finally {
                full.recycle()
            }
        }
    }

    override fun onScreenDestroyed() {
        super.onScreenDestroyed()
        if (::recents.isInitialized) recents.dismiss()
        if (::contents.isInitialized) contents.dismiss()
        if (::backlinks.isInitialized) backlinks.dismiss()
        LinkPickerRelay.showing = null
        val open = session ?: return
        session = null
        appScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { open.close(true) }.onFailure { Log.w(TAG, "close failed: ${it.javaClass.simpleName}") }
        }
    }

    companion object {
        private const val TAG = "NotebookActivity"
        private const val NO_SUCH_ITEM = "there is no such item"
        private const val RECENTS_LIMIT = 20
        /** A page's paper is authored at the page's size; the bound only guards a foreign blob. */
        private const val MAX_TEMPLATE_EDGE = 4096
        private const val PAPER_CACHE_SIZE = 3
        private const val DEFAULT_HEADING_LEVEL = 1

        /** The page to land on, for an open through a link. Consumed once. */
        const val EXTRA_INITIAL_PAGE_ID = "initialPageId"

        /** An open through a link keeps the trail; any other open starts a new story. */
        const val EXTRA_VIA_LINK = "viaLink"

        /** Outlives the screen, so a park or a close in flight always completes. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun intent(context: android.content.Context, itemId: String, viaLink: Boolean, initialPageId: String?): android.content.Intent =
            android.content.Intent(context, NotebookActivity::class.java)
                .setAction(Seam.ACTION_OPEN_ITEM)
                .putExtra(Seam.EXTRA_ITEM_ID, itemId)
                .putExtra(EXTRA_VIA_LINK, viaLink)
                .putExtra(EXTRA_INITIAL_PAGE_ID, initialPageId)
    }
}
