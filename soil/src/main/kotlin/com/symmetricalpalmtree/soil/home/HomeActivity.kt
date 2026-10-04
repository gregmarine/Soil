package com.symmetricalpalmtree.soil.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.bootstrap.KeyGate
import com.symmetricalpalmtree.soil.bootstrap.Library
import com.symmetricalpalmtree.soil.bootstrap.RecoveryKeyActivity
import com.symmetricalpalmtree.soil.bootstrap.UnlockActivity
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.library.ItemApps
import com.symmetricalpalmtree.soil.library.LibraryBrowser
import com.symmetricalpalmtree.soil.library.LibraryPrefs
import com.symmetricalpalmtree.soil.library.NewNotebookActivity
import com.symmetricalpalmtree.soil.databinding.ActivityHomeBinding
import com.symmetricalpalmtree.soil.paper.templates.TemplatePick
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.templates.TemplatesActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.symmetricalpalmtree.soil.data.index.IndexSchema
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.shell.AppEntry
import com.symmetricalpalmtree.soil.shell.AppList
import com.symmetricalpalmtree.soil.shell.HiddenApps
import com.symmetricalpalmtree.soil.shell.SoilBarService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **The home screen**: two views under one top bar. The bar carries the two views and what
 * makes things; the library's filters sit on its own path line and Import on its bottom bar;
 * Settings and the Scratch Pad are the side menu's, Encryption is a row on Settings.
 *
 *  - **The library**, which it opens on: folders and items as cards, a page at a time, with the
 *    Pinned, Recents and Search shelves ([LibraryBrowser]). An item opens in the app for its kind.
 *  - **The app drawer**: the installed apps the person has not hidden, each with its own icon,
 *    in fixed pages. A long press on an app asks whether to hide it; the hidden apps screen
 *    brings it back.
 *
 * It renders [Library.status] rather than waiting for it. Soil is the device's home screen: it
 * cannot forward to a bootstrap and finish, and it must never trap the person. The drawer needs
 * no key, so it works while the library is locked, being prepared, or out of reach.
 *
 * It does not touch the side bars or the firmware's menu at all — that is the bar service's work,
 * and Soil runs as an ordinary app without it.
 */
class HomeActivity : AppCompatActivity() {

    private enum class Showing { LIBRARY, APPS }

    private lateinit var binding: ActivityHomeBinding
    private lateinit var grid: AppGrid
    private lateinit var browser: LibraryBrowser
    private lateinit var importFlow: com.symmetricalpalmtree.soil.importing.ImportFlow
    private lateinit var libraryPrefs: LibraryPrefs
    private var showing = Showing.LIBRARY
    private var appPages = 1
    private var appPage = 0
    private var route = KeyGate.Route.PREPARING
    private var notebookApp = false

    /** The folder whose default paper the template picker is up for. */
    private var templateFor: String? = null
    private val templateLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val folderId = templateFor
        templateFor = null
        if (result.resultCode != android.app.Activity.RESULT_OK || folderId == null) return@registerForActivityResult
        val encoded = result.data?.getStringExtra(Seam.EXTRA_PICK) ?: return@registerForActivityResult
        val pick = TemplatePick.decode(encoded) ?: return@registerForActivityResult
        lifecycleScope.launch {
            // Blank is the absence of a say: a folder whose default is Blank says nothing.
            withContext(Dispatchers.IO) { LibraryStore().setDefaultTemplate(folderId, if (pick is TemplatePick.Blank) null else encoded) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        grid = AppGrid(
            container = binding.appGrid,
            onOpen = { app ->
                if (!AppList.launch(this, app)) {
                    Dialogs.problem(this, getString(R.string.app_open_failed_title), getString(R.string.app_open_failed_body, app.label))
                }
            },
            onHold = ::askToHide,
            onPaged = { page, pages ->
                appPage = page
                appPages = pages
                renderPager()
            },
        )
        libraryPrefs = LibraryPrefs(this)
        browser = LibraryBrowser(
            activity = this,
            binding = binding.browser,
            onOpen = ::open,
            onOpenPage = { item, pageId -> open(item, pageId) },
            onFolderChanged = { libraryPrefs.folderId = it },
        )
        browser.onShelfChanged = { renderLibrary() }
        browser.onDefaultTemplate = { folderId, _ ->
            templateFor = folderId
            templateLauncher.launch(
                android.content.Intent(Seam.ACTION_PICK_TEMPLATE).setPackage(packageName)
                    .putExtra(Seam.EXTRA_CURRENT_TOKEN, null as String?),
            )
        }
        browser.startIn(savedInstanceState?.getString(KEY_FOLDER) ?: libraryPrefs.folderId)
        binding.btnPrev.setOnClickListener { grid.previous() }
        binding.btnNext.setOnClickListener { grid.next() }
        binding.appGrid.onPrevious = { grid.previous() }
        binding.appGrid.onNext = { grid.next() }

        binding.btnLibrary.setOnClickListener { show(Showing.LIBRARY) }
        binding.btnApps.setOnClickListener { show(Showing.APPS) }
        binding.btnHiddenApps.setOnClickListener { startActivity(Intent(this, HiddenAppsActivity::class.java)) }
        binding.btnNewNotebook.setOnClickListener { startActivity(NewNotebookActivity.intent(this, browser.folderId)) }
        binding.btnNewFolder.setOnClickListener { browser.showNewFolderDialog() }
        importFlow = com.symmetricalpalmtree.soil.importing.ImportFlow(this, { browser.folderId }, { browser.reload() }, { renderLibrary() })
        // The library's filters on its path line, Import on its bottom bar.
        val lib = binding.browser
        lib.btnImport.setOnClickListener { importFlow.onTap() }
        lib.btnSearch.setOnClickListener { browser.openSearchDialog() }
        lib.btnRecents.setOnClickListener { browser.toggleShelf(LibraryBrowser.Shelf.RECENTS) }
        lib.btnPinned.setOnClickListener { browser.toggleShelf(LibraryBrowser.Shelf.PINNED) }
        lib.btnSort.setOnClickListener { browser.showSortSheet() }
        binding.btnRecoveryKey.setOnClickListener { startActivity(Intent(this, RecoveryKeyActivity::class.java)) }
        binding.btnUnlock.setOnClickListener { startActivity(Intent(this, UnlockActivity::class.java)) }
        // Every icon button names itself on a long press.
        listOf(binding.btnLibrary, binding.btnApps, binding.btnHiddenApps, binding.btnNewNotebook, binding.btnNewFolder, lib.btnImport, lib.btnSearch, lib.btnRecents, lib.btnPinned, lib.btnSort)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }

        show(savedInstanceState?.getString(KEY_SHOWING)?.let { name -> Showing.values().firstOrNull { it.name == name } } ?: Showing.LIBRARY)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Library.status.collect(::render) }
                // The cards are read again whenever the library opens or an item changes.
                launch {
                    combine(Library.status, ItemSessions.changes) { status, _ -> status.route }.collect { if (it == KeyGate.Route.OPEN) browser.reload() }
                }
                launch {
                    SoilBarService.running.collect { on ->
                        binding.shellNote.visibility = if (on) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    combine(AppList.apps, HiddenApps.hidden, HiddenApps::visible).collect { apps ->
                        binding.appsEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                        grid.show(apps)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // An app may have been installed or removed while Soil was away.
        lifecycleScope.launch { AppList.refresh(this@HomeActivity) }
        lifecycleScope.launch {
            notebookApp = withContext(Dispatchers.IO) { ItemApps.find(this@HomeActivity, IndexSchema.KIND_NOTEBOOK) != null }
            renderLibrary()
        }
        if (::importFlow.isInitialized) importFlow.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SHOWING, showing.name)
        if (::browser.isInitialized) outState.putString(KEY_FOLDER, browser.folderId)
    }

    /** One of the two views, and what of the bars belongs to it. */
    private fun show(view: Showing) {
        showing = view
        val apps = view == Showing.APPS
        binding.libraryView.visibility = if (apps) View.GONE else View.VISIBLE
        binding.appsView.visibility = if (apps) View.VISIBLE else View.GONE
        binding.btnHiddenApps.visibility = if (apps) View.VISIBLE else View.GONE
        renderLibrary()
        // The view that is showing wears a border; nothing is ever greyed.
        binding.btnLibrary.isSelected = !apps
        binding.btnApps.isSelected = apps
        renderPager()
    }

    /** The drawer's pager: the library browser carries its own. A pager over one page is two
     *  buttons that do nothing, so it is not shown. */
    private fun renderPager() {
        binding.pageText.text = getString(R.string.page_of, appPage + 1, appPages)
        binding.bottomBar.visibility = if (showing == Showing.APPS && appPages > 1) View.VISIBLE else View.GONE
    }

    /** The browser while the library is open, the line when it is not; and the library's own
     *  buttons, which stand down on a shelf (a shelf is not a place to create into). */
    private fun renderLibrary() {
        val open = route == KeyGate.Route.OPEN
        val library = showing == Showing.LIBRARY && open
        binding.browser.root.visibility = if (open) View.VISIBLE else View.GONE
        binding.libraryNote.visibility = if (open) View.GONE else View.VISIBLE
        val inShelf = ::browser.isInitialized && browser.inShelf
        binding.btnNewNotebook.visibility = if (library && notebookApp && !inShelf) View.VISIBLE else View.GONE
        binding.btnNewFolder.visibility = if (library && !inShelf) View.VISIBLE else View.GONE
        val lib = binding.browser
        lib.btnImport.visibility = if (library && !inShelf && ::importFlow.isInitialized && importFlow.installed) View.VISIBLE else View.GONE
        lib.btnSort.visibility = if (library && (!inShelf || browser.shelf != LibraryBrowser.Shelf.SEARCH)) View.VISIBLE else View.GONE
        if (::browser.isInitialized) {
            lib.btnRecents.isSelected = browser.shelf == LibraryBrowser.Shelf.RECENTS
            lib.btnPinned.isSelected = browser.shelf == LibraryBrowser.Shelf.PINNED
            lib.btnSearch.isSelected = browser.shelf == LibraryBrowser.Shelf.SEARCH
        }
    }

    private fun open(item: Item, pageId: String? = null) {
        when (ItemApps.open(this, item.id, item.kind, pageId = pageId)) {
            ItemApps.Opened.YES -> Unit
            ItemApps.Opened.NO_APP ->
                Dialogs.problem(this, getString(R.string.item_no_app_title), getString(R.string.item_no_app_body, item.name))
            ItemApps.Opened.FAILED ->
                Dialogs.problem(this, getString(R.string.item_open_failed_title), getString(R.string.item_open_failed_body, item.name))
        }
    }

    /** A long press asks; it never acts. */
    private fun askToHide(app: AppEntry) {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.hide_app_title, app.label))
                .setMessage(R.string.hide_app_body)
                .setPositiveButton(R.string.hide_app_confirm) { _, _ -> HiddenApps.hide(this, app) }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create()
        ).show()
    }

    /** One status, one appearance: the message, and which of the top bar's buttons exist. A
     *  button that cannot act is absent, never greyed — a disabled control is invisible on e-ink. */
    private fun render(status: Library.Status) {
        route = status.route
        renderLibrary()
        binding.libraryMessage.setText(
            when (route) {
                KeyGate.Route.OPEN -> R.string.library_empty
                KeyGate.Route.PREPARING -> R.string.library_preparing
                KeyGate.Route.RECOVERY_KEY -> R.string.library_save_key
                KeyGate.Route.UNLOCK -> R.string.library_locked
                KeyGate.Route.RESUME_ROTATION -> R.string.library_rotating
                KeyGate.Route.BLOCKED -> when (status.index) {
                    SoilIndex.State.FOREIGN_FILE -> R.string.library_foreign
                    SoilIndex.State.DAMAGED_FILE -> R.string.library_damaged
                    else -> R.string.library_unavailable
                }
            },
        )
        binding.btnRecoveryKey.visibility = if (route == KeyGate.Route.RECOVERY_KEY) View.VISIBLE else View.GONE
        binding.btnUnlock.visibility = if (route == KeyGate.Route.UNLOCK) View.VISIBLE else View.GONE
    }

    /** Back peels one layer of the library: out of a shelf, up a folder. A home screen has
     *  nowhere else to go back to. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showing == Showing.LIBRARY && ::browser.isInitialized) browser.onBackPressed()
    }

    private companion object {
        const val KEY_SHOWING = "showing"
        const val KEY_FOLDER = "folder"
    }
    override fun onDestroy() {
        if (::importFlow.isInitialized) importFlow.close()
        super.onDestroy()
    }

}
