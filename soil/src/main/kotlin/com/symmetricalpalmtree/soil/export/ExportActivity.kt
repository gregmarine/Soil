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
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.Item
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityExportBinding
import com.symmetricalpalmtree.soil.ext.ExportContract
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
 */
class ExportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExportBinding
    private lateinit var panel: ExportPanel
    private val prefs by lazy { SettingsPrefs(this) }

    private lateinit var itemId: String
    private var item: Item? = null
    private var pageId: String? = null
    private var returnToApp = false
    private var relaunched = false
    private var scope: ExportScope = ExportScope.Whole
    private var renderer: ComponentName? = null

    private class PageFacts(val number: Int, val title: String?)
    private var pageFacts: PageFacts? = null

    private class Candidate(val extension: Extension, val info: ExporterInfo)
    private var described: List<Candidate> = emptyList()
    private var candidates: List<Candidate> = emptyList()
    private var chosenPackage: String? = null
    private val values = LinkedHashMap<String, String>()

    private var busy = false
    private var discovering = false
    private var typedPassphrase: String? = null
    private var typedExportSecret: String? = null

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
        itemId = intent.getStringExtra(Seam.EXTRA_ITEM_ID).orEmpty()
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

        savedInstanceState?.let { state ->
            chosenPackage = state.getString(KEY_PACKAGE)
            if (state.getBoolean(KEY_SCOPE_WHOLE)) scope = ExportScope.Whole
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
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PACKAGE, chosenPackage)
        outState.putBoolean(KEY_SCOPE_WHOLE, scope is ExportScope.Whole)
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
        val found = withContext(Dispatchers.IO) { IndexStore().aliveItem(itemId) }
        if (found == null) { if (!isFinishing) problemAndClose(R.string.export_failed_title, R.string.export_missing_body); return emptyList() }
        item = found
        binding.itemName.text = found.name
        if (renderer == null) renderer = withContext(Dispatchers.IO) { AppRenderers.find(this@ExportActivity, found.kind) }
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
            if (info.sourceKind == ExportContract.SOURCE_PAGES && renderer == null) { Slog.d(TAG) { "dropping ${ref.packageName}: no renderer for ${found.kind}" }; continue }
            kept += Candidate(ref, info)
        }
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
        for (d in c.info.options) {
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

    private fun stem(): String {
        val name = item?.name.orEmpty()
        return when (scope) {
            ExportScope.Whole -> ExportNaming.base(name, itemId)
            is ExportScope.Page -> ExportNaming.pageStem(name, itemId, pageFacts?.number ?: 0, pageFacts?.title)
        }
    }

    private fun perPage(c: Candidate): Boolean = ExportDelivery.perPage(c.info.delivery, scope)

    private fun stemFor(index: Int, pageNames: List<ExportNaming.PageName>): String {
        val name = pageNames.getOrNull(index)
        return ExportNaming.pageStem(item?.name.orEmpty(), itemId, name?.number ?: (index + 1), name?.title)
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
            suspend fun failed(@StringRes titleRes: Int, message: String) =
                if (saf != null) fail(saf.uri, titleRes, message, mayDelete = destinationTouched || emptyAtStart) else failNothing(titleRes, message)
            try {
                val c = current() ?: reselectAfterRestore()
                if (c == null) { failed(R.string.export_failed_title, getString(R.string.export_gone_body)); return@launch }
                val wantsSecret = ExportOptions.wantsExportSecret(c.info, values)
                val armedAtTap = values[ExportContract.OPTION_PROTECT] == "1"
                if ((wantsSecret || armedAtTap) && (typedExportSecret == null || !wantsSecret)) { failed(R.string.export_failed_title, getString(R.string.export_password_lost_body)); return@launch }
                val specValues = ExportOptions.specValues(c.info, values)
                val perPage = destination is Destination.SafTree
                val spec = if (perPage) null else try {
                    ExportSpec(values = specValues, itemName = ExportNaming.specNameOf(stem()), exportSecret = if (wantsSecret) typedExportSecret else null)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "spec rejected: ${e.javaClass.simpleName}")
                    failed(R.string.export_failed_title, getString(R.string.export_failed_body))
                    return@launch
                }
                val prepared = if (c.info.sourceKind == ExportContract.SOURCE_PAGES) renderedPages(c) else keyedArtifact(c)
                val streamFile = when (prepared) {
                    is StreamSource.Failed -> { failed(R.string.export_failed_title, prepared.message); return@launch }
                    is StreamSource.Ready -> prepared.file
                }
                if (perPage) {
                    exportPerPage(c, destination as Destination.SafTree, streamFile, (prepared as StreamSource.Ready).pageNames, specValues, if (wantsSecret) typedExportSecret else null)
                    return@launch
                }
                checkNotNull(spec)
                checkNotNull(saf)
                val streamBytes = withContext(Dispatchers.IO) { streamFile.length() }
                stage(if (spec.exportSecret != null) R.string.export_protecting else R.string.export_exporting)
                val source = withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(streamFile, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() }
                if (source == null) { failed(R.string.export_failed_title, getString(R.string.export_prepare_failed_body)); return@launch }
                val sink = withContext(Dispatchers.IO) { openDestination(saf.uri) }
                if (sink == null) { withContext(Dispatchers.IO) { runCatching { source.close() } }; failed(R.string.export_failed_title, getString(R.string.export_destination_body)); return@launch }
                destinationTouched = true
                val result = try {
                    ExporterClient(this@ExportActivity, c.extension).export(source, sink, spec)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Slog.d(TAG) { "export call failed: ${e.javaClass.simpleName}: ${e.message}" }
                    failed(R.string.export_failed_title, getString(R.string.export_failed_body))
                    return@launch
                }
                val onDisk = withContext(Dispatchers.IO) { destinationSizes(saf.uri) }
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
                prefs.lastExporter = c.extension.packageName
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

    private suspend fun renderedPages(c: Candidate): StreamSource {
        val r = renderer ?: return StreamSource.Failed(getString(R.string.export_no_app_body))
        stage(R.string.export_rendering)
        return try {
            val rendered = AppRenderers.render(applicationContext, r, itemId, scope.pageIds, ExportOptions.includeTemplate(c.info, values), c.info.bundleVersion)
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

    private suspend fun exportPerPage(c: Candidate, destination: Destination.SafTree, bundle: File, pageNames: List<ExportNaming.PageName>, specValues: Map<String, String>, secret: String?) {
        val dir = File(cacheDir, ExportArtifact.DIR)
        val parts = withContext(Dispatchers.IO) {
            runCatching { BundleSplit.split(bundle, dir) }.onFailure { Log.w(TAG, "the bundle would not split: ${it.javaClass.simpleName}") }.getOrNull()
        }
        if (parts.isNullOrEmpty()) { failNothing(R.string.export_failed_title, getString(R.string.export_render_failed_body)); return }
        val total = parts.size
        val extension = ExportOptions.fileExtension(c.info, values)
        val mime = ExportOptions.mimeType(c.info, values)
        val treeRoot = runCatching { DocumentsContract.buildDocumentUriUsingTree(destination.tree, DocumentsContract.getTreeDocumentId(destination.tree)) }
            .onFailure { Log.w(TAG, "the picked folder would not resolve: ${it.javaClass.simpleName}") }.getOrNull()
        if (treeRoot == null) { failNothing(R.string.export_failed_title, getString(R.string.export_destination_body)); return }
        val exporter = try {
            ExporterClient(this@ExportActivity, c.extension).hold()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "exporter bind failed: ${e.javaClass.simpleName}" }
            failNothing(R.string.export_failed_title, partialPrefix(0, total) + getString(R.string.export_failed_body))
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
                    failNothing(R.string.export_failed_title, partialPrefix(written, total) + getString(R.string.export_failed_body)); return
                }
                val partBytes = withContext(Dispatchers.IO) { part.length() }
                val source = withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() }
                if (source == null) { failNothing(R.string.export_failed_title, partialPrefix(written, total) + getString(R.string.export_prepare_failed_body)); return }
                val document = withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.createDocument(contentResolver, treeRoot, mime, name) }
                        .onFailure { Log.w(TAG, "could not create the destination document: ${it.javaClass.simpleName}") }.getOrNull()
                }
                val sink = withContext(Dispatchers.IO) { document?.let { openDestination(it) } }
                if (sink == null) { withContext(Dispatchers.IO) { runCatching { source.close() } }; stopPerPage(document, written, total, getString(R.string.export_destination_body)); return }
                val result = try {
                    exporter.export(source, sink, spec)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Slog.d(TAG) { "export call failed: ${e.javaClass.simpleName}: ${e.message}" }
                    stopPerPage(document, written, total, getString(R.string.export_failed_body)); return
                }
                val onDisk = withContext(Dispatchers.IO) { destinationSizes(document!!) }
                when (ExportVerification.verdict(c.info.sourceKind, result.bytesWritten, partBytes, onDisk)) {
                    ExportVerification.Verdict.SHORT -> { stopPerPage(document, written, total, getString(R.string.export_short_body)); return }
                    ExportVerification.Verdict.UNCONFIRMED -> {
                        hideProgress()
                        if (!isFinishing && !isDestroyed) Dialogs.problem(this@ExportActivity, R.string.export_verify_title, partialPrefix(written, total) + getString(R.string.export_verify_body))
                        return
                    }
                    ExportVerification.Verdict.OK -> Unit
                }
                written++
            }
        } finally {
            exporter.close()
        }
        prefs.lastExporter = c.extension.packageName
        hideProgress()
        if (isFinishing || isDestroyed) return
        Dialogs.confirm(this@ExportActivity, R.string.export_done_title, resources.getQuantityString(R.plurals.export_done_images, written, written)) { finish() }
    }

    private suspend fun stopPerPage(document: Uri?, written: Int, total: Int, message: String) {
        val body = partialPrefix(written, total) + message
        if (document != null) fail(document, R.string.export_failed_title, body, mayDelete = true) else failNothing(R.string.export_failed_title, body)
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
        private const val TAG = "ExportActivity"
        private const val KEY_PACKAGE = "export.package"
        private const val KEY_VALUES = "export.values"
        private const val KEY_SCOPE_WHOLE = "export.scopeWhole"

        fun intent(context: Context, itemId: String, pageId: String? = null, returnToApp: Boolean = false): Intent =
            Intent(context, ExportActivity::class.java)
                .putExtra(Seam.EXTRA_ITEM_ID, itemId)
                .putExtra(Seam.EXTRA_PAGE_ID, pageId)
                .putExtra(Seam.EXTRA_RETURN_TO_APP, returnToApp)
    }
}
