package com.symmetricalpalmtree.soil.tags

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.index.TagRecord
import com.symmetricalpalmtree.soil.data.index.TagStore
import com.symmetricalpalmtree.soil.databinding.ActivityTagsBinding
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck
import com.symmetricalpalmtree.soil.seam.TagRules
import com.symmetricalpalmtree.soil.templates.TextStaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * **The tag screen**: one item's tags, or one page's, with every tag of the library below to
 * take from. Started for a result by Soil's own library and by the Sprout apps alike
 * ([Seam.ACTION_TAGS]); the caller is checked first, before anything is inflated.
 *
 * Three surfaces, three gestures: the target's own tags (tap removes from this target; the tag
 * stays in the library), the add field (type, then ⊕ or Done: normalised, created if new,
 * attached), and the list below (tap toggles membership; long-press deletes the tag everywhere,
 * behind a confirm that names its reach).
 *
 * **Manage** opens on an overview of the item and every page the index knows, each over the tags
 * it carries; tapping a row makes it the target and the screen becomes what it is in every other
 * mode. The back arrow returns to the overview, and only from the overview does it leave.
 *
 * **Every edit is written before it is shown**: the change goes to the store, the index is read
 * again, and only then does the screen redraw. The keyboard is asked for with the explicit flag
 * and never hidden while the field has focus (the Ratta rule).
 */
class TagsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTagsBinding
    private lateinit var itemId: String
    private var mode = Seam.TAG_MODE_BROWSE
    private var index: TagIndex = TagIndex.EMPTY
    private var page = 0
    private var rowHeightPx = 1
    private var targetRowHeightPx = 1

    private class Target(val itemId: String, val pageId: String?, val header: String, val rowLabel: String)

    private lateinit var target: Target
    private var manageTargets: List<Target> = emptyList()
    private var overview = false
    private var overviewPage = 0
    private var pendingAddFocus = false
    private var busy = false
    private var bandHeightPx = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (runCatching { SeamCallerCheck.enforceCaller(this, callingPackage) }.isFailure || !SoilIndex.isReady()) { finish(); return }
        val asked = intent.getStringExtra(Seam.EXTRA_ITEM_ID)
        if (asked == null || !TagRules.isId(asked)) { finish(); return }
        itemId = asked
        val pageId = intent.getStringExtra(Seam.EXTRA_PAGE_ID)?.takeIf { TagRules.isId(it) }
        mode = intent.getIntExtra(Seam.EXTRA_TAG_MODE, Seam.TAG_MODE_BROWSE)
        val prefill = TextStaging.take(intent.getStringExtra(Seam.EXTRA_STAGED_ID))?.let { TagRules.prefill(it) }

        binding = ActivityTagsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        rowHeightPx = TagRowView.rowHeightPx(this)
        targetRowHeightPx = TagRowView.targetRowHeightPx(this)

        binding.btnBack.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        binding.btnAdd.setOnClickListener { addTyped() }
        binding.btnPrevPage.setOnClickListener { turnPage(-1) }
        binding.btnNextPage.setOnClickListener { turnPage(1) }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { addTyped(); true } else false
        }
        binding.input.doAfterTextChanged {
            // The filter runs over the index in memory, never a store call per keystroke. Silent in
            // the overview, where the field is not on screen and a clear must not reset its pager.
            if (overview) return@doAfterTextChanged
            page = 0
            renderList()
        }
        prefill?.let { binding.input.setText(it) }
        pendingAddFocus = mode == Seam.TAG_MODE_ADD
        binding.listBand.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop || bandHeightPx < 0) binding.listBand.post { renderList() }
        }
        load(pageId)
    }

    // ── Targets and modes ──────

    /** The item's name and its pages are the index's: read once, on IO, before anything shows. */
    private fun load(pageId: String?) {
        lifecycleScope.launch {
            val built = withContext(Dispatchers.IO) {
                runCatching {
                    val item = LibraryStore().item(itemId) ?: return@runCatching null
                    val pages = IndexStore().pagesOf(itemId)
                    val numbers = pages.withIndex().associate { (i, id) -> id to i + 1 }
                    Triple(item.name, pages, numbers)
                }.getOrNull()
            }
            if (isFinishing || isDestroyed) return@launch
            if (built == null) { failAndClose(R.string.tags_unavailable); return@launch }
            val (name, pages, numbers) = built
            binding.title.text = if (pageId == null) name else getString(R.string.tags_page_title, name, pageLabel(numbers[pageId]))
            buildTargets(pageId, pages, numbers)
            reload()
        }
    }

    private fun pageLabel(number: Int?): String = if (number == null) getString(R.string.tags_page_unnumbered) else getString(R.string.tags_page_label, number)

    private fun buildTargets(pageId: String?, pages: List<String>, numbers: Map<String, Int>) {
        val item = Target(
            itemId = itemId, pageId = pageId,
            header = getString(if (pageId == null) R.string.tags_on_item else R.string.tags_on_page),
            rowLabel = getString(R.string.tags_manage_item_row),
        )
        if (mode != Seam.TAG_MODE_MANAGE) {
            target = item
            overview = false
            return
        }
        manageTargets = TagManage.targets(itemId, item.rowLabel, pages, pages.map { pageLabel(numbers[it]) }).map { row ->
            if (row.pageId == null) item
            else Target(itemId = row.itemId, pageId = row.pageId, header = getString(R.string.tags_on_named, row.label), rowLabel = row.label)
        }
        target = item
        overview = true
    }

    private fun canReturnToOverview(): Boolean = mode == Seam.TAG_MODE_MANAGE && !overview && ::target.isInitialized

    private fun leave() {
        if (canReturnToOverview()) { showOverview(); return }
        finish()
    }

    private fun showOverview() {
        overview = true
        binding.input.setText("")
        page = overviewPage
        render()
    }

    private fun openTarget(t: Target) {
        if (busy) return
        overviewPage = page
        target = t
        overview = false
        page = 0
        binding.input.setText("")
        render()
    }

    /** From the window's focus, not `onResume`: a resumed Activity has no window focus yet and a
     *  `showSoftInput` against it is dropped. Flag 0, never implicit (a hardware keyboard skips an
     *  implicit show). Once per showing. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !pendingAddFocus) return
        pendingAddFocus = false
        binding.input.requestFocus()
        binding.input.setSelection(binding.input.text?.length ?: 0)
        getSystemService(InputMethodManager::class.java)?.showSoftInput(binding.input, 0)
    }

    // ── Loading ──────

    private fun reload() {
        lifecycleScope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { readIndex() }.getOrNull() }
            if (isFinishing || isDestroyed) return@launch
            if (read == null) { failAndClose(R.string.tags_unavailable); return@launch }
            index = read
            Slog.d(TAG) { "loaded: ${index.tags.size} tags, ${index.assignments.size} assignments" }
            render()
        }
    }

    /** Two reads: the library's tags, and the assignments of this item. IO. */
    private fun readIndex(): TagIndex {
        val store = TagStore()
        return TagIndex(store.tags(), store.assignmentsOfItem(itemId))
    }

    private fun failAndClose(messageRes: Int) {
        Dialogs.confirm(this, getString(R.string.tags_problem_title), getString(messageRes)) { finish() }
    }

    // ── Rendering ──────

    private fun render() {
        if (!::target.isInitialized) return
        binding.targetSection.visibility = if (overview) View.GONE else View.VISIBLE
        if (overview) renderOverview() else { renderTarget(); renderList() }
    }

    private fun renderOverview() {
        binding.listLabel.setText(R.string.tags_manage_targets)
        binding.listEmpty.visibility = View.GONE
        bandHeightPx = binding.listBand.height
        val perPage = TagPaging.rowsPerPage(bandHeightPx, targetRowHeightPx)
        page = TagPaging.clampPage(page, manageTargets.size, perPage)
        val pageCount = TagPaging.pageCount(manageTargets.size, perPage)
        val separator = getString(R.string.tags_manage_separator)
        binding.listBand.removeAllViews()
        for (t in TagPaging.slice(manageTargets, page, perPage)) {
            val mine = index.tagsOf(t.itemId, t.pageId)
            binding.listBand.addView(
                TagRowView.buildTarget(this, t.rowLabel, TagManage.summary(mine.map { it.display }, getString(R.string.tags_manage_no_tags), separator)) { openTarget(t) },
            )
        }
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageIndicator.text = getString(R.string.page_indicator, page + 1, pageCount)
    }

    private fun renderTarget() {
        binding.targetLabel.text = target.header
        val mine = index.tagsOf(target.itemId, target.pageId)
        binding.targetTags.removeAllViews()
        for (tag in mine) {
            binding.targetTags.addView(
                TagRowView.build(this, tag.display, com.symmetricalpalmtree.soil.paper.R.drawable.ic_x, getString(R.string.cd_tags_remove), onClick = { removeFromTarget(tag) }),
            )
        }
        binding.targetEmpty.visibility = if (mine.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderList() {
        if (!::target.isInitialized) return
        if (overview) { renderOverview(); return }
        val query = binding.input.text?.toString().orEmpty()
        val filtering = TagRules.display(query).isNotEmpty()
        val rows = if (filtering) index.suggest(query) else index.sortedTags()
        binding.listLabel.setText(if (filtering) R.string.tags_matching else R.string.tags_all)
        bandHeightPx = binding.listBand.height
        val perPage = TagPaging.rowsPerPage(bandHeightPx, rowHeightPx)
        page = TagPaging.clampPage(page, rows.size, perPage)
        val pageCount = TagPaging.pageCount(rows.size, perPage)
        binding.listBand.removeAllViews()
        for (tag in TagPaging.slice(rows, page, perPage)) {
            val attached = index.isAssigned(tag.id, target.itemId, target.pageId)
            binding.listBand.addView(
                TagRowView.build(
                    this, tag.display,
                    if (attached) com.symmetricalpalmtree.soil.paper.R.drawable.ic_check else com.symmetricalpalmtree.soil.paper.R.drawable.ic_plus,
                    null,
                    onClick = { if (attached) removeFromTarget(tag) else attach(tag.display) },
                    onLongClick = { confirmDelete(tag) },
                ),
            )
        }
        if (rows.isEmpty()) {
            binding.listEmpty.setText(if (filtering) R.string.tags_no_match else R.string.tags_all_empty)
            binding.listEmpty.visibility = View.VISIBLE
        } else {
            binding.listEmpty.visibility = View.GONE
        }
        binding.pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageIndicator.text = getString(R.string.page_indicator, page + 1, pageCount)
    }

    private fun turnPage(delta: Int) {
        val count: Int
        val rowHeight: Int
        if (overview) {
            count = manageTargets.size
            rowHeight = targetRowHeightPx
        } else {
            val query = binding.input.text?.toString().orEmpty()
            count = (if (TagRules.display(query).isNotEmpty()) index.suggest(query) else index.sortedTags()).size
            rowHeight = rowHeightPx
        }
        val perPage = TagPaging.rowsPerPage(binding.listBand.height, rowHeight)
        val next = TagPaging.clampPage(page + delta, count, perPage)
        if (next == page) return
        page = next
        renderList()
    }

    // ── Edits ──────

    private fun addTyped() {
        val text = binding.input.text?.toString().orEmpty()
        if (!TagRules.isValid(text)) {
            Dialogs.problem(this, R.string.tags_problem_title, R.string.tags_invalid)
            return
        }
        attach(text) {
            // Cleared only once the tag has landed; focus and the keyboard stay as they were.
            binding.input.setText("")
            page = 0
        }
    }

    private fun attach(text: String, onDone: () -> Unit = {}) {
        val display = AtomicReference(TagRules.display(text))
        edit(
            work = { store ->
                val result = store.assign(text, target.itemId, target.pageId)
                display.set(result.display)
                result.changed
            },
            onDone = { onDone(); toast(getString(R.string.tags_added_toast, display.get())) },
        )
    }

    private fun removeFromTarget(tag: TagRecord) {
        edit(work = { store -> store.unassign(tag.id, target.itemId, target.pageId) }, onDone = { toast(getString(R.string.tags_removed_toast, tag.display)) })
    }

    /** The reach is a read: this screen holds one item's assignments, and a tag's reach is the library's. */
    private fun confirmDelete(tag: TagRecord) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val usage = withContext(Dispatchers.IO) { runCatching { TagStore().usageOf(tag.id) }.getOrNull() }
            busy = false
            if (isFinishing || isDestroyed) return@launch
            if (usage == null) { Dialogs.problem(this@TagsActivity, R.string.tags_problem_title, R.string.tags_unavailable); return@launch }
            val body = if (usage.total == 0) getString(R.string.tags_delete_unused) else getString(R.string.tags_delete_body, reach(usage))
            Dialogs.style(
                AlertDialog.Builder(this@TagsActivity)
                    .setTitle(getString(R.string.tags_delete_title, tag.display))
                    .setMessage(body)
                    .setPositiveButton(R.string.tags_delete_confirm) { _, _ -> deleteTag(tag) }
                    .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                    .create(),
            ).show()
        }
    }

    /** "2 notebooks and 1 page": whichever halves are non-zero, in that order. */
    private fun reach(usage: TagStore.Usage): String {
        val items = if (usage.items == 0) null else resources.getQuantityString(R.plurals.tags_delete_items, usage.items, usage.items)
        val pages = if (usage.pages == 0) null else resources.getQuantityString(R.plurals.tags_delete_pages, usage.pages, usage.pages)
        return when {
            items != null && pages != null -> getString(R.string.tags_delete_and, items, pages)
            items != null -> items
            else -> pages.orEmpty()
        }
    }

    private fun deleteTag(tag: TagRecord) {
        edit(work = { store -> store.deleteTag(tag.id) }, onDone = { toast(getString(R.string.tags_deleted_toast, tag.display)) })
    }

    /** One edit, written before it is shown: the work on IO, then the index read again. */
    private fun edit(work: (TagStore) -> Boolean, onDone: () -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { applyEdit(work) }
            busy = false
            if (isFinishing || isDestroyed) return@launch
            when (outcome) {
                is Applied.Ok -> {
                    index = outcome.index
                    if (outcome.changed) setResult(Activity.RESULT_OK)
                    render()
                    onDone()
                }
                is Applied.Failed -> Dialogs.problem(this@TagsActivity, R.string.tags_problem_title, outcome.messageRes)
            }
        }
    }

    private sealed class Applied {
        class Ok(val index: TagIndex, val changed: Boolean) : Applied()
        class Failed(val messageRes: Int) : Applied()
    }

    private fun applyEdit(work: (TagStore) -> Boolean): Applied {
        val store = TagStore()
        val changed = try {
            work(store)
        } catch (e: IllegalStateException) {
            return Applied.Failed(if (e.message == TagRules.TAGS_FULL) R.string.tags_full else R.string.tags_save_failed)
        } catch (e: IllegalArgumentException) {
            return Applied.Failed(R.string.tags_invalid)
        } catch (e: Exception) {
            Slog.d(TAG) { "edit failed: ${e.javaClass.simpleName}" }
            return Applied.Failed(R.string.tags_save_failed)
        }
        return try {
            Applied.Ok(readIndex(), changed)
        } catch (e: Exception) {
            Applied.Failed(R.string.tags_unavailable)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAG = "TagsActivity"

        /** The screen for one target, started for a result. */
        fun intent(context: Context, itemId: String, pageId: String?, mode: Int): Intent =
            Intent(context, TagsActivity::class.java)
                .setAction(Seam.ACTION_TAGS)
                .putExtra(Seam.EXTRA_ITEM_ID, itemId)
                .putExtra(Seam.EXTRA_PAGE_ID, pageId)
                .putExtra(Seam.EXTRA_TAG_MODE, mode)
    }
}
