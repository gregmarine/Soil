package com.symmetricalpalmtree.soil.library

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.index.TemplateStore
import com.symmetricalpalmtree.soil.databinding.ActivityFolderPickerBinding
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.templates.TemplateIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A folder, chosen: of the library or of the paper library ([Hierarchy]), for a **move** (the row
 * goes here, with the collision and the into-itself refusals) or for a **save** (the folder's id
 * is the answer). A paged list, never scrolled. The templates' Default folder is never offered:
 * nothing of the person's may land in it.
 */
class FolderPickerActivity : AppCompatActivity() {

    enum class Hierarchy { LIBRARY, TEMPLATES }

    /** One folder as either store lists it. */
    private class Row(val id: String, val parentId: String, val name: String)

    private lateinit var binding: ActivityFolderPickerBinding
    private lateinit var hierarchy: Hierarchy
    private var folderId: String = ""
    private var folders: List<Row> = emptyList()
    private var page = 0
    private var busy = false

    private val moveId: String? get() = intent.getStringExtra(EXTRA_MOVE_ID)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        if (!SoilIndex.isReady()) { finish(); return }
        hierarchy = Hierarchy.valueOf(intent.getStringExtra(EXTRA_HIERARCHY) ?: Hierarchy.LIBRARY.name)
        binding = ActivityFolderPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        folderId = intent.getStringExtra(EXTRA_START_FOLDER).orEmpty()
        binding.btnHere.setText(if (moveId != null) R.string.move_here else R.string.template_save_here)
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnHere.setOnClickListener { chooseHere() }
        binding.btnUp.setOnClickListener { goUp() }
        binding.btnPrev.setOnClickListener { turn(page - 1) }
        binding.btnNext.setOnClickListener { turn(page + 1) }
        binding.root.post { refresh() }
    }

    // ── The two hierarchies, read the same way ──────

    private fun foldersIn(parentId: String): List<Row> = when (hierarchy) {
        Hierarchy.LIBRARY -> LibraryStore().folders(parentId).map { Row(it.id, it.parentId, it.name) }
        Hierarchy.TEMPLATES -> TemplateStore().folders(parentId).map { Row(it.id, it.parentId, it.name) }
    }

    private fun ancestryOf(id: String): List<Row> = when (hierarchy) {
        Hierarchy.LIBRARY -> LibraryStore().ancestry(id).map { Row(it.id, it.parentId, it.name) }
        Hierarchy.TEMPLATES -> TemplateStore().ancestry(id).map { Row(it.id, it.parentId, it.name) }
    }

    private fun rootLabel(): String = getString(if (hierarchy == Hierarchy.LIBRARY) R.string.library_root else R.string.templates_title)

    // ── Listing ──────

    private fun refresh() {
        lifecycleScope.launch {
            val (list, ancestry) = withContext(Dispatchers.IO) {
                foldersIn(folderId).filter { it.id != moveId } to (if (folderId.isEmpty()) emptyList() else ancestryOf(folderId))
            }
            folders = list
            renderBreadcrumb(ancestry)
            binding.btnUp.visibility = if (folderId.isEmpty()) View.GONE else View.VISIBLE
            page = 0
            render()
        }
    }

    private fun renderBreadcrumb(ancestry: List<Row>) {
        val ink = ContextCompat.getColor(this, com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
        val d = resources.displayMetrics.density
        fun crumb(label: String, onClick: () -> Unit) = AppCompatTextView(this).apply {
            text = label; textSize = 16f; setTextColor(ink)
            setPadding((6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt())
            setOnClickListener { onClick() }
        }
        val c = binding.breadcrumbContainer
        c.removeAllViews()
        c.addView(crumb(getString(if (moveId != null) R.string.library_move_title else R.string.template_save_to_title)) {})
        c.addView(crumb(rootLabel()) { navigateTo("") })
        for (f in ancestry) {
            c.addView(AppCompatTextView(this).apply { text = " / "; textSize = 16f; setTextColor(ink) })
            c.addView(crumb(f.name) { navigateTo(f.id) })
        }
        binding.breadcrumbScroll.post { binding.breadcrumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun render() {
        val ink = ContextCompat.getColor(this, com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
        val d = resources.displayMetrics.density
        val rowH = (64 * d).toInt()
        val perPage = (binding.list.height / rowH).coerceAtLeast(1)
        val pages = if (folders.isEmpty()) 1 else (folders.size + perPage - 1) / perPage
        page = page.coerceIn(0, pages - 1)
        binding.list.removeAllViews()
        binding.emptyState.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        for (f in folders.drop(page * perPage).take(perPage)) {
            binding.list.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                    setBackgroundResource(com.symmetricalpalmtree.soil.paper.R.drawable.bg_toolbar_button)
                    isClickable = true
                    setOnClickListener { navigateTo(f.id) }
                    addView(AppCompatImageView(context).apply { setImageResource(com.symmetricalpalmtree.soil.paper.R.drawable.ic_folder) }, LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()).apply { marginEnd = (16 * d).toInt() })
                    addView(AppCompatTextView(context).apply { text = f.name; textSize = 18f; setTextColor(ink); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH),
            )
        }
        binding.pager.visibility = if (pages > 1) View.VISIBLE else View.INVISIBLE
        binding.pageLabel.text = getString(R.string.page_indicator, page + 1, pages)
    }

    private fun turn(to: Int) { page = to; render() }
    private fun navigateTo(id: String) { folderId = id; refresh() }

    private fun goUp() {
        lifecycleScope.launch {
            val ancestry = withContext(Dispatchers.IO) { ancestryOf(folderId) }
            navigateTo(if (ancestry.size >= 2) ancestry[ancestry.size - 2].id else "")
        }
    }

    // ── The verb ──────

    private fun chooseHere() {
        if (busy) return
        val id = moveId
        if (id == null) {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PICKED_FOLDER, folderId))
            finish()
            return
        }
        val isFolder = intent.getBooleanExtra(EXTRA_MOVE_IS_FOLDER, false)
        val name = intent.getStringExtra(EXTRA_MOVE_NAME).orEmpty()
        busy = true
        lifecycleScope.launch {
            try {
                val problem = withContext(Dispatchers.IO) { moveProblem(id, isFolder, name) }
                if (problem != null) {
                    Dialogs.problem(this@FolderPickerActivity, R.string.name_problem_title, getString(problem, name))
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    when (hierarchy) {
                        Hierarchy.LIBRARY -> if (isFolder) LibraryStore().moveFolder(id, folderId) else LibraryStore().moveItem(id, folderId)
                        Hierarchy.TEMPLATES -> TemplateStore().move(id, isFolder, folderId)
                    }
                }
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_PICKED_FOLDER, folderId))
                finish()
            } finally {
                busy = false
            }
        }
    }

    /** Why the move cannot land here, as a string resource taking the name; null when it can. */
    private fun moveProblem(id: String, isFolder: Boolean, name: String): Int? = when (hierarchy) {
        Hierarchy.LIBRARY -> when {
            isFolder && LibraryStore().isSelfOrDescendant(folderId, id) -> R.string.move_into_itself
            isFolder && LibraryStore().folderNameTaken(folderId, name, id) -> R.string.move_collision_item_folder
            else -> null
        }
        Hierarchy.TEMPLATES -> when {
            isFolder && (folderId == id || TemplateStore().ancestry(folderId).any { it.id == id }) -> R.string.move_into_itself
            TemplateIds.isReservedName(folderId, name) -> R.string.template_name_reserved
            TemplateStore().nameTaken(folderId, isFolder, name, id) -> if (isFolder) R.string.move_collision_folder else R.string.move_collision_template
            else -> null
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (folderId.isNotEmpty()) goUp() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        const val EXTRA_PICKED_FOLDER = "pickedFolder"
        private const val EXTRA_HIERARCHY = "hierarchy"
        private const val EXTRA_START_FOLDER = "startFolder"
        private const val EXTRA_MOVE_ID = "moveId"
        private const val EXTRA_MOVE_IS_FOLDER = "moveIsFolder"
        private const val EXTRA_MOVE_NAME = "moveName"

        fun moveIntent(context: Context, hierarchy: Hierarchy, id: String, isFolder: Boolean, name: String, parentId: String): Intent =
            Intent(context, FolderPickerActivity::class.java)
                .putExtra(EXTRA_HIERARCHY, hierarchy.name)
                .putExtra(EXTRA_MOVE_ID, id).putExtra(EXTRA_MOVE_IS_FOLDER, isFolder).putExtra(EXTRA_MOVE_NAME, name).putExtra(EXTRA_START_FOLDER, parentId)

        /** A folder to save into: the answer is [EXTRA_PICKED_FOLDER]. */
        fun saveIntent(context: Context, hierarchy: Hierarchy): Intent =
            Intent(context, FolderPickerActivity::class.java).putExtra(EXTRA_HIERARCHY, hierarchy.name)
    }
}
