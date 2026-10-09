package com.symmetricalpalmtree.soil.library

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.Folder
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.databinding.ViewLibraryBrowserBinding
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.FuzzyRank
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.paper.core.GridMath
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.templates.TemplateNames
import com.symmetricalpalmtree.soil.templates.NameDialog
import com.symmetricalpalmtree.soil.templates.SortField
import com.symmetricalpalmtree.soil.templates.SortOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **The library browser**: the path, the paged card grid of folders and items, the three shelves
 * (Pinned, Recents, Search), and the long-press sheets. One component for every host; a host
 * supplies what a tap on an item means ([onOpen]), whether the sheets are offered, and a kind
 * to narrow to. The actions on the folder you stand in (New notebook, New folder, Search,
 * Recents, Pinned, Sort) are the host's buttons, wired to the entry points here.
 *
 * A shelf is a glance across the tree, not a place: nothing about it persists, and closing it
 * returns to the folder underneath. Construct in `onCreate`: launchers are registered here.
 */
class LibraryBrowser(
    private val activity: AppCompatActivity,
    private val binding: ViewLibraryBrowserBinding,
    private val onOpen: (Item) -> Unit,
    /** A page found by its tag: the item, opened at that page. Null leaves page cards out of the search. */
    private val onOpenPage: ((Item, String) -> Unit)? = null,
    /** Null narrows nothing; a kind lists only its items; several kinds, comma-separated, list theirs. */
    private val kind: String? = null,
    /** An item left out of every listing: the one the asking app has open. */
    private val excludeId: String? = null,
    private val sheets: Boolean = true,
    /** Where a new notebook is made and where a created folder lands: told the folder standing. */
    private val onFolderChanged: (String) -> Unit = {},
) {
    enum class Shelf { NONE, PINNED, RECENTS, SEARCH }

    /** Built per use, never held: the home screen holds a browser in every state of the library,
     *  and the index it reads closes and reopens under it (Forget, then Unlock; a passphrase
     *  change). A store held across that reads a closed database. */
    private val store: LibraryStore get() = LibraryStore()
    private val prefs = LibraryPrefs(activity)

    var folderId: String = ""
        private set
    var shelf: Shelf = Shelf.NONE
        private set
    private var query = ""
    private var pinnedIds: Set<String> = emptySet()
    private var pageIndex = 0
    private var pageCount = 1
    private var items: List<LibraryCard> = emptyList()
    private var grid: LibraryGrid? = null
    /** Covers decoded on IO, bounded by their bytes; the ids with no cover apart. Both are
     *  emptied on each listing: a cover changes without `updatedAt` moving. */
    private val coverCache = object : LruCache<String, Bitmap>(COVER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val noCover = HashSet<String>()
    /** The newest listing asked for: an older read that lands after it is dropped. */
    private var refreshGeneration = 0
    private var selectedId: String? = null

    private val moveLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) reload()
    }

    /** The tag screen, for a result: a changed tag may change what a search shelf shows. */
    private val tagsLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && shelf == Shelf.SEARCH) reload()
    }

    init {
        with(binding) {
            btnBack.setOnClickListener { onBackPressed() }
            btnFirst.setOnClickListener { goToPage(0) }
            btnPrev.setOnClickListener { goToPage(pageIndex - 1) }
            btnNext.setOnClickListener { goToPage(pageIndex + 1) }
            btnLast.setOnClickListener { goToPage(pageCount - 1) }
            gridContainer.onNext = { goToPage(pageIndex + 1) }
            gridContainer.onPrevious = { goToPage(pageIndex - 1) }
        }
        var measured = false
        binding.gridContainer.viewTreeObserver.addOnGlobalLayoutListener {
            if (measured) return@addOnGlobalLayoutListener
            val w = binding.gridContainer.width
            val h = binding.gridContainer.height
            if (w <= 0 || h <= 0) return@addOnGlobalLayoutListener
            measured = true
            grid = LibraryGrid(binding.gridContainer, ::onCardTap, if (sheets) ::onCardLongPress else null).also { it.measure(activity, w, h) }
            reload()
        }
    }

    // ── Host API ──────

    /** Start in [id], or at the root when it is gone. */
    fun startIn(id: String) {
        folderId = id
    }

    fun reload() { activity.lifecycleScope.launch { refresh() } }

    /** The item ticked in a picker; the host redraws the page. */
    fun select(id: String?) {
        selectedId = id
        activity.lifecycleScope.launch { bindCurrentPage() }
    }

    fun toggleShelf(next: Shelf) {
        shelf = if (shelf == next) Shelf.NONE else next
        pageIndex = 0
        onShelfChanged()
        reload()
    }

    /** A dialog asks for the query; the shelf wears it as its title. */
    fun openSearchDialog() {
        NameDialog.show(activity, R.string.library_search_title, R.string.template_search_confirm, query, R.string.library_search_hint) { typed, dismiss ->
            if (!FuzzyRank.isRunnable(typed)) {
                Dialogs.problem(activity, R.string.template_search_empty_title, R.string.template_search_empty_body)
                return@show
            }
            query = typed.trim()
            dismiss()
            shelf = Shelf.SEARCH
            pageIndex = 0
            onShelfChanged()
            reload()
        }
    }

    fun showSortSheet() {
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

    fun showNewFolderDialog() {
        val parentId = folderId
        var accepting = false
        NameDialog.show(activity, R.string.new_folder_title, R.string.new_folder_create, "", R.string.new_folder_hint) { name, dismiss ->
            if (accepting) return@show
            TemplateNames.validate(name)?.let { problem ->
                Dialogs.problem(activity, R.string.name_problem_title, NameDialog.problemMessage(activity, problem))
                return@show
            }
            accepting = true
            act {
                try {
                    if (withContext(Dispatchers.IO) { store.folderNameTaken(parentId, name) }) {
                        Dialogs.problem(activity, R.string.name_problem_title, activity.getString(R.string.folder_duplicate_name, name))
                        return@act
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

    /** Whether the shelves are up: what a host's chrome stands down for. */
    val inShelf: Boolean get() = shelf != Shelf.NONE

    /** Back peels one layer: out of a shelf, up a folder. False with nothing to peel. */
    fun onBackPressed(): Boolean {
        if (shelf != Shelf.NONE) { closeShelf(); return true }
        if (folderId.isEmpty()) return false
        navigateUp()
        return true
    }

    // ── Listing ──────

    /** Read the listing again. A read that fails keeps the last listing and says so; one
     *  overtaken by a newer read is dropped. Never throws but for cancellation. */
    private suspend fun refresh() {
        if (!com.symmetricalpalmtree.soil.data.index.SoilIndex.isReady()) return
        val generation = ++refreshGeneration
        val listed = try {
            withContext(Dispatchers.IO) {
                val s = store
                if (folderId.isNotEmpty() && s.folder(folderId) == null) folderId = ""
                pinnedIds = s.pinnedIds().toSet()
                val field = prefs.sortField
                val order = prefs.sortOrder
                when (shelf) {
                    Shelf.NONE -> LibraryListing.folderCards(s.folders(folderId), itemsIn(folderId), pinnedIds, field, order)
                    Shelf.PINNED -> LibraryListing.pinnedCards(pinnedIds.toList(), s.aliveItems(pinnedIds).filterValues { wanted(it) }, field, order, ::placeOf)
                    Shelf.RECENTS -> LibraryListing.recentCards(s.allItems().filter { wanted(it) }, pinnedIds, ::placeOf)
                    Shelf.SEARCH -> searchCards()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "the listing could not be read: ${e.javaClass.simpleName}")
            // A library locked mid-read is not a failure: the home screen shows its line instead.
            if (generation == refreshGeneration && com.symmetricalpalmtree.soil.data.index.SoilIndex.isReady() && !activity.isFinishing && !activity.isDestroyed) {
                Dialogs.problem(activity, R.string.library_read_failed_title, R.string.library_read_failed_body)
            }
            return
        }
        if (generation != refreshGeneration) return
        renderChrome()
        items = listed
        coverCache.evictAll()
        noCover.clear()
        binding.emptyState.setText(emptyTextRes())
        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        pageCount = GridMath.pageCount(items.size, grid?.cardsPerPage ?: 1)
        pageIndex = GridMath.clampPage(pageIndex, pageCount)
        bindCurrentPage()
    }

    private fun itemsIn(parentId: String): List<Item> = store.items(parentId).filter { wanted(it) }

    /**
     * The search shelf's read: names and tags together. The tags the query touches are matched
     * first, and only their assignments are fetched; page numbers come from the index's page
     * order. Pages are left out where the host opens no page ([onOpenPage] null). IO.
     */
    private fun searchCards(): List<LibraryCard> {
        val folders = store.allFolders()
        val items = store.allItems().filter { wanted(it) }
        val tagStore = com.symmetricalpalmtree.soil.data.index.TagStore()
        val matches = SearchMerge.matchTags(tagStore.tags(), query)
        val assignments = if (matches.ids.isEmpty()) emptyList() else tagStore.assignmentsOf(matches.ids)
        var shelf = SearchMerge.rank(folders, items, query, matches, assignments)
        if (onOpenPage == null) shelf = SearchMerge.Shelf(shelf.folders, shelf.items, emptyList())
        val numbers = HashMap<String, Map<String, Int>>()
        val index = com.symmetricalpalmtree.soil.data.index.IndexStore()
        return LibraryListing.searchCards(
            shelf, pinnedIds, ::placeOfFolder, ::placeOf,
            pageNumber = { itemId, pageId -> numbers.getOrPut(itemId) { index.pageNumbers(itemId) }[pageId] },
            withTag = { place, tag -> activity.getString(R.string.search_where_and_tag, place, tag) },
        )
    }

    private val kinds: Set<String>? = kind?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() }

    private fun wanted(item: Item): Boolean = (kinds == null || item.kind in kinds) && item.id != excludeId

    /** Where an item is, for a flat shelf's second line. */
    private fun placeOf(item: Item): String = placeOfFolderId(item.parentId)
    private fun placeOfFolder(folder: Folder): String = placeOfFolderId(folder.parentId)
    private fun placeOfFolderId(parentId: String): String =
        if (parentId.isEmpty()) activity.getString(R.string.library_root) else store.folder(parentId)?.name ?: activity.getString(R.string.library_root)

    private fun emptyTextRes(): Int = when (shelf) {
        Shelf.PINNED -> R.string.library_pinned_empty
        Shelf.RECENTS -> R.string.library_recents_empty
        Shelf.SEARCH -> R.string.library_search_empty
        Shelf.NONE -> if (kind != null) R.string.item_picker_none else R.string.library_empty
    }

    /** Covers for the visible slice only, read on IO, merged on Main. */
    private suspend fun bindCurrentPage() {
        val g = grid ?: return
        val range = GridMath.pageRange(pageIndex, g.cardsPerPage, items.size)
        val ids = range.mapNotNull { coverIdOf(items[it]) }.distinct()
        val missing = ids.filter { coverCache.get(it) == null && it !in noCover }
        val fetched = if (missing.isEmpty()) emptyMap() else withContext(Dispatchers.IO) {
            missing.associateWith { id -> runCatching { LibraryGrid.decodeCover(store.cover(id)) }.getOrNull() }
        }
        for ((id, bmp) in fetched) if (bmp != null) coverCache.put(id, bmp) else noCover.add(id)
        val covers = ids.associateWith { fetched[it] ?: coverCache.get(it) }
        g.bind(items, pageIndex, covers, selectedId)
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = activity.getString(R.string.page_indicator, pageIndex + 1, pageCount)
    }

    /** Whose cover a card shows: an item's own, a page's the item's. */
    private fun coverIdOf(card: LibraryCard): String? = when (card) {
        is LibraryCard.ItemCard -> card.id
        is LibraryCard.PageCard -> card.item.id
        is LibraryCard.FolderCard -> null
    }

    // ── Chrome ──────

    private fun renderChrome() = with(binding) {
        val inShelf = shelf != Shelf.NONE
        breadcrumbScroll.visibility = if (inShelf) View.GONE else View.VISIBLE
        shelfTitle.visibility = if (inShelf) View.VISIBLE else View.GONE
        btnBack.visibility = if (inShelf || folderId.isNotEmpty()) View.VISIBLE else View.GONE
        if (inShelf) {
            shelfTitle.text = when (shelf) {
                Shelf.PINNED -> activity.getString(R.string.shelf_title_pinned)
                Shelf.RECENTS -> activity.getString(R.string.shelf_title_recent)
                else -> activity.getString(R.string.shelf_title_search, query)
            }
        } else {
            renderBreadcrumb()
        }
    }

    private fun renderBreadcrumb() {
        val ink = ContextCompat.getColor(activity, com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
        activity.lifecycleScope.launch {
            val ancestry = if (folderId.isEmpty()) emptyList() else try {
                withContext(Dispatchers.IO) { store.ancestry(folderId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the path could not be read: ${e.javaClass.simpleName}")
                emptyList()
            }
            val container = binding.breadcrumbContainer
            container.removeAllViews()
            container.addView(crumb(activity.getString(R.string.library_root), ink, "", activity.getString(R.string.library_root)))
            for (f in ancestry) {
                container.addView(separator(ink))
                container.addView(crumb(f.name, ink, f.id, f.name))
            }
            binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
        }
    }

    /** A crumb navigates; held, it opens the folder's say (the root's only way in). */
    private fun crumb(label: String, color: Int, id: String, name: String): TextView {
        val d = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = label
            textSize = 16f
            setTextColor(color)
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            setOnClickListener { navigateTo(id) }
            if (sheets) setOnLongClickListener { showFolderSaySheet(id, name); true }
        }
    }

    private fun separator(color: Int): TextView = TextView(activity).apply { text = " / "; textSize = 16f; setTextColor(color) }

    // ── Navigation ──────

    fun navigateTo(id: String) {
        folderId = id
        val wasShelf = shelf != Shelf.NONE
        shelf = Shelf.NONE
        pageIndex = 0
        onFolderChanged(id)
        if (wasShelf) onShelfChanged()
        reload()
    }

    private fun navigateUp() {
        if (folderId.isEmpty()) return
        act {
            val ancestry = withContext(Dispatchers.IO) { store.ancestry(folderId) }
            navigateTo(if (ancestry.size >= 2) ancestry[ancestry.size - 2].id else "")
        }
    }

    private fun closeShelf() {
        shelf = Shelf.NONE
        pageIndex = 0
        onShelfChanged()
        reload()
    }

    private fun goToPage(index: Int) {
        val clamped = GridMath.clampPage(index, pageCount)
        if (clamped == pageIndex) return
        pageIndex = clamped
        activity.lifecycleScope.launch { bindCurrentPage() }
    }

    private fun applySort(field: SortField, order: SortOrder) {
        prefs.sortField = field
        prefs.sortOrder = order
        pageIndex = 0
        reload()
    }

    // ── Cards ──────

    private fun onCardTap(card: LibraryCard) {
        when (card) {
            is LibraryCard.FolderCard -> navigateTo(card.id)
            is LibraryCard.ItemCard -> onOpen(card.item)
            is LibraryCard.PageCard -> onOpenPage?.invoke(card.item, card.pageId)
        }
    }

    private fun onCardLongPress(card: LibraryCard) {
        val sheet = ActionSheetDialog(activity).title(card.name)
        when (card) {
            is LibraryCard.FolderCard -> sheet
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_edit, activity.getString(R.string.action_rename)) { showRenameFolder(card.folder) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_move_folder, activity.getString(R.string.action_move)) {
                    moveLauncher.launch(FolderPickerActivity.moveIntent(activity, FolderPickerActivity.Hierarchy.LIBRARY, card.id, true, card.name, card.folder.parentId))
                }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_cursor_text, activity.getString(R.string.scheme_action)) { SchemeBuilderDialog.open(activity, card.id, card.name) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_template, activity.getString(R.string.default_template_action)) { onDefaultTemplate?.invoke(card.id, card.name) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_trash, activity.getString(R.string.action_delete)) { confirmDeleteFolder(card.folder) }
                .show()
            is LibraryCard.ItemCard -> sheet
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_pinned, activity.getString(if (card.pinned) R.string.action_unpin else R.string.action_pin)) { togglePin(card.id, card.pinned) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_edit, activity.getString(R.string.action_rename)) { showRenameItem(card.item) }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_move_folder, activity.getString(R.string.action_move)) {
                    moveLauncher.launch(FolderPickerActivity.moveIntent(activity, FolderPickerActivity.Hierarchy.LIBRARY, card.id, false, card.name, card.item.parentId))
                }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_tag, activity.getString(R.string.action_tags)) {
                    tagsLauncher.launch(com.symmetricalpalmtree.soil.tags.TagsActivity.intent(activity, card.id, null, Seam.TAG_MODE_BROWSE))
                }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_file_export, activity.getString(R.string.action_export)) {
                    activity.startActivity(com.symmetricalpalmtree.soil.export.ExportActivity.intent(activity, card.id))
                }
                .addAction(R.drawable.ic_archive, activity.getString(if (com.symmetricalpalmtree.soil.backup.BackupPredicates.isExcluded(card.item.flags)) R.string.action_include_backup else R.string.action_exclude_backup)) {
                    toggleExcluded(card.item.id, !com.symmetricalpalmtree.soil.backup.BackupPredicates.isExcluded(card.item.flags))
                }
                .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_trash, activity.getString(R.string.action_delete)) { confirmDeleteItem(card.item) }
                .show()
            is LibraryCard.PageCard -> Unit
        }
    }

    /** The exclude bit never bumps `updatedAt`; the cards are read again so the sheet's label follows. */
    private fun toggleExcluded(itemId: String, excluded: Boolean) {
        act {
            withContext(Dispatchers.IO) { com.symmetricalpalmtree.soil.data.index.IndexStore().setExcludedFromBackup(itemId, excluded) }
            refresh()
        }
    }

    /** The host's door to the template picker for a folder's default paper. */
    var onDefaultTemplate: ((folderId: String, folderName: String) -> Unit)? = null

    /** Told whenever a shelf opens or closes: the host's own buttons stand down on a shelf. */
    var onShelfChanged: () -> Unit = {}

    /** The root crumb's and any crumb's long press: the folder's say, as a sheet of its two rows. */
    private fun showFolderSaySheet(id: String, name: String) {
        ActionSheetDialog(activity).title(name)
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_cursor_text, activity.getString(R.string.scheme_action)) { SchemeBuilderDialog.open(activity, id, name) }
            .addAction(com.symmetricalpalmtree.soil.paper.R.drawable.ic_template, activity.getString(R.string.default_template_action)) { onDefaultTemplate?.invoke(id, name) }
            .show()
    }

    private fun togglePin(id: String, pinned: Boolean) {
        act {
            withContext(Dispatchers.IO) { if (pinned) store.unpin(id) else store.pin(id) }
            refresh()
        }
    }

    private fun showRenameFolder(folder: Folder) {
        var accepting = false
        NameDialog.show(activity, R.string.rename_title, R.string.action_rename, folder.name) { name, dismiss ->
            if (accepting) return@show
            if (name == folder.name) { dismiss(); return@show }
            TemplateNames.validate(name)?.let { problem ->
                Dialogs.problem(activity, R.string.name_problem_title, NameDialog.problemMessage(activity, problem))
                return@show
            }
            accepting = true
            act {
                try {
                    if (withContext(Dispatchers.IO) { store.folderNameTaken(folder.parentId, name, folder.id) }) {
                        Dialogs.problem(activity, R.string.name_problem_title, activity.getString(R.string.folder_duplicate_name, name))
                        return@act
                    }
                    withContext(Dispatchers.IO) { store.renameFolder(folder.id, name) }
                    dismiss()
                    refresh()
                } finally {
                    accepting = false
                }
            }
        }
    }

    /** An item's name is its own words: through the seam's rule, into the file too. */
    private fun showRenameItem(item: Item) {
        var accepting = false
        NameDialog.show(activity, R.string.rename_title, R.string.action_rename, item.name) { name, dismiss ->
            if (accepting) return@show
            if (name == item.name) { dismiss(); return@show }
            val clean = runCatching { com.symmetricalpalmtree.soil.data.item.ItemNames.clean(name) }.getOrNull()
            if (clean == null) {
                Dialogs.problem(activity, R.string.name_problem_title, R.string.name_empty)
                return@show
            }
            accepting = true
            act {
                try {
                    withContext(Dispatchers.IO) {
                        com.symmetricalpalmtree.soil.data.index.IndexStore().rename(item.id, clean, System.currentTimeMillis())
                        ItemSessions.rename(item.id, clean)
                    }
                    ItemSessions.changed()
                    dismiss()
                    refresh()
                } finally {
                    accepting = false
                }
            }
        }
    }

    private fun confirmDeleteItem(item: Item) {
        if (ItemSessions.isHeld(item.id)) {
            Dialogs.problem(activity, R.string.delete_item_open_title, activity.getString(R.string.delete_item_open_body, item.name))
            return
        }
        confirm(R.string.delete_item_title, R.string.delete_item_body, item.name) {
            act {
                // Asked again at the moment of deleting: the app may have opened it while the
                // question was up.
                val deleted = withContext(Dispatchers.IO) {
                    if (ItemSessions.isHeld(item.id)) return@withContext false
                    if (store.deleteItem(item.id)) LibraryFiles.deleteItemFile(activity, item.id)
                    true
                }
                if (!deleted) {
                    Dialogs.problem(activity, R.string.delete_item_open_title, activity.getString(R.string.delete_item_open_body, item.name))
                    return@act
                }
                ItemSessions.changed()
                refresh()
            }
        }
    }

    /** A folder holding an item open in its app is not deleted: the item's file would go from
     *  under the app. Asked before the question and again at the moment of deleting. */
    private fun confirmDeleteFolder(folder: Folder) {
        act {
            if (withContext(Dispatchers.IO) { holdsOpenItem(folder.id) }) {
                Dialogs.problem(activity, R.string.delete_item_open_title, activity.getString(R.string.delete_folder_open_body, folder.name))
                return@act
            }
            confirm(R.string.delete_folder_title, R.string.delete_folder_body, folder.name) { deleteFolder(folder) }
        }
    }

    private fun deleteFolder(folder: Folder) {
        act {
            val gone = withContext(Dispatchers.IO) {
                if (holdsOpenItem(folder.id)) return@withContext null
                val ids = store.deleteFolderRecursive(folder.id)
                ids.forEach { LibraryFiles.deleteItemFile(activity, it) }
                ids
            }
            if (gone == null) {
                Dialogs.problem(activity, R.string.delete_item_open_title, activity.getString(R.string.delete_folder_open_body, folder.name))
                return@act
            }
            Slog.d(TAG) { "deleted a folder with ${gone.size} items" }
            ItemSessions.changed()
            if (folderId == folder.id) navigateTo(folder.parentId) else refresh()
        }
    }

    /** Whether any item under [rootId], at any depth, is open in its app. IO. */
    private fun holdsOpenItem(rootId: String): Boolean {
        val s = store
        val stack = ArrayDeque<String>().apply { add(rootId) }
        val seen = HashSet<String>()
        while (stack.isNotEmpty()) {
            val fid = stack.removeLast()
            if (!seen.add(fid)) continue
            if (s.items(fid).any { ItemSessions.isHeld(it.id) }) return true
            s.folders(fid).forEach { stack.add(it.id) }
        }
        return false
    }

    /** A change on Main: a store call that throws says so in a problem dialog, never a crash. */
    private fun act(block: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "a library change failed: ${e.javaClass.simpleName}")
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Dialogs.problem(activity, R.string.library_change_failed_title, R.string.library_change_failed_body)
                }
            }
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

    fun saveState(outState: Bundle) {
        outState.putString(KEY_FOLDER, folderId)
    }

    fun restoreState(saved: Bundle?) {
        saved?.getString(KEY_FOLDER)?.let { folderId = it }
    }

    private companion object {
        const val TAG = "LibraryBrowser"
        const val KEY_FOLDER = "libraryBrowser.folder"
        /** A few pages of covers, decoded. */
        const val COVER_CACHE_BYTES = 24 * 1024 * 1024
    }
}
