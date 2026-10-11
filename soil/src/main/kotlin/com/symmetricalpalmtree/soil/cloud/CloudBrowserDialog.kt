package com.symmetricalpalmtree.soil.cloud

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.GridMath
import com.symmetricalpalmtree.soil.paper.core.Immersive
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.templates.NameDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * **The browser**: Soil draws a tree's folders and files itself, the cloud provider's or, since
 * 2026-10-10, this device's ([BrowserSource]). The source is asked one thing, `list`, and
 * answers rows; every decision about them is made here. Full screen, the top bar Up · crumb ·
 * Cancel · *Save here*, rows paginated below, the pager at the foot.
 *
 * Two modes, one difference: under [Mode.PICK_FOLDER] a file row is drawn and inert and the
 * answer is *Save here*; under [Mode.PICK_FILE] a file row is the answer, the action and the
 * *New folder…* row are absent, and nothing is filtered by extension (which importer reads the
 * file is decided afterwards, by its name). A listing that fails leaves the browser where it was.
 * Not connected closes into the caller's Connect offer. Exactly one of the three callbacks runs.
 */
class CloudBrowserDialog(
    private val activity: AppCompatActivity,
    private val source: BrowserSource,
    private val mode: Mode,
    /** The floor Up will not climb above, and where the browser opens unless [startPath] says otherwise. */
    private val basePath: List<String>,
    /** The folder the browser opens on: a remembered one under [basePath]. A gone folder lists as empty, so there is no opening failure. */
    private val startPath: List<String> = basePath,
    private val onPicked: (Pick) -> Unit,
    private val onNotConnected: () -> Unit = {},
    private val onCancelled: () -> Unit,
) {

    private val providerName: String get() = source.label

    enum class Mode { PICK_FOLDER, PICK_FILE }

    sealed class Pick {
        /** A folder, with the rows the browser last drew of it, so the caller can ask "is my name already here" without a second round trip. */
        class Folder(val path: List<String>, val listing: List<CloudEntry>) : Pick()
        class File(val entry: CloudEntry) : Pick()
    }

    private val dialog = Dialog(activity, R.style.Theme_Soil)

    private var path: List<String> = if (startPath.size >= basePath.size && startPath.take(basePath.size) == basePath) startPath else basePath
    private var entries: List<CloudEntry> = emptyList()
    private var listPage = 0
    private var itemsPerPage = 1
    private var measured = false
    private var loading = false
    private var answered = false

    private lateinit var rows: LinearLayout
    private lateinit var message: TextView
    private lateinit var crumb: TextView
    private lateinit var pager: View
    private lateinit var pageLabel: TextView
    private lateinit var btnUp: AppCompatImageButton
    private lateinit var btnAction: AppCompatButton

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) { answer { onCancelled() }; return }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_cloud_browser)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { answer { onCancelled() } }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }

        TopGuard.applyRootPadding(dialog.findViewById(R.id.cloudBrowserRoot))
        rows = dialog.findViewById(R.id.cloudRows)
        message = dialog.findViewById(R.id.cloudMessage)
        crumb = dialog.findViewById(R.id.cloudCrumb)
        pager = dialog.findViewById(R.id.cloudPager)
        pageLabel = dialog.findViewById(R.id.cloudPageLabel)
        btnUp = dialog.findViewById(R.id.btnCloudUp)
        btnAction = dialog.findViewById(R.id.btnCloudAction)
        val btnCancel = dialog.findViewById<AppCompatButton>(R.id.btnCloudCancel)
        val btnFirst = dialog.findViewById<AppCompatImageButton>(R.id.btnCloudFirst)
        val btnPrev = dialog.findViewById<AppCompatImageButton>(R.id.btnCloudPrev)
        val btnNext = dialog.findViewById<AppCompatImageButton>(R.id.btnCloudNext)
        val btnLast = dialog.findViewById<AppCompatImageButton>(R.id.btnCloudLast)
        listOf(btnUp, btnFirst, btnPrev, btnNext, btnLast).forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }

        btnUp.setOnClickListener { goUp() }
        btnCancel.setOnClickListener { dialog.dismiss() }
        // GONE, never disabled: in PICK_FILE there is no "save here" to be had.
        btnAction.visibility = if (mode == Mode.PICK_FOLDER) View.VISIBLE else View.GONE
        btnAction.setOnClickListener { onSaveHere() }
        btnFirst.setOnClickListener { goToListPage(0) }
        btnPrev.setOnClickListener { goToListPage(listPage - 1) }
        btnNext.setOnClickListener { goToListPage(listPage + 1) }
        btnLast.setOnClickListener { goToListPage(CloudBrowserRules.pageCount(rowCount(), itemsPerPage) - 1) }

        renderCrumb()
        showMessage(R.string.cloud_browser_loading)

        // Rows per page from the real body height, measured once after the first layout.
        rows.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                rows.viewTreeObserver.removeOnGlobalLayoutListener(this)
                itemsPerPage = CloudBrowserRules.itemsPerPage(rows.height - rows.paddingTop - rows.paddingBottom, activity.resources.displayMetrics.density)
                measured = true
                Slog.d(TAG) { "shown: rows/page=$itemsPerPage depth=${path.size} mode=$mode" }
                navigate(path)
            }
        })

        dialog.show()
        dialog.window?.let { w -> Immersive.apply(w, w.decorView) }
    }

    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    // ── Navigation ──────

    /** List [target] and, only if that succeeds, move there. */
    private fun navigate(target: List<String>) {
        if (loading) { Slog.d(TAG) { "navigate ignored: a listing is already running" }; return }
        loading = true
        showMessage(R.string.cloud_browser_loading)
        activity.lifecycleScope.launch {
            val listed = try {
                source.list(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                if (dialog.isShowing) onListFailed(e)
                return@launch
            }
            loading = false
            if (!dialog.isShowing) return@launch
            path = target
            entries = listed
            listPage = 0
            renderCrumb()
            render()
        }
    }

    private fun goUp() {
        if (loading) return
        if (!CloudBrowserRules.canGoUp(path.size, basePath.size)) { Slog.d(TAG) { "up at the base folder: nothing to do" }; return }
        navigate(path.dropLast(1))
    }

    private fun onListFailed(e: Exception) {
        Slog.d(TAG) { "listing failed: ${e.javaClass.simpleName}" }
        when (e) {
            is CloudNotConnected -> { answer { onNotConnected() }; dismiss() }
            is CloudNetworkFailed -> problem(activity.getString(R.string.cloud_browser_network_title, providerName), activity.getString(R.string.cloud_browser_network_body, providerName))
            else -> problem(activity.getString(source.failedTitleRes), activity.getString(source.failedBodyRes))
        }
        if (dialog.isShowing && !answered) render()
    }

    // ── The action, and the New folder row ──────

    private fun onSaveHere() {
        if (loading) { Slog.d(TAG) { "save here ignored: a listing is running" }; return }
        Slog.d(TAG) { "picked a folder at depth ${path.size} (${entries.size} entries listed)" }
        answer { onPicked(Pick.Folder(path, entries)) }
        dismiss()
    }

    private fun onFilePicked(entry: CloudEntry) {
        if (loading) { Slog.d(TAG) { "file tap ignored: a listing is running" }; return }
        Slog.d(TAG) { "picked a file at depth ${path.size} (${entry.sizeBytes} B listed)" }
        answer { onPicked(Pick.File(entry)) }
        dismiss()
    }

    private fun onNewFolder() {
        if (loading) return
        NameDialog.show(activity, titleRes = R.string.cloud_browser_new_folder_title, confirmRes = R.string.cloud_browser_new_folder_confirm, hintRes = R.string.cloud_browser_new_folder_hint) { name, dismissNameDialog ->
            when (CloudBrowserRules.newFolderOutcome(name, entries, path.size)) {
                CloudBrowserRules.NewFolderOutcome.REFUSED -> problem(activity.getString(R.string.cloud_browser_name_title), activity.getString(R.string.cloud_browser_name_body))
                CloudBrowserRules.NewFolderOutcome.ENTER_EXISTING -> { dismissNameDialog(); navigate(path + name) }
                CloudBrowserRules.NewFolderOutcome.CREATE -> { dismissNameDialog(); createFolder(name) }
            }
        }
    }

    private fun createFolder(name: String) {
        if (loading) return
        loading = true
        showMessage(R.string.cloud_browser_creating)
        val target = path + name
        activity.lifecycleScope.launch {
            try {
                source.ensureFolder(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                if (dialog.isShowing) onListFailed(e)
                return@launch
            }
            loading = false
            if (!dialog.isShowing) return@launch
            navigate(target)
        }
    }

    // ── Drawing ──────

    private fun renderCrumb() {
        crumb.text = CloudBrowserRules.crumb(providerName, path, activity.getString(R.string.cloud_browser_crumb_separator))
        // INVISIBLE, not GONE: the crumb must not step sideways at the floor.
        btnUp.visibility = if (CloudBrowserRules.canGoUp(path.size, basePath.size)) View.VISIBLE else View.INVISIBLE
    }

    private fun showMessage(@StringRes textRes: Int) {
        rows.removeAllViews()
        message.setText(textRes)
        message.visibility = View.VISIBLE
        pager.visibility = View.INVISIBLE
    }

    private fun rowCount(): Int = entries.size + if (mode == Mode.PICK_FOLDER) 1 else 0

    private fun goToListPage(page: Int) {
        if (loading) return
        val clamped = GridMath.clampPage(page, CloudBrowserRules.pageCount(rowCount(), itemsPerPage))
        if (clamped == listPage) return
        listPage = clamped
        render()
    }

    private fun render() {
        if (!measured) return
        val all = CloudBrowserRules.rows(entries, offersNewFolder = mode == Mode.PICK_FOLDER)
        if (all.isEmpty()) { showMessage(R.string.cloud_browser_empty); return }
        message.visibility = View.GONE
        rows.removeAllViews()
        val pageCount = CloudBrowserRules.pageCount(all.size, itemsPerPage)
        listPage = GridMath.clampPage(listPage, pageCount)
        val inflater = LayoutInflater.from(activity)
        for (row in CloudBrowserRules.page(all, listPage, itemsPerPage)) rows.addView(buildRow(inflater, row))
        pageLabel.text = activity.getString(R.string.page_indicator, listPage + 1, pageCount)
        pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
    }

    private fun buildRow(inflater: LayoutInflater, row: CloudBrowserRules.Row): View {
        val view = inflater.inflate(R.layout.item_cloud_entry, rows, false)
        val icon = view.findViewById<AppCompatImageView>(R.id.cloudRowIcon)
        val label = view.findViewById<TextView>(R.id.cloudRowLabel)
        when (row) {
            is CloudBrowserRules.Row.NewFolder -> {
                icon.setImageResource(R.drawable.ic_folder_plus)
                label.setText(R.string.cloud_browser_new_folder)
                view.setOnClickListener { onNewFolder() }
            }
            is CloudBrowserRules.Row.Entry -> {
                val entry = row.entry
                icon.setImageResource(if (entry.isFolder) R.drawable.ic_folder else R.drawable.ic_file_text)
                label.text = entry.name
                if (entry.isFolder) view.setOnClickListener { navigate(path + entry.name) }
                else if (CloudBrowserRules.fileTappable(mode == Mode.PICK_FILE)) view.setOnClickListener { onFilePicked(entry) }
            }
        }
        return view
    }

    private inline fun answer(block: () -> Unit) {
        if (answered) return
        answered = true
        block()
    }

    private fun problem(title: CharSequence, body: CharSequence) {
        if (!dialog.isShowing) return
        Dialogs.problem(activity, title, body)
    }

    private companion object { const val TAG = "CloudBrowser" }
}
