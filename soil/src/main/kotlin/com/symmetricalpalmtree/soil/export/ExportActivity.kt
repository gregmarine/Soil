package com.symmetricalpalmtree.soil.export

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.cloud.CloudBrowserDialog
import com.symmetricalpalmtree.soil.cloud.CloudBrowserRules
import com.symmetricalpalmtree.soil.cloud.CloudClient
import com.symmetricalpalmtree.soil.cloud.CloudConnectEntry
import com.symmetricalpalmtree.soil.cloud.CloudNetworkFailed
import com.symmetricalpalmtree.soil.cloud.CloudNotConnected
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityExportBinding
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.CloudStatus
import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.ext.ExportResult
import com.symmetricalpalmtree.soil.ext.ExportSpec
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.ext.Extensions
import com.symmetricalpalmtree.soil.ext.ExporterClient
import com.symmetricalpalmtree.soil.ext.ExporterInfo
import com.symmetricalpalmtree.soil.library.ItemApps
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamFormat
import com.symmetricalpalmtree.soil.seam.SeamRenderInfo
import com.symmetricalpalmtree.soil.settings.SettingsPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **Soil's one export screen**, for every kind of item. It finds the installed exporters, draws
 * their options with its own widgets, prepares what the chosen one needs (the item file, keyed
 * as asked, or the pages rendered by the item's app) and hands the writing to the extension over
 * two descriptors. Everything that touches a key stays here.
 *
 * Reached from the library's item sheet, and from an app's page sheet with [Seam.ACTION_EXPORT]
 * for one page of the item; an app that closed its item to export it is reopened on the way out.
 *
 * **Render-only** ([ExportRenderMode]): an app with no item — the calendar — names a kind, a key
 * and a name instead of an item. The screen skips the library and the item's key, lists the page
 * formats alone, and the kind's renderer draws under the key as it would under an item id. On
 * the way out there is nothing to reopen.
 *
 * With a cloud provider installed the screen has a Destination row ([ExportDestination]). On the
 * cloud leg the exporter writes into a file in Soil's cache, verified as on the local leg, and
 * that file is uploaded under `Exports/` through the browser's pick, replace-by-name after a
 * *Replace?* that stands in for the picker's overwrite confirmation. Nothing in the cloud is ever
 * deleted by a failure; every failure before the upload says so.
 */
class ExportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportBinding
    private lateinit var panel: ExportPanel
    private val prefs by lazy { SettingsPrefs(this) }

    private lateinit var itemId: String
    private var item: Item? = null

    /** Render-only: no item, the kind's renderer under a key. Null for an ordinary export. */
    private var renderOnly: ExportRenderMode.Request? = null
    private var pageId: String? = null
    private var returnToApp = false
    private var relaunched = false
    private var scope: ExportScope = ExportScope.Whole
    private var renderer: ComponentName? = null

    /** What the item's app says of its kind: whether it flows, and the formats it writes itself. */
    private var renderInfo: SeamRenderInfo = SeamRenderInfo.PAGES_ONLY
    private var pageSize: String = ExportPageSize.DEFAULT

    private class PageFacts(val number: Int, val title: String?)
    private var pageFacts: PageFacts? = null

    /**
     * One row of the format list. An extension's exporter, or with [appFormat] one of the
     * formats the item's own app writes: then there is no extension behind it (the reference
     * only names it, for the choice and for "last used"), the app writes the finished file and
     * Soil puts it where it was asked.
     */
    private class Candidate(val extension: Extension, val info: ExporterInfo, val appFormat: SeamFormat? = null)
    private var described: List<Candidate> = emptyList()
    private var candidates: List<Candidate> = emptyList()
    private var chosenPackage: String? = null
    private val values = LinkedHashMap<String, String>()

    private var busy = false
    private var discovering = false
    private var typedPassphrase: String? = null
    private var typedExportSecret: String? = null

    // The cloud destination: the row's answer, the connect door, the provider as last found and
    // what it said of itself (read again at each discovery; a stale "connected" would aim an
    // export at a cloud since disconnected), and the browser while it is up.
    private var destinationChoice = ExportDestination.Choice.LOCAL
    private var cloud: CloudConnectEntry? = null
    private var cloudRef: Extension? = null
    private var cloudStatus: CloudStatus? = null
    private var selectCloudOnDiscovery = false
    private var browser: CloudBrowserDialog? = null

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) runExport(Destination.Saf(uri)) else cancelledAtThePicker()
    }

    private val treeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) runExport(Destination.SafTree(uri)) else cancelledAtThePicker()
    }

    private fun cancelledAtThePicker() {
        typedPassphrase = null
        typedExportSecret = null
        busy = false
        Slog.d(TAG) { "destination picker cancelled" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderOnly = ExportRenderMode.requestOf(intent.getStringExtra(Seam.EXTRA_RENDER_KIND), intent.getStringExtra(Seam.EXTRA_RENDER_KEY), intent.getStringExtra(Seam.EXTRA_RENDER_NAME))
        // In render-only mode the key is what the renderer gets as its item id.
        itemId = renderOnly?.key ?: intent.getStringExtra(Seam.EXTRA_ITEM_ID).orEmpty()
        if (itemId.isEmpty() || !SoilIndex.isReady() || KeySession.get() == null) { finish(); return }
        pageId = intent.getStringExtra(Seam.EXTRA_PAGE_ID)?.takeIf { it.isNotEmpty() }
        returnToApp = intent.getBooleanExtra(Seam.EXTRA_RETURN_TO_APP, false)
        scope = ExportScope.seeded(pageId)

        binding = ActivityExportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        panel = ExportPanel(this)
        binding.btnBack.setOnClickListener { if (busy) showBusyGuard() else finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (busy) showBusyGuard() else finish() }
        })
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnExport.setOnClickListener { onExportTap() }

        // The sign-in came back: the person reached for the cloud answer, so the next discovery
        // takes it for them when the fresh status agrees.
        cloud = CloudConnectEntry(this) { wasConnected ->
            if (wasConnected) selectCloudOnDiscovery = true
            if (!busy) discover()
        }

        savedInstanceState?.let { state ->
            chosenPackage = state.getString(KEY_PACKAGE)
            if (state.getBoolean(KEY_SCOPE_WHOLE)) scope = ExportScope.Whole
            if (state.getBoolean(KEY_DESTINATION)) destinationChoice = ExportDestination.Choice.CLOUD
            state.getBundle(KEY_VALUES)?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { values[k] = it } } }
        }
        discover()
    }

    override fun onResume() {
        super.onResume()
        if (!busy && ::binding.isInitialized) discover()
    }

    override fun onDestroy() {
        hideProgress()
        browser?.dismiss()
        browser = null
        cloud?.close()
        cloud = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PACKAGE, chosenPackage)
        outState.putBoolean(KEY_SCOPE_WHOLE, scope is ExportScope.Whole)
        outState.putBoolean(KEY_DESTINATION, destinationChoice == ExportDestination.Choice.CLOUD)
        outState.putBundle(KEY_VALUES, Bundle().also { b -> values.forEach { (k, v) -> b.putString(k, v) } })
    }

    /** An app that closed its item for this export gets it back, at the page it was on. */
    override fun finish() {
        val it = item
        if (returnToApp && !relaunched && it != null) {
            relaunched = true
            ItemApps.open(this, it.id, it.kind, pageId = pageId)
        }
        super.finish()
    }

    // ── Discovery ──────

    private fun discover() {
        if (discovering) return
        discovering = true
        lifecycleScope.launch {
            val kept = try { loadCandidates() } finally { discovering = false }
            if (busy || isFinishing || isDestroyed) return@launch
            candidates = kept
            Slog.d(TAG) { "${kept.size} usable exporter(s)" }
            if (kept.isEmpty()) { problemAndClose(R.string.export_none_title, R.string.export_none_body); return@launch }
            if (selectCloudOnDiscovery) {
                selectCloudOnDiscovery = false
                if (cloudStatus?.connected == true) destinationChoice = ExportDestination.Choice.CLOUD
            }
            reselect()
        }
    }

    private fun reselect() {
        val standing = candidates.firstOrNull { it.extension.packageName == chosenPackage }
        val remembered = candidates.firstOrNull { it.extension.packageName == prefs.lastExporter }
        select(standing ?: remembered ?: candidates.first(), keepValues = standing != null)
    }

    private fun listedNow(): List<Candidate> = described.filter { ExportScope.lists(it.info.sourceKind, scope) }

    private fun setScope(next: ExportScope) {
        if (next == scope) return
        scope = next
        candidates = listedNow()
        if (candidates.isEmpty()) { problemAndClose(R.string.export_none_title, R.string.export_none_body); return }
        reselect()
    }

    private suspend fun loadCandidates(): List<Candidate> {
        val mode = renderOnly
        val kind: String
        if (mode != null) {
            binding.itemName.text = mode.name
            kind = mode.kind
        } else {
            val found = withContext(Dispatchers.IO) { IndexStore().aliveItem(itemId) }
            if (found == null) { if (!isFinishing) problemAndClose(R.string.export_failed_title, R.string.export_missing_body); return emptyList() }
            item = found
            binding.itemName.text = found.name
            kind = found.kind
        }
        if (renderer == null) {
            renderer = withContext(Dispatchers.IO) { AppRenderers.find(this@ExportActivity, kind) }
            renderInfo = renderer?.let { AppRenderers.describe(this@ExportActivity, it) } ?: SeamRenderInfo.PAGES_ONLY
            pageSize = ExportPageSize.orDefault(prefs.lastPageSize)
        }
        // Render-only: nothing to draw with is nothing to export.
        if (mode != null && renderer == null) { if (!isFinishing) problemAndClose(R.string.export_none_title, R.string.export_none_body); return emptyList() }
        val door = pageId
        if (door != null && pageFacts == null) {
            val r = renderer
            pageFacts = if (r == null) null else runCatching { AppRenderers.pages(this@ExportActivity, r, itemId) }.getOrNull()?.let { names ->
                val at = names.ids.indexOf(door)
                if (at < 0) null else PageFacts(names.numbers[at], names.titles[at].ifEmpty { null })
            }
            if (pageFacts == null) { Slog.d(TAG) { "the page is not in the item; exporting the whole item" }; pageId = null; scope = ExportScope.Whole }
        }
        val refs = withContext(Dispatchers.IO) { Extensions.exporters(this@ExportActivity) }
        val kept = ArrayList<Candidate>(refs.size)
        for (ref in refs) {
            val info = describe(ref) ?: continue
            if (!ExportOptions.isRenderable(info)) { Slog.d(TAG) { "dropping ${ref.packageName}: an option this build cannot draw" }; continue }
            // A pages exporter is only as good as an app to draw with.
            if (info.sourceKind == ExportContract.SOURCE_PAGES && renderer == null) { Slog.d(TAG) { "dropping ${ref.packageName}: no renderer for $kind" }; continue }
            // Render-only: no file to hand a file exporter.
            if (mode != null && !ExportRenderMode.lists(info.sourceKind)) { Slog.d(TAG) { "dropping ${ref.packageName}: render-only" }; continue }
            kept += Candidate(ref, info)
        }
        // The app's own formats, after the extensions' — not in render-only mode, where the
        // pages are the whole of it. A descriptor the constructor refuses drops the format with a
        // log line, as it drops an extension.
        for (format in if (mode != null) emptyList() else renderInfo.formats) {
            val info = runCatching { ExporterInfo(format.label, format.fileExtension, format.mimeType, emptyList()) }.getOrNull()
            if (info == null) { Slog.d(TAG) { "dropping an app format: its descriptor was refused" }; continue }
            kept += Candidate(Extension(APP_FORMAT_PREFIX + format.id, "", format.label), info, format)
        }
        loadCloud()
        described = kept
        if (scope is ExportScope.Page && !ExportScope.offerable(kept.map { it.info.sourceKind })) scope = ExportScope.Whole
        return listedNow()
    }

    private fun scopeRowVisible(): Boolean = pageId != null && ExportScope.offerable(described.map { it.info.sourceKind })

    private suspend fun describe(ref: Extension): ExporterInfo? = try {
        ExporterClient(this, ref).describe()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Slog.d(TAG) { "dropping ${ref.packageName}: describe failed (${e.javaClass.simpleName})" }
        null
    }

    private fun select(c: Candidate, keepValues: Boolean) {
        chosenPackage = c.extension.packageName
        val merged = ExportOptions.specValues(c.info, if (keepValues) values else emptyMap())
        values.clear()
        values.putAll(merged)
        if (!keepValues) { binding.editPassphrase.setText(""); binding.editPassphraseConfirm.setText("") }
        render()
    }

    private fun current(): Candidate? = candidates.firstOrNull { it.extension.packageName == chosenPackage }

    // ── The rows ──────

    private fun render() {
        val c = current() ?: return
        binding.scope.removeAllViews()
        val scopeVisible = scopeRowVisible()
        binding.scope.visibility = if (scopeVisible) View.VISIBLE else View.GONE
        if (scopeVisible) {
            binding.scope.addView(panel.caption(getString(R.string.export_scope_caption)))
            val page = scope is ExportScope.Page
            binding.scope.addView(panel.choice(getString(R.string.export_scope_page), page) { if (!page) setScope(ExportScope.seeded(pageId)) })
            binding.scope.addView(panel.choice(getString(R.string.export_scope_item), !page) { if (page) setScope(ExportScope.Whole) })
        }
        renderDestination()
        binding.chooser.removeAllViews()
        if (candidates.size == 1) {
            binding.chooser.addView(panel.value(c.info.formatLabel))
        } else {
            for (candidate in candidates) {
                val checked = candidate.extension.packageName == chosenPackage
                binding.chooser.addView(panel.choice(candidate.info.formatLabel, checked) { if (!checked) select(candidate, keepValues = false) })
            }
        }
        binding.options.removeAllViews()
        if (asksPageSize(c)) {
            binding.options.addView(panel.caption(getString(R.string.export_page_size_caption)))
            for (choice in ExportPageSize.CHOICES) {
                binding.options.addView(panel.choice(getString(pageSizeLabel(choice)), pageSize == choice) { pageSize = choice; render() })
            }
        }
        for (d in c.info.options) {
            // An item that flows has no paper to put under it.
            if (renderInfo.flowing && d.id == ExportContract.OPTION_PAGE_TEMPLATE) continue
            when {
                ExportOptions.isFixed(d) -> {
                    val value = values[d.id] ?: d.defaultValue
                    binding.options.addView(panel.caption(d.label))
                    binding.options.addView(panel.value(ExportOptions.choiceLabel(d, value)))
                }
                d.kind == ExportContract.KIND_SINGLE_CHOICE -> {
                    binding.options.addView(panel.caption(d.label))
                    d.choiceIds.forEachIndexed { i, choiceId ->
                        binding.options.addView(panel.choice(d.choiceLabels[i], values[d.id] == choiceId) { values[d.id] = choiceId; render() })
                    }
                }
                d.kind == ExportContract.KIND_TOGGLE -> {
                    val on = values[d.id] == "1"
                    binding.options.addView(panel.toggle(d.label, on) { values[d.id] = if (on) "0" else "1"; render() })
                }
            }
        }
        val info = c.info
        val protect = ExportOptions.wantsExportSecret(info, values)
        binding.passphraseBlock.visibility = if (ExportOptions.needsPassphrase(info, values) || protect) View.VISIBLE else View.GONE
        binding.passphraseCaption.setText(if (protect) R.string.export_password_caption else R.string.export_passphrase_caption)
        binding.editPassphrase.setHint(if (protect) R.string.export_password_hint else R.string.export_passphrase_hint)
        binding.editPassphraseConfirm.setHint(if (protect) R.string.export_password_confirm_hint else R.string.export_passphrase_confirm_hint)
        binding.plainWarning.visibility = if (ExportOptions.showsPlainWarning(info, values)) View.VISIBLE else View.GONE
    }

    /** A page size is asked for an item that flows, by any format that is laid out on pages. */
    private fun asksPageSize(c: Candidate): Boolean =
        renderInfo.flowing && (c.appFormat?.paged ?: (c.info.sourceKind == ExportContract.SOURCE_PAGES))

    private fun pageSizeLabel(choice: String): Int = when (choice) {
        Seam.PAGE_LETTER -> R.string.export_page_size_letter
        Seam.PAGE_A4 -> R.string.export_page_size_a4
        else -> R.string.export_page_size_screen
    }

    // ── Progress ──────

    private var progress: AlertDialog? = null

    private fun showProgress(@StringRes textRes: Int) {
        if (isFinishing || isDestroyed) return
        progress = Dialogs.style(AlertDialog.Builder(this).setMessage(textRes).setCancelable(false).create()).also { it.show() }
    }

    private fun hideProgress() { progress?.let { runCatching { it.dismiss() } }; progress = null }
    private fun stage(@StringRes textRes: Int) { progress?.setMessage(getString(textRes)) }
    private fun stage(text: String) { progress?.setMessage(text) }

    // ── The tap ──────

    /** What the files are named after: the item, or the render-only request. */
    private fun displayName(): String = renderOnly?.name ?: item?.name.orEmpty()

    private fun stem(): String {
        val name = displayName()
        return when (scope) {
            ExportScope.Whole -> ExportNaming.base(name, itemId)
            is ExportScope.Page -> ExportNaming.pageStem(name, itemId, pageFacts?.number ?: 0, pageFacts?.title)
        }
    }

    private fun perPage(c: Candidate): Boolean = ExportDelivery.perPage(c.info.delivery, scope)

    private fun stemFor(index: Int, pageNames: List<ExportNaming.PageName>): String {
        val name = pageNames.getOrNull(index)
        return ExportNaming.pageStem(displayName(), itemId, name?.number ?: (index + 1), name?.title)
    }

    private fun onExportTap() {
        if (busy) { Slog.d(TAG) { "export tap ignored: already running" }; return }
        val c = current() ?: return
        val protect = ExportOptions.wantsExportSecret(c.info, values)
        val rekey = ExportOptions.needsPassphrase(c.info, values)
        typedPassphrase = null
        typedExportSecret = null
        if (rekey || protect) {
            val typed = binding.editPassphrase.text?.toString().orEmpty()
            val confirm = binding.editPassphraseConfirm.text?.toString().orEmpty()
            if (typed.isEmpty() || confirm.isEmpty()) {
                Dialogs.problem(this, if (protect) R.string.export_password_missing_title else R.string.export_passphrase_missing_title, if (protect) R.string.export_password_missing_body else R.string.export_passphrase_missing_body)
                return
            }
            if (typed != confirm) {
                Dialogs.problem(this, if (protect) R.string.export_password_mismatch_title else R.string.export_passphrase_mismatch_title, if (protect) R.string.export_password_mismatch_body else R.string.export_passphrase_mismatch_body)
                return
            }
            if (protect && typed.length > ExportContract.MAX_EXPORT_SECRET_CHARS) { Dialogs.problem(this, R.string.export_password_long_title, R.string.export_password_long_body); return }
            if (protect) typedExportSecret = typed else typedPassphrase = typed
        }
        if (destinationChoice == ExportDestination.Choice.CLOUD) { openCloudBrowser(c); return }
        if (perPage(c)) {
            busy = true
            try {
                treeLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
            } catch (e: Exception) {
                busy = false
                Log.w(TAG, "no folder picker: ${e.javaClass.simpleName}")
                Dialogs.problem(this, R.string.export_no_picker_title, R.string.export_no_picker_body)
            }
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(ExportOptions.mimeType(c.info, values))
            .putExtra(Intent.EXTRA_TITLE, ExportNaming.fileName(stem(), ExportOptions.fileExtension(c.info, values)))
        busy = true
        try {
            saveLauncher.launch(intent)
        } catch (e: Exception) {
            busy = false
            Log.w(TAG, "no document creator: ${e.javaClass.simpleName}")
            Dialogs.problem(this, R.string.export_no_picker_title, R.string.export_no_picker_body)
        }
    }

    private sealed class Destination {
        class Saf(val uri: Uri) : Destination()
        class SafTree(val tree: Uri) : Destination()
        /** One file, named, into a folder of the provider's tree. */
        class Cloud(val path: List<String>, val name: String, val mime: String) : Destination()
        /** One file per page into a folder of the provider's tree. */
        class CloudFolder(val path: List<String>) : Destination()
    }

    // ── The destination ──────

    /** The row exists only while a provider is installed: GONE otherwise, never disabled. */
    private fun renderDestination() {
        binding.destination.removeAllViews()
        val visible = ExportDestination.rowVisible(cloudRef != null)
        destinationChoice = ExportDestination.settled(destinationChoice, visible)
        binding.destination.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        binding.destination.addView(panel.caption(getString(R.string.export_destination_caption)))
        val local = destinationChoice == ExportDestination.Choice.LOCAL
        binding.destination.addView(panel.choice(getString(R.string.export_destination_local), local) { if (!local) { destinationChoice = ExportDestination.Choice.LOCAL; render() } })
        binding.destination.addView(panel.choice(cloudName(), !local) { if (local) onCloudDestinationTap() })
    }

    private fun onCloudDestinationTap() {
        when (ExportDestination.onCloudTap(cloudStatus)) {
            ExportDestination.Tap.SELECT -> { destinationChoice = ExportDestination.Choice.CLOUD; render() }
            // The radio checked itself at the tap; a refused answer redraws the row as it stands.
            ExportDestination.Tap.NOT_CONFIGURED -> { render(); Dialogs.problem(this, R.string.cloud_not_configured_title, R.string.cloud_not_configured_body) }
            ExportDestination.Tap.OFFER_CONNECT -> { render(); offerConnect() }
        }
    }

    /** The inline Connect offer: Connect is the one thing that helps with no account or no answer. */
    private fun offerConnect() {
        val entry = cloud ?: return
        if (!entry.isAvailable || isFinishing || isDestroyed) return
        val name = cloudName()
        Dialogs.style(
            AlertDialog.Builder(this).setTitle(getString(R.string.cloud_connect_offer_title, name)).setMessage(getString(R.string.cloud_connect_offer_body, name))
                .setPositiveButton(R.string.cloud_connect) { _, _ -> entry.open() }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
        ).show()
    }

    /** Is a provider installed, and what does it say of itself? A provider that will not answer keeps its row: "did not answer" is said at the tap. */
    private suspend fun loadCloud() {
        val ref = cloud?.discover()
        cloudRef = ref
        cloudStatus = if (ref == null) null else try {
            CloudClient.status(this, ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "cloud status unavailable: ${e.javaClass.simpleName}" }
            null
        }
    }

    private fun cloudName(): String = ExportDestination.providerName(cloudStatus, cloudRef?.label.orEmpty())

    /** The cloud's stand-in for the pickers: the browser over `Exports/`, answering a folder. */
    private fun openCloudBrowser(c: Candidate) {
        val ref = cloudRef
        if (ref == null) { failCloud(R.string.export_failed_title, getString(R.string.export_cloud_gone_body)); return }
        busy = true
        browser?.dismiss()
        val dialog = CloudBrowserDialog(
            activity = this,
            ref = ref,
            providerName = cloudName(),
            mode = CloudBrowserDialog.Mode.PICK_FOLDER,
            basePath = listOf(ExportDestination.EXPORTS_FOLDER),
            onPicked = { pick ->
                browser = null
                when (pick) {
                    is CloudBrowserDialog.Pick.Folder -> if (perPage(c)) confirmFolderThenExport(pick.path) else confirmThenUpload(c, pick.path, pick.listing)
                    is CloudBrowserDialog.Pick.File -> cancelledAtThePicker()
                }
            },
            onNotConnected = {
                browser = null
                cancelledAtThePicker()
                lifecycleScope.launch {
                    loadCloud()
                    if (!isFinishing && !isDestroyed) { render(); offerConnect() }
                }
            },
            onCancelled = { browser = null; cancelledAtThePicker() },
        )
        browser = dialog
        dialog.show()
    }

    /** An upload replaces by name, so a folder already holding the name gets the *Replace?* question first. */
    private fun confirmThenUpload(c: Candidate, path: List<String>, listing: List<CloudEntry>) {
        val name = ExportNaming.fileName(stem(), ExportOptions.fileExtension(c.info, values))
        val destination = Destination.Cloud(path, name, ExportOptions.mimeType(c.info, values))
        if (CloudBrowserRules.fileNamed(listing, name) == null) { runExport(destination); return }
        if (isFinishing || isDestroyed) { cancelledAtThePicker(); return }
        var replacing = false
        Dialogs.style(
            AlertDialog.Builder(this).setTitle(getString(R.string.cloud_replace_title, name)).setMessage(R.string.cloud_replace_body)
                .setPositiveButton(R.string.cloud_replace_confirm) { _, _ -> replacing = true; runExport(destination) }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
        ).also { it.setOnDismissListener { if (!replacing) cancelledAtThePicker() } }.show()
    }

    /** The per-page leg's one question, asked once about the folder: the names are not known until the pages are rendered. */
    private fun confirmFolderThenExport(path: List<String>) {
        if (isFinishing || isDestroyed) { cancelledAtThePicker(); return }
        val where = path.lastOrNull() ?: cloudName()
        var uploading = false
        Dialogs.style(
            AlertDialog.Builder(this).setTitle(getString(R.string.export_cloud_folder_title, where)).setMessage(R.string.export_cloud_folder_body)
                .setPositiveButton(R.string.export_upload_confirm) { _, _ -> uploading = true; runExport(Destination.CloudFolder(path)) }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
        ).also { it.setOnDismissListener { if (!uploading) cancelledAtThePicker() } }.show()
    }

    private suspend fun uploadAndConfirm(c: Candidate, cloud: Destination.Cloud, file: File) {
        val ref = cloudRef
        if (ref == null) { failCloud(R.string.export_failed_title, getString(R.string.export_cloud_gone_body)); return }
        if (!uploadOne(ref, cloud.path, cloud.name, cloud.mime, file, prefix = "")) return
        prefs.lastExporter = c.extension.packageName
        if (asksPageSize(c)) prefs.lastPageSize = pageSize
        hideProgress()
        if (isFinishing || isDestroyed) return
        Dialogs.confirm(this, R.string.export_done_title, getString(R.string.export_cloud_done_body, cloudName())) { finish() }
    }

    /**
     * One file up, and the honest sentence when it does not land. [prefix] is what the per-page
     * loop puts before every sentence, empty for a single file; the "Nothing was uploaded" note
     * is true of one file and said only then.
     */
    private suspend fun uploadOne(ref: Extension, path: List<String>, name: String, mime: String, file: File, prefix: String): Boolean {
        val provider = cloudName()
        fun report(@StringRes titleRes: Int, message: String, note: Boolean) {
            if (note && prefix.isEmpty()) { failCloud(titleRes, message); return }
            hideProgress()
            if (isFinishing || isDestroyed) return
            Dialogs.problem(this, titleRes, prefix + message)
        }
        stage(getString(R.string.export_uploading, provider))
        val bytes = withContext(Dispatchers.IO) { file.length() }
        val pfd = withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() }
        if (pfd == null) { report(R.string.export_failed_title, getString(R.string.export_prepare_failed_body), note = true); return false }
        val entry = try {
            CloudClient.upload(this, ref, path.toTypedArray(), name, mime, pfd, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudNotConnected) {
            hideProgress()
            if (isFinishing || isDestroyed) return false
            Dialogs.style(
                AlertDialog.Builder(this).setTitle(R.string.export_failed_title).setMessage(prefix + getString(R.string.export_cloud_not_connected_body, provider))
                    .setPositiveButton(R.string.cloud_connect) { _, _ -> cloud?.open() }
                    .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.ok, null).create(),
            ).show()
            return false
        } catch (e: CloudNetworkFailed) {
            report(R.string.export_failed_title, getString(R.string.export_cloud_network_body, provider), note = true)
            return false
        } catch (e: Exception) {
            // No answer: the file may or may not have arrived. Said as such; nothing deleted.
            Slog.d(TAG) { "upload failed: ${e.javaClass.simpleName}" }
            report(R.string.export_failed_title, getString(R.string.export_cloud_unanswered_body, provider), note = false)
            return false
        }
        if (ExportVerification.cloudVerdict(entry.sizeBytes, bytes) != ExportVerification.Verdict.OK) {
            Log.w(TAG, "the provider reports ${entry.sizeBytes} for $bytes uploaded bytes")
            report(R.string.export_verify_title, getString(R.string.export_cloud_verify_body, provider), note = false)
            return false
        }
        Slog.d(TAG) { "uploaded $bytes bytes" }
        return true
    }

    /** A cloud failure before anything reached the provider. */
    private fun failCloud(@StringRes titleRes: Int, message: String) {
        hideProgress()
        if (isFinishing || isDestroyed) return
        Dialogs.problem(this, titleRes, "$message ${getString(R.string.export_cloud_untouched_note)}")
    }

    /** The cache file the exporter writes on the cloud leg, wiped with the rest in `finally`. */
    private fun openCacheSink(file: File): ParcelFileDescriptor? {
        file.parentFile?.mkdirs()
        return runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE) }
            .onFailure { Log.w(TAG, "could not open the cache file: ${it.javaClass.simpleName}") }.getOrNull()
    }

    // ── The flow ──────

    private fun runExport(destination: Destination) {
        busy = true
        showProgress(R.string.export_preparing)
        lifecycleScope.launch {
            val saf = destination as? Destination.Saf
            val sizesAtStart = if (saf != null) withContext(Dispatchers.IO) { destinationSizes(saf.uri) } else emptyList()
            val emptyAtStart = sizesAtStart.isNotEmpty() && sizesAtStart.all { it == 0L }
            var destinationTouched = false
            suspend fun failed(@StringRes titleRes: Int, message: String) = when {
                saf != null -> fail(saf.uri, titleRes, message, mayDelete = destinationTouched || emptyAtStart)
                destination is Destination.Cloud || destination is Destination.CloudFolder -> failCloud(titleRes, message)
                else -> failNothing(titleRes, message)
            }
            try {
                val c = current() ?: reselectAfterRestore()
                if (c == null) { failed(R.string.export_failed_title, getString(R.string.export_gone_body)); return@launch }
                val wantsSecret = ExportOptions.wantsExportSecret(c.info, values)
                val armedAtTap = values[ExportContract.OPTION_PROTECT] == "1"
                if ((wantsSecret || armedAtTap) && (typedExportSecret == null || !wantsSecret)) { failed(R.string.export_failed_title, getString(R.string.export_password_lost_body)); return@launch }
                val specValues = ExportOptions.specValues(c.info, values)
                val perPage = destination is Destination.SafTree || destination is Destination.CloudFolder
                val scaled = if (c.appFormat == null && asksPageSize(c)) ExportPageSize.specValues(pageSize) else emptyMap()
                val spec = if (perPage) null else try {
                    ExportSpec(values = specValues + scaled, itemName = ExportNaming.specNameOf(stem()), exportSecret = if (wantsSecret) typedExportSecret else null)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "spec rejected: ${e.javaClass.simpleName}")
                    failed(R.string.export_failed_title, getString(R.string.export_failed_body))
                    return@launch
                }
                val prepared = when {
                    c.appFormat != null -> producedByApp(c, c.appFormat)
                    c.info.sourceKind == ExportContract.SOURCE_PAGES -> renderedPages(c)
                    else -> keyedArtifact(c)
                }
                val streamFile = when (prepared) {
                    is StreamSource.Failed -> { failed(R.string.export_failed_title, prepared.message); return@launch }
                    is StreamSource.Ready -> prepared.file
                }
                if (perPage) {
                    exportPerPage(c, destination, streamFile, (prepared as StreamSource.Ready).pageNames, specValues, if (wantsSecret) typedExportSecret else null)
                    return@launch
                }
                checkNotNull(spec)
                // On the cloud leg the exporter writes into Soil's cache; the upload follows the verdict.
                val cloudDestination = destination as? Destination.Cloud
                val cacheOut = if (cloudDestination != null) File(File(cacheDir, ExportArtifact.DIR), "out." + ExportOptions.fileExtension(c.info, values)) else null
                val streamBytes = withContext(Dispatchers.IO) { streamFile.length() }
                stage(if (spec.exportSecret != null) R.string.export_protecting else R.string.export_exporting)
                val source = withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(streamFile, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() }
                if (source == null) { failed(R.string.export_failed_title, getString(R.string.export_prepare_failed_body)); return@launch }
                val sink = withContext(Dispatchers.IO) { if (cacheOut != null) openCacheSink(cacheOut) else openDestination(checkNotNull(saf).uri) }
                if (sink == null) { withContext(Dispatchers.IO) { runCatching { source.close() } }; failed(R.string.export_failed_title, getString(R.string.export_destination_body)); return@launch }
                if (cacheOut == null) destinationTouched = true
                val result = try {
                    // The app's own format is already the finished file: Soil only copies it.
                    if (c.appFormat != null) withContext(Dispatchers.IO) { ExportResult(copyThrough(source, sink)) }
                    else ExporterClient(this@ExportActivity, c.extension).export(source, sink, spec)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Slog.d(TAG) { "export call failed: ${e.javaClass.simpleName}: ${e.message}" }
                    failed(R.string.export_failed_title, getString(R.string.export_failed_body))
                    return@launch
                }
                val onDisk = withContext(Dispatchers.IO) { if (cacheOut != null) listOf(cacheOut.length()) else destinationSizes(checkNotNull(saf).uri) }
                when (ExportVerification.verdict(c.info.sourceKind, result.bytesWritten, streamBytes, onDisk)) {
                    ExportVerification.Verdict.SHORT -> {
                        Log.w(TAG, "short export: ${result.bytesWritten} written, $streamBytes streamed, destination $onDisk")
                        failed(R.string.export_failed_title, getString(R.string.export_short_body))
                        return@launch
                    }
                    ExportVerification.Verdict.UNCONFIRMED -> {
                        Log.w(TAG, "destination reports $onDisk for ${result.bytesWritten} bytes")
                        hideProgress()
                        if (!isFinishing && !isDestroyed) Dialogs.problem(this@ExportActivity, R.string.export_verify_title, getString(R.string.export_verify_body))
                        return@launch
                    }
                    ExportVerification.Verdict.OK -> Unit
                }
                Slog.d(TAG) { "exported ${result.bytesWritten} bytes" }
                if (cloudDestination != null) { uploadAndConfirm(c, cloudDestination, checkNotNull(cacheOut)); return@launch }
                prefs.lastExporter = c.extension.packageName
                if (asksPageSize(c)) prefs.lastPageSize = pageSize
                hideProgress()
                if (isFinishing || isDestroyed) return@launch
                Dialogs.confirm(this@ExportActivity, R.string.export_done_title, if (scope is ExportScope.Page) R.string.export_done_page_body else R.string.export_done_body) { finish() }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { ExportArtifact.clean(applicationContext) }
                typedPassphrase = null
                typedExportSecret = null
                busy = false
                hideProgress()
            }
        }
    }

    private sealed class StreamSource {
        class Ready(val file: File, val pageNames: List<ExportNaming.PageName> = emptyList()) : StreamSource()
        class Failed(val message: String) : StreamSource()
    }

    private suspend fun keyedArtifact(c: Candidate): StreamSource {
        val it = item ?: return StreamSource.Failed(getString(R.string.export_missing_body))
        val path = withContext(Dispatchers.IO) { runCatching { LibraryStore().ancestry(it.parentId).map { f -> ExportStamp.Folder(f.id, f.name) } }.getOrDefault(emptyList()) }
        val prepared = ExportArtifact.prepare(applicationContext, itemId, ExportStamp.Stamp(it.name, path))
        if (prepared is ExportArtifact.Outcome.Failed) return StreamSource.Failed(getString(ExportMessages.of(prepared.problem)))
        val artifact = prepared as ExportArtifact.Outcome.Ready
        val plan = try {
            ExportKeying.plan(ExportOptions.keying(c.info, values), typedPassphrase != null)
        } catch (e: IllegalArgumentException) {
            return StreamSource.Failed(getString(R.string.export_passphrase_lost_body))
        }
        if (plan == ExportKeying.Plan.KEEP) return StreamSource.Ready(artifact.file)
        val passphrase = KeySession.get() ?: return StreamSource.Failed(getString(R.string.export_locked_body))
        stage(if (plan == ExportKeying.Plan.REKEY) R.string.export_rekeying else R.string.export_decrypting)
        return try {
            StreamSource.Ready(ExportKeying.apply(artifact.file, passphrase, plan, typedPassphrase))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "keying transform failed: ${e.javaClass.simpleName}")
            StreamSource.Failed(getString(R.string.export_transform_body))
        }
    }

    /** One of the app's own formats, written by the app into the cache. */
    private suspend fun producedByApp(c: Candidate, format: SeamFormat): StreamSource {
        val r = renderer ?: return StreamSource.Failed(getString(R.string.export_no_app_body))
        stage(R.string.export_producing)
        return try {
            StreamSource.Ready(AppRenderers.produce(applicationContext, r, itemId, format.id, pageSize, c.info.fileExtension))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Slog.d(TAG) { "the app refused: ${e.message}" }
            StreamSource.Failed(getString(ExportMessages.ofRender(e.message)))
        } catch (e: Exception) {
            Log.w(TAG, "the app's format failed: ${e.javaClass.simpleName}")
            StreamSource.Failed(getString(R.string.export_render_failed_body))
        }
    }

    /** Every byte of [source] onto [sink], both closed after. Answers how many were written. */
    private fun copyThrough(source: ParcelFileDescriptor, sink: ParcelFileDescriptor): Long {
        var written = 0L
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            ParcelFileDescriptor.AutoCloseOutputStream(sink).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    written += n
                }
                output.flush()
                runCatching { output.fd.sync() }
            }
        }
        return written
    }

    private suspend fun renderedPages(c: Candidate): StreamSource {
        val r = renderer ?: return StreamSource.Failed(getString(R.string.export_no_app_body))
        stage(R.string.export_rendering)
        return try {
            val rendered = AppRenderers.render(applicationContext, r, itemId, scope.pageIds, ExportOptions.includeTemplate(c.info, values), c.info.bundleVersion, if (renderInfo.flowing) pageSize else null)
            StreamSource.Ready(rendered.file, rendered.names)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            Slog.d(TAG) { "render refused: ${e.message}" }
            StreamSource.Failed(getString(ExportMessages.ofRender(e.message)))
        } catch (e: Exception) {
            Log.w(TAG, "render failed: ${e.javaClass.simpleName}")
            StreamSource.Failed(getString(R.string.export_render_failed_body))
        }
    }

    private suspend fun reselectAfterRestore(): Candidate? {
        candidates = loadCandidates()
        if (isFinishing || isDestroyed) return null
        val pick = candidates.firstOrNull { it.extension.packageName == chosenPackage } ?: candidates.takeIf { chosenPackage == null }?.firstOrNull()
        if (pick != null && !isFinishing && !isDestroyed) select(pick, keepValues = true)
        return pick
    }

    /**
     * One file per page into a folder: a SAF tree, or a folder of the provider's tree. Everything
     * about one file is the single-file flow's; this adds the arithmetic. A failure stops, keeps
     * what is already written, removes the failing SAF document (never anything in the cloud) and
     * leads with *N of M images were exported.*
     */
    private suspend fun exportPerPage(c: Candidate, destination: Destination, bundle: File, pageNames: List<ExportNaming.PageName>, specValues: Map<String, String>, secret: String?) {
        val cloud = destination as? Destination.CloudFolder
        val tree = destination as? Destination.SafTree
        val dir = File(cacheDir, ExportArtifact.DIR)
        val parts = withContext(Dispatchers.IO) {
            runCatching { BundleSplit.split(bundle, dir) }.onFailure { Log.w(TAG, "the bundle would not split: ${it.javaClass.simpleName}") }.getOrNull()
        }
        if (parts.isNullOrEmpty()) { stopPerPage(null, 0, 0, getString(R.string.export_render_failed_body), destination); return }
        val total = parts.size
        val extension = ExportOptions.fileExtension(c.info, values)
        val mime = ExportOptions.mimeType(c.info, values)
        val treeRoot = tree?.let { t ->
            runCatching { DocumentsContract.buildDocumentUriUsingTree(t.tree, DocumentsContract.getTreeDocumentId(t.tree)) }
                .onFailure { Log.w(TAG, "the picked folder would not resolve: ${it.javaClass.simpleName}") }.getOrNull()
        }
        if (tree != null && treeRoot == null) { failNothing(R.string.export_failed_title, getString(R.string.export_destination_body)); return }
        val provider = cloudRef
        if (cloud != null && provider == null) { failCloud(R.string.export_failed_title, getString(R.string.export_cloud_gone_body)); return }
        val exporter = try {
            ExporterClient(this@ExportActivity, c.extension).hold()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "exporter bind failed: ${e.javaClass.simpleName}" }
            stopPerPage(null, 0, total, getString(R.string.export_failed_body), destination)
            return
        }
        var written = 0
        try {
            for (index in parts.indices) {
                val part = parts[index]
                val stemName = stemFor(index, pageNames)
                val name = ExportNaming.fileName(stemName, extension)
                stage(getString(R.string.export_exporting_image, index + 1, total))
                val spec = try {
                    ExportSpec(values = specValues, itemName = ExportNaming.specNameOf(stemName), exportSecret = secret)
                } catch (e: IllegalArgumentException) {
                    stopPerPage(null, written, total, getString(R.string.export_failed_body), destination); return
                }
                val partBytes = withContext(Dispatchers.IO) { part.length() }
                val source = withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() }
                if (source == null) { stopPerPage(null, written, total, getString(R.string.export_prepare_failed_body), destination); return }
                val cacheOut = if (cloud != null) File(dir, "out-$index.$extension") else null
                val document = if (treeRoot != null) withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.createDocument(contentResolver, treeRoot, mime, name) }
                        .onFailure { Log.w(TAG, "could not create the destination document: ${it.javaClass.simpleName}") }.getOrNull()
                } else null
                val sink = withContext(Dispatchers.IO) { if (cacheOut != null) openCacheSink(cacheOut) else document?.let { openDestination(it) } }
                if (sink == null) { withContext(Dispatchers.IO) { runCatching { source.close() } }; stopPerPage(document, written, total, getString(R.string.export_destination_body), destination); return }
                val result = try {
                    exporter.export(source, sink, spec)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Slog.d(TAG) { "export call failed: ${e.javaClass.simpleName}: ${e.message}" }
                    stopPerPage(document, written, total, getString(R.string.export_failed_body), destination); return
                }
                val onDisk = withContext(Dispatchers.IO) { if (cacheOut != null) listOf(cacheOut.length()) else destinationSizes(checkNotNull(document)) }
                when (ExportVerification.verdict(c.info.sourceKind, result.bytesWritten, partBytes, onDisk)) {
                    ExportVerification.Verdict.SHORT -> { stopPerPage(document, written, total, getString(R.string.export_short_body), destination); return }
                    ExportVerification.Verdict.UNCONFIRMED -> {
                        hideProgress()
                        if (!isFinishing && !isDestroyed) Dialogs.problem(this@ExportActivity, R.string.export_verify_title, partialPrefix(written, total) + getString(R.string.export_verify_body))
                        return
                    }
                    ExportVerification.Verdict.OK -> Unit
                }
                if (cloud != null) {
                    if (!uploadOne(checkNotNull(provider), cloud.path, name, mime, checkNotNull(cacheOut), prefix = partialPrefix(written, total))) return
                    withContext(Dispatchers.IO) { cacheOut.delete() }
                }
                written++
            }
        } finally {
            exporter.close()
        }
        prefs.lastExporter = c.extension.packageName
        if (asksPageSize(c)) prefs.lastPageSize = pageSize
        hideProgress()
        if (isFinishing || isDestroyed) return
        val body = if (cloud != null) resources.getQuantityString(R.plurals.export_cloud_done_images, written, written, cloudName())
        else resources.getQuantityString(R.plurals.export_done_images, written, written)
        Dialogs.confirm(this@ExportActivity, R.string.export_done_title, body) { finish() }
    }

    private suspend fun stopPerPage(document: Uri?, written: Int, total: Int, message: String, destination: Destination) {
        val body = partialPrefix(written, total) + message
        when {
            document != null -> fail(document, R.string.export_failed_title, body, mayDelete = true)
            destination is Destination.CloudFolder -> {
                hideProgress()
                if (isFinishing || isDestroyed) return
                Dialogs.problem(this, R.string.export_failed_title, if (written == 0) "$body ${getString(R.string.export_cloud_untouched_note)}" else body)
            }
            else -> failNothing(R.string.export_failed_title, body)
        }
    }

    private fun partialPrefix(written: Int, total: Int): String = resources.getQuantityString(R.plurals.export_done_images_partial, written, written, total) + " "

    private fun failNothing(@StringRes titleRes: Int, message: String) {
        hideProgress()
        if (isFinishing || isDestroyed) return
        Dialogs.problem(this, titleRes, "$message ${getString(R.string.export_untouched_note)}")
    }

    private fun openDestination(uri: Uri): ParcelFileDescriptor? {
        for (mode in arrayOf("rwt", "wt", "w")) {
            val pfd = runCatching { contentResolver.openFileDescriptor(uri, mode) }.getOrNull()
            if (pfd != null) return pfd
        }
        Log.w(TAG, "could not open the destination for writing")
        return null
    }

    private fun destinationSizes(uri: Uri): List<Long> {
        val sizes = ArrayList<Long>(2)
        runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) sizes += c.getLong(0) } }
        runCatching { contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> pfd.statSize.takeIf { it >= 0L }?.let { sizes += it } } }
        return sizes
    }

    private fun showBusyGuard() = Dialogs.problem(this, R.string.export_busy_title, R.string.export_busy_body)

    private suspend fun fail(uri: Uri, @StringRes titleRes: Int, message: String, mayDelete: Boolean) {
        hideProgress()
        val removed = if (mayDelete) withContext(Dispatchers.IO) {
            runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.onFailure { Log.w(TAG, "could not remove the partial export: ${it.javaClass.simpleName}") }.getOrDefault(false)
        } else false
        if (isFinishing || isDestroyed) return
        val note = getString(when { removed -> R.string.export_removed_note; mayDelete -> R.string.export_remains_note; else -> R.string.export_untouched_note })
        Dialogs.problem(this, titleRes, "$message $note")
    }

    private fun problemAndClose(@StringRes titleRes: Int, @StringRes bodyRes: Int) {
        if (isFinishing || isDestroyed) { finish(); return }
        Dialogs.style(AlertDialog.Builder(this).setTitle(titleRes).setMessage(bodyRes).setPositiveButton(com.symmetricalpalmtree.soil.paper.R.string.ok, null).create())
            .also { it.setOnDismissListener { finish() } }.show()
    }

    companion object {
        /** The reference that names one of the app's own formats: never a package. */
        private const val APP_FORMAT_PREFIX = "app:"
        private const val TAG = "ExportActivity"
        private const val KEY_PACKAGE = "export.package"
        private const val KEY_VALUES = "export.values"
        private const val KEY_SCOPE_WHOLE = "export.scopeWhole"
        private const val KEY_DESTINATION = "export.cloud"

        /** Render-only: the pages of [kind] under [key], named [name]. */
        fun renderIntent(context: Context, kind: String, key: String, name: String): Intent =
            Intent(context, ExportActivity::class.java)
                .putExtra(Seam.EXTRA_RENDER_KIND, kind)
                .putExtra(Seam.EXTRA_RENDER_KEY, key)
                .putExtra(Seam.EXTRA_RENDER_NAME, name)

        fun intent(context: Context, itemId: String, pageId: String? = null, returnToApp: Boolean = false): Intent =
            Intent(context, ExportActivity::class.java)
                .putExtra(Seam.EXTRA_ITEM_ID, itemId)
                .putExtra(Seam.EXTRA_PAGE_ID, pageId)
                .putExtra(Seam.EXTRA_RETURN_TO_APP, returnToApp)
    }
}
