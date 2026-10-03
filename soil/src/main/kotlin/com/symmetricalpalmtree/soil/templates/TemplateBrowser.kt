package com.symmetricalpalmtree.soil.templates

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.TemplateRow
import com.symmetricalpalmtree.soil.data.index.TemplateStore
import com.symmetricalpalmtree.soil.databinding.ViewTemplateBrowserBinding
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.FuzzyRank
import com.symmetricalpalmtree.soil.paper.core.GridMath
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.templates.TemplateIds
import com.symmetricalpalmtree.soil.paper.templates.TemplateNames
import com.symmetricalpalmtree.soil.paper.templates.TemplatePick
import com.symmetricalpalmtree.soil.paper.templates.TemplateToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **The template browser**: breadcrumbs, the paged card grid, sort, New folder, Import, the
 * long-press sheets, the Move picker and the three shelves. One component for every host; a host
 * supplies what a tap on a paper card means ([onPick]) and which card is the paper in force
 * ([selection]), by card id or by the page's token.
 *
 * The shelves (Pinned, Recents, Search) are flat views across the tree, one at a time, and
 * nothing about them persists: the browser opens in the tree at the root, every time. Inside
 * Default and on a shelf, the controls that act on a folder stand down: GONE, never disabled.
 * Construct in `onCreate`: launchers are registered here.
 */
class TemplateBrowser(
    private val activity: AppCompatActivity,
    private val binding: ViewTemplateBrowserBinding,
    private val onPick: (TemplatePick) -> Unit,
    private val selection: () -> Selection = { Selection() },
) {
    data class Selection(val cardId: String? = null, val token: String? = null)

    enum class Shelf { NONE, PINNED, RECENTS, SEARCH }

    private val store = TemplateStore()
    private val prefs = TemplatePrefs(activity)
    private val transfer = TemplateTransfer(activity, { store }, { folderId }, onChanged = { reload() })

    /** `""` is the root; [TemplateIds.DEFAULT_FOLDER] the reserved folder; else a row. */
    private var folderId: String = ""
    private var shelf = Shelf.NONE
    private var query = ""
    private var pinnedIds: Set<String> = emptySet()
    private var pageIndex = 0
    private var pageCount = 1
    private var items: List<TemplateCard> = emptyList()
    private var grid: TemplateCardGrid? = null
    private val pageWidthPx: Int
    private val pageHeightPx: Int

    private val moveLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) reload()
    }

    init {
        val metrics = activity.resources.displayMetrics
        pageWidthPx = minOf(metrics.widthPixels, metrics.heightPixels)
        pageHeightPx = maxOf(metrics.widthPixels, metrics.heightPixels)
        with(binding) {
            btnSort.setOnClickListener { showSortSheet() }
            btnNewFolder.setOnClickListener { showNewFolderDialog() }
            btnImport.setOnClickListener { transfer.startImport() }
            btnPinned.setOnClickListener { toggleShelf(Shelf.PINNED) }
            btnRecents.setOnClickListener { toggleShelf(Shelf.RECENTS) }
            btnSearch.setOnClickListener { openSearchDialog() }
            btnBack.setOnClickListener { stepBack() }
            btnFirst.setOnClickListener { goToPage(0) }
            btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
            btnNext.setOnClickListener { goToPage(pageIndex + 1) }
            btnLast.setOnClickListener { goToPage(pageCount - 1) }
        }
        var measured = false
        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (measured) return@addOnGlobalLayoutListener
            val w = binding.gridContainer.width
            val h = binding.gridContainer.height
            if (w <= 0 || h <= 0) return@addOnGlobalLayoutListener
            measured = true
            grid = TemplateCardGrid(binding.gridContainer, ::onCardTap, ::onCardLongPress).also { it.measure(activity, w, h) }
            reload()
        }
    }

    // ── Host API ──────

    fun reload() { activity.lifecycleScope.launch { refresh() } }

    /** Redraw the page alone: the host's selection changed and nothing in the store did. */
    fun refreshSelection() { activity.lifecycleScope.launch { bindCurrentPage() } }

    fun showCloseButton(onClose: () -> Unit) {
        binding.btnClose.setOnClickListener { onClose() }
        binding.btnClose.visibility = View.VISIBLE
    }

    fun saveState(outState: Bundle) = transfer.saveState(outState)
    fun restoreState(saved: Bundle?) = transfer.restoreState(saved)

    /** Back peels one layer: out of a shelf, up a folder. False when there is nothing to peel. */
    fun onBackPressed(): Boolean {
        if (shelf != Shelf.NONE) { closeShelf(); return true }
        if (folderId.isEmpty()) return false
        navigateUp()
        return true
    }

    private fun stepBack() { onBackPressed() }

    // ── Listing ──────

    private val inDefaults: Boolean get() = folderId == TemplateIds.DEFAULT_FOLDER

    private fun builtInLabels() = listOf(activity.getString(R.string.template_lined), activity.getString(R.string.template_dotted), activity.getString(R.string.template_grid))

    private suspend fun refresh() {
        renderChrome()
        val listed = withContext(Dispatchers.IO) {
            pinnedIds = store.pinnedIds().toSet()
            when {
                shelf != Shelf.NONE -> shelfCards()
                inDefaults -> TemplateLibrary.defaultCards(builtInLabels())
                folderId.isEmpty() -> TemplateLibrary.rootCards(activity.getString(R.string.template_blank), activity.getString(R.string.template_default_folder), sortedRows(""))
                else -> TemplateLibrary.rowCards(sortedRows(folderId))
            }
        }
        items = listed
        binding.emptyState.setText(emptyTextRes())
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        pageCount = GridMath.pageCount(items.size, grid?.cardsPerPage ?: 1)
        pageIndex = GridMath.clampPage(pageIndex, pageCount)
        bindCurrentPage()
    }

    private fun sortedRows(parentId: String): List<TemplateRow> =
        TemplateLibrary.sorted(store.folders(parentId) + store.templates(parentId), prefs.sortField, prefs.sortOrder)

    private fun shelfCards(): List<TemplateCard> = when (shelf) {
        Shelf.PINNED -> {
            val alive = store.aliveTemplates(TemplateLibrary.rowIdsAmong(pinnedIds))
            TemplateLibrary.pinnedCards(pinnedIds, TemplateLibrary.sorted(alive.values.toList(), prefs.sortField, prefs.sortOrder), builtInLabels())
        }
        Shelf.RECENTS -> {
            val recents = prefs.recents()
            val alive = store.aliveTemplates(TemplateLibrary.rowIdsAmong(recents))
            prefs.prune(TemplateLibrary.pruneable(alive.keys))
            TemplateLibrary.recentCards(recents, alive, builtInLabels())
        }
        Shelf.SEARCH -> TemplateLibrary.searchCards(query, activity.getString(R.string.template_blank), builtInLabels(), store.allTemplates())
        Shelf.NONE -> emptyList()
    }

    private fun emptyTextRes(): Int = when (shelf) {
        Shelf.PINNED -> R.string.templates_pinned_empty
        Shelf.RECENTS -> R.string.templates_recents_empty
        Shelf.SEARCH -> R.string.templates_search_empty
        Shelf.NONE -> R.string.templates_empty
    }

    /** The page's cards, their art rendered off Main; a picture's bytes are read here and only here. */
    private suspend fun bindCurrentPage() {
        val g = grid ?: return
        val range = GridMath.pageRange(pageIndex, g.cardsPerPage, items.size)
        val visible = range.map { items[it] }
        val chosen = selection()
        val dpi = activity.resources.displayMetrics.densityDpi.toFloat()
        val (art, ticked) = withContext(Dispatchers.IO) {
            val art = HashMap<String, Bitmap?>(visible.size)
            val ticked = HashSet<String>(4)
            val needsToken = chosen.token != null
            for (card in visible) {
                val image = (card as? TemplateCard.Static)
                    ?.takeIf { needsToken || !TemplateThumbnails.isCached(card, g.cardWidth) }
                    ?.let { runCatching { store.image(it.id) }.getOrNull() }
                art[card.id] = TemplateThumbnails.bitmap(card, g.cardWidth, pageWidthPx, pageHeightPx, dpi, image)
                if (isChosen(card, chosen, image)) ticked.add(card.id)
            }
            art to ticked
        }
        g.bind(items, pageIndex, art, ticked, pinnedIds)
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = activity.getString(R.string.page_indicator, pageIndex + 1, pageCount)
    }

    private fun isChosen(card: TemplateCard, chosen: Selection, image: ByteArray?): Boolean {
        if (chosen.cardId != null && chosen.cardId == card.id) return true
        val token = chosen.token ?: return false
        val cardToken = when (card) {
            is TemplateCard.Blank -> ""
            is TemplateCard.BuiltIn -> TemplateToken.of(card.kind)
            is TemplateCard.Static -> image?.let { TemplateToken.ofImage(it, card.fit) }
            else -> null
        }
        return cardToken == token
    }

    // ── Chrome ──────

    private fun renderChrome() = with(binding) {
        val inShelf = shelf != Shelf.NONE
        val fixed = inDefaults || inShelf
        btnSort.visibility = if ((inDefaults && !inShelf) || shelf == Shelf.SEARCH) View.GONE else View.VISIBLE
        btnNewFolder.visibility = if (fixed) View.GONE else View.VISIBLE
        btnImport.visibility = if (fixed) View.GONE else View.VISIBLE
        btnPinned.isSelected = shelf == Shelf.PINNED
        btnRecents.isSelected = shelf == Shelf.RECENTS
        btnSearch.isSelected = shelf == Shelf.SEARCH
        breadcrumbScroll.visibility = if (inShelf) View.GONE else View.VISIBLE
        shelfTitle.visibility = if (inShelf) View.VISIBLE else View.GONE
        if (inShelf) {
            btnBack.visibility = View.VISIBLE
            shelfTitle.text = when (shelf) {
                Shelf.PINNED -> activity.getString(R.string.shelf_title_pinned_templates)
                Shelf.RECENTS -> activity.getString(R.string.shelf_title_recent_templates)
                else -> activity.getString(R.string.shelf_title_search_templates, query)
            }
        } else {
            btnBack.visibility = if (folderId.isEmpty()) View.GONE else View.VISIBLE
            renderBreadcrumb()
        }
    }

    private fun renderBreadcrumb() {
        val ink = ContextCompat.getColor(activity, com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
        activity.lifecycleScope.launch {
            val ancestry = if (inDefaults || folderId.isEmpty()) emptyList() else withContext(Dispatchers.IO) { store.ancestry(folderId) }
            val container = binding.breadcrumbContainer
            container.removeAllViews()
            container.addView(crumb(activity.getString(R.string.templates_title), ink) { navigateTo("") })
            for (f in ancestry) {
                container.addView(separator(ink))
                container.addView(crumb(f.name, ink) { navigateTo(f.id) })
            }
            if (inDefaults) {
                container.addView(separator(ink))
                container.addView(crumb(activity.getString(R.string.template_default_folder), ink) {})
            }
            binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
        }
    }

    private fun crumb(label: String, color: Int, onClick: () -> Unit): TextView {
        val d = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = label
            textSize = 16f
            setTextColor(color)
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun separator(color: Int): TextView = TextView(activity).apply { text = " / "; textSize = 16f; setTextColor(color) }

    // ── Navigation ──────

    private fun navigateTo(id: String) {
        folderId = id
        shelf = Shelf.NONE
        pageIndex = 0
        reload()
    }

    private fun navigateUp() {
        if (folderId.isEmpty()) return
        if (inDefaults) { navigateTo(""); return }
        activity.lifecycleScope.launch {
            val ancestry = withContext(Dispatchers.IO) { store.ancestry(folderId) }
            navigateTo(if (ancestry.size >= 2) ancestry[ancestry.size - 2].id else "")
        }
    }

    private fun goToPage(index: Int) {
        val clamped = GridMath.clampPage(index, pageCount)
        if (clamped == pageIndex) return
        pageIndex = clamped
        activity.lifecycleScope.launch { bindCurrentPage() }
    }

    private fun toggleShelf(next: Shelf) {
        shelf = if (shelf == next) Shelf.NONE else next
        pageIndex = 0
        reload()
    }

    private fun closeShelf() {
        if (shelf == Shelf.NONE) return
        shelf = Shelf.NONE
        pageIndex = 0
        reload()
    }

    /** A dialog, then a flat shelf titled by the query. The last query comes back, selected. */
    private fun openSearchDialog() {
        NameDialog.show(activity, R.string.template_search_title, R.string.template_search_confirm, query, R.string.template_search_hint) { typed, dismiss ->
            if (!FuzzyRank.isRunnable(typed)) {
                Dialogs.problem(activity, R.string.template_search_empty_title, R.string.template_search_empty_body)
                return@show
            }
            query = typed.trim()
            dismiss()
            shelf = Shelf.SEARCH
            pageIndex = 0
            reload()
        }
    }

    // ── Cards ──────

    private fun onCardTap(item: TemplateCard) {
        when (item) {
            is TemplateCard.Folder -> navigateTo(item.row.id)
            is TemplateCard.Defaults -> navigateTo(TemplateIds.DEFAULT_FOLDER)
            is TemplateCard.Blank -> onPick(TemplatePick.Blank)
            is TemplateCard.BuiltIn -> onPick(TemplatePick.BuiltIn(item.kind))
            is TemplateCard.Static -> onPick(TemplatePick.Static(item.id))
        }
    }

    /** A built-in long-presses to Pin alone; a folder and a template get the full sheet; Blank
     *  and Default do not long-press at all. */
    private fun onCardLongPress(item: TemplateCard) {
        val sheet = ActionSheetDialog(activity).title(item.name)
        when (item) {
            is TemplateCard.BuiltIn -> sheet.addPinRow(item.id).show()
            is TemplateCard.Folder -> sheet
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_edit, activity.getString(R.string.action_rename)) { showRenameDialog(item.row) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_move_folder, activity.getString(R.string.action_move)) { showMovePicker(item.row) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_trash, activity.getString(R.string.action_delete)) { confirmDeleteFolder(item.row) }
                .show()
            is TemplateCard.Static -> sheet
                .addPinRow(item.id)
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_edit, activity.getString(R.string.action_rename)) { showRenameDialog(item.row) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_move_folder, activity.getString(R.string.action_move)) { showMovePicker(item.row) }
                .also { if (shelf == Shelf.NONE) it.addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_copy, activity.getString(R.string.action_duplicate)) { duplicate(item.row) } }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_aspect_ratio, activity.getString(R.string.action_fit)) { transfer.chooseFit(item.row) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_download, activity.getString(R.string.action_export)) { transfer.export(item.row) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_trash, activity.getString(R.string.action_delete)) { confirmDeleteTemplate(item.row) }
                .show()
            else -> Unit
        }
    }

    private fun ActionSheetDialog.addPinRow(cardId: String): ActionSheetDialog {
        val pinned = cardId in pinnedIds
        return addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_pinned, activity.getString(if (pinned) R.string.action_unpin else R.string.action_pin)) {
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) { if (pinned) store.unpin(cardId) else store.pin(cardId) }
                refresh()
            }
        }
    }

    // ── New folder / rename / duplicate / delete / move ──────

    private fun showNewFolderDialog() {
        val parentId = folderId
        var accepting = false
        NameDialog.show(activity, R.string.new_folder_title, R.string.new_folder_create, "", R.string.new_folder_hint) { name, dismiss ->
            if (accepting) return@show
            if (NameDialog.reject(activity, name, parentId)) return@show
            accepting = true
            activity.lifecycleScope.launch {
                try {
                    if (withContext(Dispatchers.IO) { store.nameTaken(parentId, true, name) }) {
                        Dialogs.problem(activity, R.string.name_problem_title, activity.getString(R.string.template_folder_duplicate_name, name))
                        return@launch
                    }
                    withContext(Dispatchers.IO) { store.createFolder(name, parentId) }
                    dismiss()
                    refresh()
                } finally {
                    accepting = false
                }
            }
        }
    }

    private fun showRenameDialog(row: TemplateRow) {
        var accepting = false
        NameDialog.show(activity, R.string.rename_title, R.string.action_rename, row.name) { name, dismiss ->
            if (accepting) return@show
            if (name == row.name) { dismiss(); return@show }
            if (NameDialog.reject(activity, name, row.parentId)) return@show
            accepting = true
            activity.lifecycleScope.launch {
                try {
                    if (withContext(Dispatchers.IO) { store.nameTaken(row.parentId, row.isFolder, name, row.id) }) {
                        val msg = if (row.isFolder) R.string.template_folder_duplicate_name else R.string.template_duplicate_name
                        Dialogs.problem(activity, R.string.name_problem_title, activity.getString(msg, name))
                        return@launch
                    }
                    withContext(Dispatchers.IO) { store.rename(row.id, row.isFolder, name) }
                    dismiss()
                    refresh()
                } finally {
                    accepting = false
                }
            }
        }
    }

    private fun duplicate(row: TemplateRow) {
        activity.lifecycleScope.launch {
            val made = withContext(Dispatchers.IO) {
                val taken = (store.templates(row.parentId) + store.folders(row.parentId)).map { it.name }.toSet()
                store.duplicate(row.id, TemplateNames.duplicateName(row.name, taken))
            }
            if (made == null) Dialogs.problem(activity, R.string.template_duplicate_gone_title, R.string.template_duplicate_gone_body)
            refresh()
        }
    }

    private fun confirmDeleteTemplate(row: TemplateRow) = confirm(R.string.delete_template_title, R.string.delete_template_body, row.name) {
        activity.lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.deleteTemplate(row.id) }
            prefs.forget(listOf(row.id))
            refresh()
        }
    }

    private fun confirmDeleteFolder(row: TemplateRow) = confirm(R.string.delete_template_folder_title, R.string.delete_template_folder_body, row.name) {
        activity.lifecycleScope.launch {
            val gone = withContext(Dispatchers.IO) { store.deleteFolderRecursive(row.id) }
            prefs.forget(gone)
            Slog.d(TAG) { "deleted a folder with ${gone.size} templates" }
            if (folderId == row.id) navigateTo(row.parentId) else refresh()
        }
    }

    private fun confirm(titleRes: Int, bodyRes: Int, name: String, onConfirm: () -> Unit) {
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(titleRes, name))
                .setMessage(bodyRes)
                .setPositiveButton(R.string.delete_confirm) { _, _ -> onConfirm() }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create(),
        ).show()
    }

    private fun showMovePicker(row: TemplateRow) =
        moveLauncher.launch(com.symmetricalpalmtree.soil.library.FolderPickerActivity.moveIntent(activity, com.symmetricalpalmtree.soil.library.FolderPickerActivity.Hierarchy.TEMPLATES, row.id, row.isFolder, row.name, row.parentId))

    // ── Sort ──────

    private fun showSortSheet() {
        val field = prefs.sortField
        val order = prefs.sortOrder
        fun tick(f: SortField, o: SortOrder) = if (field == f && order == o) com.symmetricalpalmtree.soil.paper.R.drawable.ic_check else null
        ActionSheetDialog(activity)
            .title(activity.getString(R.string.cd_sort))
            .addAction(tick(SortField.NAME, SortOrder.ASC), activity.getString(R.string.sort_name_asc)) { applySort(SortField.NAME, SortOrder.ASC) }
            .addAction(tick(SortField.NAME, SortOrder.DESC), activity.getString(R.string.sort_name_desc)) { applySort(SortField.NAME, SortOrder.DESC) }
            .addAction(tick(SortField.MODIFIED, SortOrder.ASC), activity.getString(R.string.sort_modified_asc)) { applySort(SortField.MODIFIED, SortOrder.ASC) }
            .addAction(tick(SortField.MODIFIED, SortOrder.DESC), activity.getString(R.string.sort_modified_desc)) { applySort(SortField.MODIFIED, SortOrder.DESC) }
            .show()
    }

    private fun applySort(field: SortField, order: SortOrder) {
        prefs.sortField = field
        prefs.sortOrder = order
        pageIndex = 0
        reload()
    }

    private companion object { const val TAG = "TemplateBrowser" }
}
