package com.symmetricalpalmtree.soil.notesprout.links

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.notesprout.NotesproutApp
import com.symmetricalpalmtree.soil.notesprout.R
import com.symmetricalpalmtree.soil.notesprout.data.NotebookSchema
import com.symmetricalpalmtree.soil.notesprout.data.PageRef
import com.symmetricalpalmtree.soil.notesprout.databinding.ActivityLinkPickerBinding
import com.symmetricalpalmtree.soil.notesprout.links.LinkPickerModel.PickMode
import com.symmetricalpalmtree.soil.notesprout.notebook.PagePaints
import com.symmetricalpalmtree.soil.notesprout.objects.LinkPayload
import com.symmetricalpalmtree.soil.notesprout.objects.PageLabels
import com.symmetricalpalmtree.soil.paper.chrome.DayPickerDialog
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.CalendarDates
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Where a link points**: the one screen behind the selection bar's Link and its Edit link. It
 * answers one question and returns one string, the payload the notebook writes onto the row.
 *
 * Four shelves, in the mode row's order:
 *  1. **This notebook**: its pages, minus the one being written on (a link to here goes
 *     nowhere). The numbers count the whole notebook.
 *  2. **Notebook**: the library's notebooks, the open one hidden.
 *  3. **Notebook page**: the same list, but a notebook opens into its pages.
 *  4. **A calendar day**: the shared day picker (Calsprout, 2026-10-06); the day chosen is
 *     named in the body, and a tap on it asks again.
 *
 * Every page card shows the page in miniature, rendered behind a placeholder: the open
 * notebook's through the screen's own store ([LinkPickerRelay]), another's through a session
 * of its own ([ForeignNotebook]), one at a time, closed the moment it is left. A page with a
 * heading is named by it.
 *
 * The target may not exist yet: a page grid offers **New page**, a browse **New notebook**, and
 * the thing made becomes the choice. Picker creations are not undoable.
 *
 * Nothing here is disabled or greyed. An OK with nothing chosen explains.
 *
 * There is one library, in Soil: the Notebook shelves do not browse it here. Entering either
 * opens **Soil's item picker** over this screen, folders and all, and the notebook it answers
 * is the one shown; a tap on it, or the shelf's own button, asks again.
 */
class LinkPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkPickerBinding
    private lateinit var showing: LinkPickerRelay.Showing
    private lateinit var grid: CardGrid

    private var editing = false
    private var mode = PickMode.THIS_NOTEBOOK
    private var chrome = LinkPayload.CHROME_UNDERLINE
    private var selectedNotebookId: String? = null
    private var selectedPageId: String? = null

    /** The day the calendar shelf holds (`yyyy-MM-dd`), or nothing yet. */
    private var selectedDate: String? = null

    /** The notebook whose pages are on screen in [PickMode.NOTEBOOK_PAGE], with its session. */
    private var drilled: SeamItem? = null
    private var foreign: ForeignNotebook? = null

    private var pageIndex = 0
    private var pageCount = 1
    private var notebooks: List<SeamItem> = emptyList()
    private var pageItems: List<Pair<PageRef, Int>> = emptyList()

    /** What a page card shows once read, kept for this showing alone and dropped whole past a cap. */
    private class Preview(val bitmap: Bitmap?, val title: String?)
    private val previews = HashMap<String, Preview>()

    private var density = 1f
    private var scaledDensity = 1f
    private var stickyIconSource: Drawable? = null

    /** One create at a time: a second tap in the e-ink's gap would make a second page. */
    private var creating = false

    private val soil get() = (application as NotesproutApp).soil

    /** Soil's item picker, for the two Notebook shelves. A cancel with nothing chosen yet goes
     *  back to This notebook: a shelf with nothing on it is not a place to wait. */
    private val itemPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.data?.getStringExtra(Seam.EXTRA_ITEM_ID)
        if (result.resultCode != Activity.RESULT_OK || id == null) {
            if (selectedNotebookId == null && drilled == null) setMode(PickMode.THIS_NOTEBOOK)
            return@registerForActivityResult
        }
        val pageId = result.data?.getStringExtra(Seam.EXTRA_PAGE_ID)?.takeIf { it.isNotEmpty() }
        lifecycleScope.launch {
            val item = aliveTarget(id) ?: return@launch
            when (mode) {
                PickMode.NOTEBOOK -> { selectedNotebookId = item.id; refresh(jumpToSelection = true) }
                PickMode.NOTEBOOK_PAGE -> when {
                    // Soil named the page (a sketchbook's, from its own app): the link is complete.
                    pageId != null -> { selectedNotebookId = item.id; selectedPageId = pageId; onOk() }
                    // A notebook's pages are previewed here, from its own file.
                    item.kind == NotebookSchema.KIND -> { drill(item); pageIndex = 0; refresh() }
                    // Any other kind with pages: Soil asks its app for them, straight away.
                    else -> launchPageOf(item)
                }
                PickMode.THIS_NOTEBOOK, PickMode.CAL_DAY -> Unit
            }
        }
    }

    /** Soil's picker again, straight at [item]'s pages — a sketchbook's, named by its own app. */
    private fun launchPageOf(item: SeamItem) {
        val intent = Intent(Seam.ACTION_PICK_ITEM)
            .setPackage(com.symmetricalpalmtree.soil.notesprout.BuildConfig.SOIL_PACKAGE)
            .putExtra(Seam.EXTRA_PAGE_OF_ITEM, item.id)
            .putExtra(Seam.EXTRA_PICK_PAGE, true)
        try {
            itemPickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Soil's picker would not open: ${e.javaClass.simpleName}")
        }
    }

    private fun launchItemPicker() {
        // A link to a whole item may point at any kind of item; a link to a page needs an item
        // with pages: a notebook, or a sketchbook (its pages named by its own app).
        val intent = Intent(Seam.ACTION_PICK_ITEM)
            .setPackage(com.symmetricalpalmtree.soil.notesprout.BuildConfig.SOIL_PACKAGE)
            .putExtra(Seam.EXTRA_KIND, if (mode == PickMode.NOTEBOOK_PAGE) PAGED_KINDS else null)
            .putExtra(Seam.EXTRA_EXCLUDE_ITEM_ID, showing.notebookId)
        try {
            itemPickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Soil's picker would not open: ${e.javaClass.simpleName}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val relay = LinkPickerRelay.showing
        if (relay == null) {
            Slog.d(TAG) { "no relay: the process was rebuilt under the picker" }
            finish()
            return
        }
        showing = relay
        binding = ActivityLinkPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        val dm = resources.displayMetrics
        density = dm.density
        scaledDensity = dm.scaledDensity
        stickyIconSource = AppCompatResources.getDrawable(this, com.symmetricalpalmtree.soil.paper.R.drawable.ic_sticker_2)

        val prefill = intent.getStringExtra(EXTRA_INITIAL_PAYLOAD)
        editing = prefill != null
        val decoded = prefill?.let { LinkPayload.decode(it) }
        mode = LinkPickerModel.modeFor(decoded)
        chrome = LinkPickerModel.chromeFor(decoded)
        wire()

        var measured = false
        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (measured) return@addOnGlobalLayoutListener
            val w = binding.gridContainer.width - binding.gridContainer.paddingLeft - binding.gridContainer.paddingRight
            val h = binding.gridContainer.height - binding.gridContainer.paddingTop - binding.gridContainer.paddingBottom
            if (w <= 0 || h <= 0) return@addOnGlobalLayoutListener
            measured = true
            grid = CardGrid(binding.gridContainer, w, h, density)
            lifecycleScope.launch {
                applyPrefill(decoded)
                refresh(jumpToSelection = true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        foreign?.close()
        foreign = null
    }

    /** Put the picker where the link already points. A target that is gone falls back silently. */
    private suspend fun applyPrefill(decoded: LinkPayload.Decoded?) {
        if (decoded == null) return
        when (decoded.kind) {
            LinkPayload.KIND_PAGE -> selectedPageId = decoded.pageId
            LinkPayload.KIND_ITEM -> selectedNotebookId = aliveNotebook(decoded.itemId)?.id
            LinkPayload.KIND_ITEM_PAGE -> {
                val target = alivePaged(decoded.itemId) ?: return
                // A notebook's pages are previewed; a sketchbook's page stands as chosen.
                if (target.kind == NotebookSchema.KIND) drill(target) else selectedNotebookId = target.id
                selectedPageId = decoded.pageId
            }
            LinkPayload.KIND_CAL -> selectedDate = decoded.date
        }
    }

    private suspend fun aliveNotebook(id: String?): SeamItem? = aliveItem(id)?.takeIf { it.kind == NotebookSchema.KIND }

    /** An item with pages a link may name: a notebook, or a sketchbook. */
    private suspend fun alivePaged(id: String?): SeamItem? = aliveItem(id)?.takeIf { it.kind == NotebookSchema.KIND || it.kind == KIND_SKETCHBOOK }

    /** What the shelf in use may point at: any item for a whole-item link, a paged one otherwise. */
    private suspend fun aliveTarget(id: String?): SeamItem? = if (mode == PickMode.NOTEBOOK) aliveItem(id) else alivePaged(id)

    private suspend fun aliveItem(id: String?): SeamItem? {
        if (id == null || id == showing.notebookId) return null
        return withContext(Dispatchers.IO) { runCatching { soil.seam().item(id) }.getOrNull() }
    }

    private fun wire() = with(binding) {
        btnCancel.setOnClickListener { finish() }
        btnOk.setOnClickListener { onOk() }
        btnUp.setOnClickListener { leaveDrill(); pageIndex = 0; lifecycleScope.launch { refresh() } }
        btnModeThisNotebook.setOnClickListener { setMode(PickMode.THIS_NOTEBOOK) }
        btnModeNotebook.setOnClickListener { setMode(PickMode.NOTEBOOK) }
        btnModeNotebookPage.setOnClickListener { setMode(PickMode.NOTEBOOK_PAGE) }
        btnModeCalDay.setOnClickListener { setMode(PickMode.CAL_DAY) }
        // The calendar shelf's body names the day; a tap on it asks again.
        emptyState.setOnClickListener { if (mode == PickMode.CAL_DAY) askDay() }
        btnStyleUnderline.setOnClickListener { chrome = LinkPayload.CHROME_UNDERLINE; renderStyle() }
        btnStyleNone.setOnClickListener { chrome = LinkPayload.CHROME_NONE; renderStyle() }
        btnNewPage.setOnClickListener { onNewPage() }
        btnNewNotebook.setOnClickListener { launchItemPicker() }
        btnFirst.setOnClickListener { goToPage(0) }
        btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
        btnNext.setOnClickListener { goToPage(pageIndex + 1) }
        btnLast.setOnClickListener { goToPage(pageCount - 1) }
    }

    private fun renderChrome() = with(binding) {
        btnModeThisNotebook.isSelected = mode == PickMode.THIS_NOTEBOOK
        btnModeNotebook.isSelected = mode == PickMode.NOTEBOOK
        btnModeNotebookPage.isSelected = mode == PickMode.NOTEBOOK_PAGE
        btnModeCalDay.isSelected = mode == PickMode.CAL_DAY
        renderStyle()
        val creates = LinkPickerModel.createButtons(mode, drilled != null)
        btnNewPage.visibility = if (creates.newPage) View.VISIBLE else View.GONE
        btnNewNotebook.visibility = if (creates.newNotebook) View.VISIBLE else View.GONE
        val base = getString(if (editing) R.string.link_edit_action else R.string.link_picker_title)
        val d = drilled
        title.text = if (d != null) getString(R.string.link_picker_title_in, base, d.name) else base
        btnUp.visibility = if (d != null) View.VISIBLE else View.GONE
    }

    private fun renderStyle() = with(binding) {
        btnStyleUnderline.isSelected = chrome == LinkPayload.CHROME_UNDERLINE
        btnStyleNone.isSelected = chrome == LinkPayload.CHROME_NONE
    }

    // ── Listing ──────

    private fun showingPages(): Boolean = mode == PickMode.THIS_NOTEBOOK || drilled != null

    private fun activeSource(): PickerSource? = if (mode == PickMode.THIS_NOTEBOOK) showing.source else foreign

    private suspend fun refresh(jumpToSelection: Boolean = false) {
        renderChrome()
        when {
            mode == PickMode.CAL_DAY -> refreshDay()
            showingPages() -> refreshPages(jumpToSelection)
            else -> refreshBrowse(jumpToSelection)
        }
    }

    /** The calendar shelf: no cards, the chosen day's words in the body, or the ask. */
    private fun refreshDay() {
        notebooks = emptyList()
        pageItems = emptyList()
        pageCount = 1
        pageIndex = 0
        grid.bind(0) { _, _ -> }
        binding.pager.visibility = View.GONE
        val day = selectedDate?.let { CalendarDates.parse(it) }
        binding.emptyState.text = if (day == null) getString(R.string.link_picker_choose_day) else getString(R.string.link_picker_day_chosen, CalendarDates.dayLabel(day))
        binding.emptyState.visibility = View.VISIBLE
    }

    /** The shared day picker, on the day held or today. A cancel keeps what was held. */
    private fun askDay() {
        val initial = selectedDate?.let { CalendarDates.parse(it) } ?: java.time.LocalDate.now()
        DayPickerDialog.show(this, initial) { day ->
            selectedDate = CalendarDates.format(day)
            if (mode == PickMode.CAL_DAY) refreshDay()
        }
    }

    private suspend fun refreshPages(jumpToSelection: Boolean) {
        notebooks = emptyList()
        val all = activeSource()?.pages().orEmpty()
        val exclude = if (mode == PickMode.THIS_NOTEBOOK) showing.currentPageId else null
        pageItems = LinkPickerModel.pageCards(all, { it.id }, exclude)
        showEmpty(pageItems.isEmpty(), if (mode == PickMode.THIS_NOTEBOOK) R.string.link_picker_no_pages else R.string.link_picker_no_foreign_pages)
        pageCount = LinkPickerModel.pageCount(pageItems.size, grid.cardsPerPage)
        pageIndex = if (jumpToSelection) selectedPage(pageItems.indexOfFirst { it.first.id == selectedPageId }) else LinkPickerModel.clampPage(pageIndex, pageCount)
        bindCurrentPage()
    }

    /** The Notebook shelf holds the one notebook Soil's picker answered, or nothing yet. */
    private suspend fun refreshBrowse(jumpToSelection: Boolean) {
        pageItems = emptyList()
        notebooks = listOfNotNull(selectedNotebookId?.let { aliveTarget(it) })
        showEmpty(notebooks.isEmpty(), R.string.link_picker_choose_notebook)
        pageCount = 1
        pageIndex = 0
        bindCurrentPage()
    }

    private fun selectedPage(itemIndex: Int): Int =
        if (itemIndex < 0) 0 else LinkPickerModel.clampPage(LinkPickerModel.gridPageOf(itemIndex, grid.cardsPerPage), pageCount)

    private fun showEmpty(empty: Boolean, messageRes: Int) = with(binding.emptyState) {
        setText(messageRes)
        visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun bindCurrentPage() {
        val from = pageIndex * grid.cardsPerPage
        if (showingPages()) {
            val slice = pageItems.drop(from).take(grid.cardsPerPage)
            grid.bind(slice.size) { i, card -> bindPageCard(card, slice[i].first, slice[i].second) }
        } else {
            val slice = notebooks.drop(from).take(grid.cardsPerPage)
            grid.bind(slice.size) { i, card -> bindNotebookCard(card, slice[i]) }
        }
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = getString(R.string.page_indicator, pageIndex + 1, pageCount)
    }

    // ── Cards ──────

    private fun bindNotebookCard(card: Card, item: SeamItem) {
        card.image.setImageBitmap(null)
        card.image.visibility = View.GONE
        card.name.visibility = View.VISIBLE
        card.name.text = item.name
        // A notebook is told by its pages; anything else by what it is.
        card.label.text = when (item.kind) {
            NotebookSchema.KIND -> resources.getQuantityString(R.plurals.link_notebook_pages, item.pageCount, item.pageCount)
            KIND_SKETCHBOOK -> resources.getQuantityString(R.plurals.link_sketchbook_pages, item.pageCount, item.pageCount)
            KIND_DOCUMENT -> getString(R.string.link_item_document)
            else -> getString(R.string.link_item_other)
        }
        card.root.isSelected = item.id == selectedNotebookId
        card.root.setOnClickListener { onNotebookTap(item) }
    }

    /** The label reads "Page n" from the moment the card binds; the miniature and the heading's
     *  name arrive when read. A late result checks the card's tag before painting. */
    private fun bindPageCard(card: Card, page: PageRef, position: Int) {
        card.name.visibility = View.GONE
        card.image.visibility = View.VISIBLE
        card.root.tag = page.id
        card.root.isSelected = page.id == selectedPageId
        card.root.setOnClickListener { onPageTap(page) }
        val cached = previews[page.id]
        card.label.text = labelFor(position, cached?.title)
        card.image.setImageBitmap(cached?.bitmap)
        if (cached != null) return
        val source = activeSource() ?: return
        val width = grid.cardWidth
        lifecycleScope.launch {
            val content = source.content(page) ?: return@launch
            val (w, h) = PreviewMath.renderSize(width, page.width.toInt(), page.height.toInt())
            val entry = withContext(Dispatchers.Default) {
                val icon = stickyIconSource?.constantState?.newDrawable()?.mutate()
                Preview(
                    bitmap = PagePreview.render(page, content, w, h, density, PagePaints.of(scaledDensity, icon)),
                    title = PageLabels.titleOf(content.headings + content.links.flatMap { it.headings }),
                )
            }
            if (previews.size >= MAX_CACHED_PREVIEWS) previews.clear()
            previews[page.id] = entry
            if (card.root.tag != page.id) return@launch
            card.label.text = labelFor(position, entry.title)
            card.image.setImageBitmap(entry.bitmap)
        }
    }

    private fun labelFor(position: Int, title: String?): String =
        if (title.isNullOrEmpty()) getString(R.string.link_page_label, position) else getString(R.string.link_page_label_titled, position, title)

    // ── Taps ──────

    /** The chosen notebook's card: a tap asks Soil for another. */
    private fun onNotebookTap(@Suppress("UNUSED_PARAMETER") item: SeamItem) = launchItemPicker()

    private fun onPageTap(page: PageRef) {
        selectedPageId = if (selectedPageId == page.id) null else page.id
        bindCurrentPage()
    }

    private fun setMode(newMode: PickMode) {
        if (mode == newMode) return
        mode = newMode
        // A target chosen for one kind of link means nothing for another.
        selectedNotebookId = null
        selectedPageId = null
        selectedDate = null
        leaveDrill()
        pageIndex = 0
        lifecycleScope.launch { refresh() }
        // The two Notebook shelves begin by asking Soil which notebook; the calendar shelf by
        // asking which day.
        when (newMode) {
            PickMode.NOTEBOOK, PickMode.NOTEBOOK_PAGE -> launchItemPicker()
            PickMode.CAL_DAY -> askDay()
            PickMode.THIS_NOTEBOOK -> Unit
        }
    }

    private fun drill(item: SeamItem) {
        leaveDrill()
        drilled = item
        selectedNotebookId = item.id
        foreign = ForeignNotebook(soil, item.id)
        Slog.d(TAG) { "drilled into a notebook" }
    }

    private fun leaveDrill() {
        if (drilled == null) return
        foreign?.close()
        foreign = null
        drilled = null
        selectedNotebookId = null
        selectedPageId = null
        previews.clear()
    }

    private fun goToPage(index: Int) {
        val clamped = LinkPickerModel.clampPage(index, pageCount)
        if (clamped == pageIndex) return
        pageIndex = clamped
        bindCurrentPage()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (drilled != null) {
            leaveDrill()
            pageIndex = 0
            lifecycleScope.launch { refresh() }
        } else {
            @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ── Create ──────

    /** A selected card is the anchor, and the sheet asks which side; nothing selected appends. */
    private fun onNewPage() {
        val anchor = selectedPageId
        if (anchor == null) { createPage(null, before = false); return }
        ActionSheetDialog(this)
            .addAction(null, getString(R.string.link_insert_before)) { createPage(anchor, before = true) }
            .addAction(null, getString(R.string.link_insert_after)) { createPage(anchor, before = false) }
            .show()
    }

    private fun createPage(anchorId: String?, before: Boolean) {
        if (creating) return
        creating = true
        lifecycleScope.launch {
            try {
                val created = activeSource()?.createPage(anchorId, before)
                if (created == null) {
                    Dialogs.problem(this@LinkPickerActivity, R.string.link_new_page_failed_title, R.string.link_new_page_failed_body)
                    return@launch
                }
                selectedPageId = created.id
                refresh(jumpToSelection = true)
            } finally {
                creating = false
            }
        }
    }

    // ── OK ──────

    private fun onOk() {
        val payload = LinkPickerModel.composeOk(mode, chrome, showing.notebookId, selectedNotebookId, selectedPageId, selectedDate)
        if (payload == null) {
            Dialogs.problem(
                this, R.string.link_pick_none_title,
                when (mode) {
                    PickMode.THIS_NOTEBOOK -> R.string.link_pick_none_page
                    PickMode.NOTEBOOK -> R.string.link_pick_none_notebook
                    PickMode.NOTEBOOK_PAGE -> R.string.link_pick_none_notebook_page
                    PickMode.CAL_DAY -> R.string.link_pick_none_day
                },
            )
            return
        }
        Slog.d(TAG) { "picked a $mode target, chrome=$chrome" }
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_PAYLOAD, payload))
        finish()
    }

    /**
     * A paged grid of cards, built once for the container's size: three across, each card the
     * library-card shape with a label under it. Cards are made before the page shows and bound
     * in place; the grid never scrolls.
     */
    class Card(val root: FrameLayout, val image: ImageView, val name: AppCompatTextView, val label: AppCompatTextView)

    class CardGrid(private val container: FrameLayout, width: Int, height: Int, density: Float) {
        private val gap = (GAP_DP * density).toInt()
        private val labelH = (LABEL_DP * density).toInt()
        val cardWidth: Int = ((width - gap * (COLUMNS - 1)) / COLUMNS).coerceAtLeast(1)
        private val cardHeight: Int = (cardWidth * CARD_ASPECT).toInt() + labelH
        private val rows: Int = ((height + gap) / (cardHeight + gap)).coerceAtLeast(1)
        val cardsPerPage: Int = rows * COLUMNS
        private val cards = ArrayList<Card>(cardsPerPage)
        private val layer = FrameLayout(container.context)

        init {
            container.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val ctx = container.context
            val ink = ctx.getColor(com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
            for (i in 0 until cardsPerPage) {
                val col = i % COLUMNS
                val row = i / COLUMNS
                val root = FrameLayout(ctx).apply {
                    setBackgroundResource(com.symmetricalpalmtree.soil.paper.R.drawable.bg_selectable_card)
                    visibility = View.GONE
                }
                val pad = (6 * density).toInt()
                val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight - labelH).apply { setMargins(pad, pad, pad, 0) })
                val name = AppCompatTextView(ctx).apply {
                    textSize = 16f; setTextColor(ink); gravity = Gravity.CENTER; maxLines = 4
                    setPadding(pad * 2, pad, pad * 2, pad)
                }
                root.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight - labelH).apply { gravity = Gravity.CENTER })
                val label = AppCompatTextView(ctx).apply {
                    textSize = 13f; setTextColor(ink); gravity = Gravity.CENTER; maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(pad, 0, pad, 0)
                }
                root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, labelH).apply { gravity = Gravity.BOTTOM })
                layer.addView(
                    root,
                    FrameLayout.LayoutParams(cardWidth, cardHeight).apply {
                        leftMargin = col * (cardWidth + gap)
                        topMargin = row * (cardHeight + gap)
                    },
                )
                cards += Card(root, image, name, label)
            }
        }

        fun bind(count: Int, binder: (Int, Card) -> Unit) {
            for ((i, card) in cards.withIndex()) {
                if (i < count) {
                    card.root.tag = null
                    card.root.visibility = View.VISIBLE
                    binder(i, card)
                } else {
                    card.root.visibility = View.GONE
                    card.root.setOnClickListener(null)
                    card.image.setImageBitmap(null)
                }
            }
        }

        private companion object {
            const val COLUMNS = 3
            const val GAP_DP = 12f
            const val LABEL_DP = 28f
            const val CARD_ASPECT = 4f / 3f
        }
    }

    companion object {
        private const val TAG = "LinkPickerActivity"

        /** The library's names for a document and a sketchbook: the other kinds this picker names. */
        private const val KIND_DOCUMENT = "document"
        private const val KIND_SKETCHBOOK = "sketchbook"

        /** The kinds with pages a page link may name, as Soil's picker takes them. */
        private const val PAGED_KINDS = "notebook,sketchbook"
        const val EXTRA_INITIAL_PAYLOAD = "initialPayload"
        const val EXTRA_RESULT_PAYLOAD = "resultPayload"
        private const val MAX_CACHED_PREVIEWS = 36

        fun intent(context: Context, initialPayload: String?): Intent =
            Intent(context, LinkPickerActivity::class.java).putExtra(EXTRA_INITIAL_PAYLOAD, initialPayload)
    }
}
