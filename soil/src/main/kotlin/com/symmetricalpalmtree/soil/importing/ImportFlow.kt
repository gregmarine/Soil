package com.symmetricalpalmtree.soil.importing

import android.app.Activity
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.data.item.ItemFiles
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.cloud.CloudBrowserDialog
import com.symmetricalpalmtree.soil.cloud.CloudClient
import com.symmetricalpalmtree.soil.cloud.CloudConnectEntry
import com.symmetricalpalmtree.soil.cloud.CloudNetworkFailed
import com.symmetricalpalmtree.soil.cloud.CloudNotConnected
import com.symmetricalpalmtree.soil.crypto.AttemptLimiter
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.crypto.SoilFileKind
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.export.AppRenderers
import com.symmetricalpalmtree.soil.export.ExportDestination
import com.symmetricalpalmtree.soil.ext.CloudEntry
import com.symmetricalpalmtree.soil.ext.CloudStatus
import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.ext.Extensions
import com.symmetricalpalmtree.soil.ext.ImportSpec
import com.symmetricalpalmtree.soil.ext.ImporterClient
import com.symmetricalpalmtree.soil.ext.ImporterInfo
import com.symmetricalpalmtree.soil.library.FolderPickerActivity
import com.symmetricalpalmtree.soil.library.LibraryFiles
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * **An item into the library.** The home screen's Import button opens the document picker; the
 * importer that takes the picked name streams it into Soil's cache; then Soil probes it, unlocks
 * it, keys it to this device, reads what it says of itself, asks its three questions (the same
 * item already here, where to put it, the same name already there) and only then writes: the
 * file into the garden, the row into the index, and whatever Replace retires, last.
 *
 * With a cloud provider installed the tap first asks *Import from*: this device, or the
 * provider, whose files the browser lists from its root. A cloud file is downloaded into the
 * same cache and goes through the same importer and the same questions; nothing is downloaded
 * before an importer has accepted the name.
 */
class ImportFlow(
    private val activity: AppCompatActivity,
    private val currentFolder: () -> String,
    private val onImported: suspend () -> Unit,
    private val onInstalledChanged: () -> Unit = {},
) {

    /** Whether an importer is installed, as last looked; the host shows the button by it. */
    var installed: Boolean = false
        private set

    /**
     * One thing a picked file can be imported as. An extension's importer, or with [app] the
     * item's own app taking the file in as a new item of its kind: then there is no extension
     * behind it, and the reference only names it.
     */
    private class Candidate(val extension: Extension, val info: ImporterInfo, val app: AppImporter? = null)
    private class AppImporter(val kind: String, val renderer: ComponentName)

    private var candidates: List<Candidate> = emptyList()
    private var isBusy = false
    var isImporting: Boolean = false
        private set
    private var discovering = false
    private var folderPick: CompletableDeferred<String?>? = null

    // The cloud source: the connect door, the provider as last found and what it said, the
    // browser while it is up, and whether a sign-in was opened from here.
    private val cloud = CloudConnectEntry(activity) { wasConnected -> onConnectResult(wasConnected) }
    private var cloudRef: Extension? = null
    private var cloudStatus: CloudStatus? = null
    private var browser: CloudBrowserDialog? = null
    private var connectPending = false

    private val openLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) runImport(Origin.Document(uri)) else { isBusy = false; Slog.d(TAG) { "document picker cancelled" } }
    }

    private val folderLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = folderPick
        folderPick = null
        if (pending == null) {
            if (result.resultCode == Activity.RESULT_OK) Dialogs.problem(activity, R.string.import_failed_title, activity.getString(R.string.import_interrupted_body))
            return@registerForActivityResult
        }
        pending.complete(if (result.resultCode == Activity.RESULT_OK) result.data?.getStringExtra(FolderPickerActivity.EXTRA_PICKED_FOLDER).orEmpty() else null)
    }

    /** Looked for again at every showing of the host; the answer never changes under a running import. */
    fun refresh() {
        if (discovering) return
        discovering = true
        activity.lifecycleScope.launch {
            val found = try { withContext(Dispatchers.IO) { Extensions.importers(activity) }.isNotEmpty() || appImporters().isNotEmpty() } finally { discovering = false }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (!isImporting && found != installed) { installed = found; onInstalledChanged() }
        }
    }

    fun showBusyGuard() = Dialogs.problem(activity, R.string.import_busy_title, R.string.import_busy_body)

    /** From the host's `onDestroy`: a sign-in's bind must not outlive the screen that opened it. */
    fun close() {
        browser?.dismiss()
        browser = null
        cloud.close()
    }

    fun onTap() {
        if (isImporting) { showBusyGuard(); return }
        if (isBusy) return
        isBusy = true
        activity.lifecycleScope.launch {
            var handed = false
            try {
                val cands = loadCandidates().also { candidates = it }
                if (cands.isEmpty()) { problem(R.string.import_none_title, activity.getString(R.string.import_none_body)); refresh(); return@launch }
                loadCloud()
                if (activity.isFinishing || activity.isDestroyed) return@launch
                val cloudInstalled = cloudRef != null
                if (!ImportSource.asksSource(cloudInstalled)) { handed = launchDocumentPicker(cands); return@launch }
                val answer = ImportDialogs.pickFromList(activity, R.string.import_source_title, listOf(activity.getString(R.string.import_source_device), cloudName()))
                val source = answer?.let { ImportSource.sourceAt(it, cloudInstalled) } ?: return@launch
                handed = when (source) {
                    ImportSource.Source.LOCAL -> launchDocumentPicker(cands)
                    ImportSource.Source.CLOUD -> onCloudSourceChosen()
                }
            } finally {
                if (!handed) isBusy = false
            }
        }
    }

    /** True when the picker is up and owns the latch. */
    private fun launchDocumentPicker(cands: List<Candidate>): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(ImporterMatch.ANY_TYPE)
            .putExtra(Intent.EXTRA_MIME_TYPES, ImporterMatch.mimeFilter(cands.map { it.info.mimeTypes }))
        return try {
            openLauncher.launch(intent); true
        } catch (e: Exception) {
            Log.w(TAG, "no document picker: ${e.javaClass.simpleName}")
            problem(R.string.import_no_picker_title, activity.getString(R.string.import_no_picker_body))
            false
        }
    }

    // ── The cloud source ──────

    private suspend fun loadCloud() {
        val ref = cloud.discover()
        cloudRef = ref
        cloudStatus = if (ref == null) null else try {
            CloudClient.status(activity, ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "cloud status unavailable: ${e.javaClass.simpleName}" }
            null
        }
    }

    private fun cloudName(): String = ExportDestination.providerName(cloudStatus, cloudRef?.label.orEmpty())

    /** The Export screen's rule, reused: connected opens the browser; a build without credentials says so; anything else offers Connect. True when the latch was handed on. */
    private fun onCloudSourceChosen(): Boolean = when (ExportDestination.onCloudTap(cloudStatus)) {
        ExportDestination.Tap.SELECT -> { openCloudBrowser(); true }
        ExportDestination.Tap.NOT_CONFIGURED -> { Dialogs.problem(activity, R.string.cloud_not_configured_title, R.string.cloud_not_configured_body); false }
        ExportDestination.Tap.OFFER_CONNECT -> offerConnect()
    }

    private fun offerConnect(): Boolean {
        if (!cloud.isAvailable || activity.isFinishing || activity.isDestroyed) return false
        val name = cloudName()
        var connecting = false
        val dialog = Dialogs.style(
            AlertDialog.Builder(activity).setTitle(activity.getString(R.string.cloud_connect_offer_title, name)).setMessage(activity.getString(R.string.import_cloud_connect_offer_body, name))
                .setPositiveButton(R.string.cloud_connect) { _, _ -> connecting = true; connectPending = true; cloud.open() }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
        )
        dialog.setOnDismissListener { if (!connecting) isBusy = false }
        dialog.show()
        return true
    }

    /** The sign-in came back: a connected account continues into the browser; anything else lets the latch go. */
    private fun onConnectResult(wasConnected: Boolean) {
        if (!connectPending) return
        connectPending = false
        if (!wasConnected) { isBusy = false; return }
        activity.lifecycleScope.launch {
            loadCloud()
            if (activity.isFinishing || activity.isDestroyed) { isBusy = false; return@launch }
            if (cloudRef != null && cloudStatus?.connected == true) openCloudBrowser() else isBusy = false
        }
    }

    /** The browser over the provider's root, nothing filtered: which importer reads the file is decided afterwards, by its name. */
    private fun openCloudBrowser() {
        val ref = cloudRef
        if (ref == null) { isBusy = false; cloudProblem(CloudImportFailure.Kind.GONE); return }
        browser?.dismiss()
        val dialog = CloudBrowserDialog(
            activity = activity,
            ref = ref,
            providerName = cloudName(),
            mode = CloudBrowserDialog.Mode.PICK_FILE,
            basePath = emptyList(),
            onPicked = { pick ->
                browser = null
                when (pick) {
                    is CloudBrowserDialog.Pick.File -> runImport(Origin.Cloud(ref, pick.entry))
                    is CloudBrowserDialog.Pick.Folder -> isBusy = false
                }
            },
            onNotConnected = {
                browser = null
                activity.lifecycleScope.launch {
                    loadCloud()
                    if (activity.isFinishing || activity.isDestroyed) { isBusy = false; return@launch }
                    if (!offerConnect()) isBusy = false
                }
            },
            onCancelled = { browser = null; isBusy = false; Slog.d(TAG) { "cloud browser cancelled" } },
        )
        browser = dialog
        dialog.show()
    }

    private fun cloudProblem(kind: CloudImportFailure.Kind) {
        ImportOverlay.hide(activity)
        if (activity.isFinishing || activity.isDestroyed) return
        val name = cloudName()
        when (kind) {
            CloudImportFailure.Kind.GONE -> Dialogs.problem(activity, R.string.import_failed_title, activity.getString(R.string.import_cloud_gone_body))
            CloudImportFailure.Kind.NETWORK -> Dialogs.problem(activity, R.string.import_failed_title, activity.getString(R.string.import_cloud_network_body, name))
            CloudImportFailure.Kind.UNANSWERED -> Dialogs.problem(activity, R.string.import_failed_title, activity.getString(R.string.import_cloud_unanswered_body, name))
            CloudImportFailure.Kind.NOT_CONNECTED -> Dialogs.style(
                AlertDialog.Builder(activity).setTitle(R.string.import_failed_title).setMessage(activity.getString(R.string.import_cloud_not_connected_body, name))
                    .setPositiveButton(R.string.cloud_connect) { _, _ -> isBusy = true; connectPending = true; cloud.open() }
                    .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
            ).show()
        }
    }

    /** Where the bytes come from: the one thing a cloud import and a picked document do not share. */
    private sealed class Origin {
        class Document(val uri: Uri) : Origin()
        class Cloud(val ref: Extension, val entry: CloudEntry) : Origin()
    }

    /** The provider streams the file into the import cache; what landed, what it reported and what the listing said are corroborated. */
    private suspend fun download(origin: Origin.Cloud, incoming: File): File {
        ImportOverlay.stage(activity, activity.getString(R.string.import_stage_downloading, cloudName()))
        val file = File(incoming.parentFile, CLOUD_FILE)
        val destination = withContext(Dispatchers.IO) {
            runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE) }.getOrNull()
        } ?: throw ItemImport.ImportProblem(ItemImport.Problem.WRITE)
        val reported = try {
            CloudClient.download(activity, origin.ref, origin.entry.id, destination)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloudNotConnected) {
            throw CloudImportFailure(CloudImportFailure.Kind.NOT_CONNECTED, e)
        } catch (e: CloudNetworkFailed) {
            throw CloudImportFailure(CloudImportFailure.Kind.NETWORK, e)
        } catch (e: Exception) {
            throw CloudImportFailure(CloudImportFailure.Kind.UNANSWERED, e)
        }
        val landed = withContext(Dispatchers.IO) { file.length() }
        when (CloudImportRules.downloadVerdict(reported, landed, origin.entry.sizeBytes)) {
            CloudImportRules.Verdict.SHORT -> { Log.w(TAG, "download landed $landed of $reported reported (${origin.entry.sizeBytes} listed) bytes"); throw ItemImport.ImportProblem(ItemImport.Problem.SHORT) }
            CloudImportRules.Verdict.DISAGREE -> Log.w(TAG, "the listing said ${origin.entry.sizeBytes} for $landed downloaded bytes")
            CloudImportRules.Verdict.OK -> Unit
        }
        Slog.d(TAG) { "downloaded $landed bytes" }
        return file
    }

    private suspend fun loadCandidates(): List<Candidate> {
        val refs = withContext(Dispatchers.IO) { Extensions.importers(activity) }
        val kept = ArrayList<Candidate>(refs.size)
        for (ref in refs) {
            val info = try {
                ImporterClient(activity, ref).describe()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Slog.d(TAG) { "dropping ${ref.packageName}: describe failed (${e.javaClass.simpleName})" }
                null
            } ?: continue
            kept += Candidate(ref, info)
        }
        kept += appImporters()
        return kept
    }

    /**
     * The apps that take files in as new items of their kind (a document from a `.md`). Each
     * renderer is asked what it is; one that does not say, or names its files in a way the
     * descriptor refuses, takes nothing in.
     */
    private suspend fun appImporters(): List<Candidate> {
        val renderers = withContext(Dispatchers.IO) { AppRenderers.all(activity) }
        val kept = ArrayList<Candidate>()
        for ((kind, renderer) in renderers) {
            val said = AppRenderers.describe(activity, renderer)
            if (said.importExtensions.isEmpty()) continue
            val info = runCatching { ImporterInfo(said.importLabel, said.importExtensions, said.importMimeTypes) }.getOrNull()
            if (info == null) { Slog.d(TAG) { "dropping an app importer: its descriptor was refused" }; continue }
            kept += Candidate(Extension("app:$kind", "", said.importLabel), info, AppImporter(kind, renderer))
        }
        return kept
    }

    private fun runImport(origin: Origin) {
        isBusy = true
        isImporting = true
        ImportOverlay.show(activity, R.string.import_stage_reading)
        activity.lifecycleScope.launch {
            try {
                import(origin)
            } catch (e: ItemImport.ImportProblem) {
                Slog.d(TAG) { "import problem: ${e.problem}" }
                problem(R.string.import_failed_title, activity.getString(problemBody(e.problem)))
            } catch (e: CloudImportFailure) {
                Slog.d(TAG) { "cloud import failed: ${e.kind}" }
                cloudProblem(e.kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "import failed: ${e.javaClass.simpleName}")
                problem(R.string.import_failed_title, activity.getString(R.string.import_generic_body))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { ItemImport.clean(activity) }
                isBusy = false
                isImporting = false
                folderPick = null
                ImportOverlay.hide(activity)
            }
        }
    }

    private suspend fun import(origin: Origin) {
        val displayName = when (origin) {
            is Origin.Document -> withContext(Dispatchers.IO) { displayNameOf(origin.uri) }
            is Origin.Cloud -> origin.entry.name
        }
        val cands = candidates.ifEmpty { loadCandidates().also { candidates = it } }
        if (cands.isEmpty()) { problem(R.string.import_none_title, activity.getString(R.string.import_none_body)); return }
        val chosen = chooseImporter(cands, displayName) ?: return
        val incoming = withContext(Dispatchers.IO) { ItemImport.prepareCache(activity) }
        if (chosen.app != null) { importIntoApp(chosen.app, origin, incoming, displayName); return }
        when (origin) {
            is Origin.Document -> {
                val sizes = withContext(Dispatchers.IO) { sourceSizes(origin.uri) }
                val source = withContext(Dispatchers.IO) { runCatching { activity.contentResolver.openFileDescriptor(origin.uri, "r") }.getOrNull() }
                    ?: throw ItemImport.ImportProblem(ItemImport.Problem.DELIVERY)
                deliver(chosen, source, sizes, incoming, displayName)
            }
            is Origin.Cloud -> {
                val fetched = download(origin, incoming)
                val source = withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(fetched, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() }
                    ?: throw ItemImport.ImportProblem(ItemImport.Problem.DELIVERY)
                deliver(chosen, source, listOf(withContext(Dispatchers.IO) { fetched.length() }), incoming, displayName)
            }
        }

        val global = KeySession.get() ?: throw ItemImport.ImportProblem(ItemImport.Problem.NO_KEY)
        val opening = unlock(incoming, global) ?: return
        ImportOverlay.stage(activity, R.string.import_stage_keying)
        val keyed = try {
            ImportKeying.toGlobal(incoming, opening, global)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "import keying failed: ${e.javaClass.simpleName}")
            throw ItemImport.ImportProblem(ItemImport.Problem.KEYING, e)
        }
        val manifest = ItemImport.readManifest(keyed, global)
        val renderer = withContext(Dispatchers.IO) { AppRenderers.find(activity, manifest.kind) } ?: throw ItemImport.ImportProblem(ItemImport.Problem.NO_APP)
        val name = ImportNames.itemName(manifest.name, displayName)

        val identity = resolveIdentity(manifest) ?: return
        val landing = if (identity.placementDecided) Landing(identity.parentId) else placement(manifest) ?: return
        val naming = resolveName(name, landing.parentId, identity.itemId, identity.keepBothChosen) ?: return

        ImportOverlay.stage(activity, R.string.import_stage_importing)
        val oldId = manifest.rawId
        if (oldId != null && oldId != identity.itemId) {
            val statements = runCatching { AppRenderers.relabelStatements(activity, renderer, oldId, identity.itemId) }
                .getOrElse { throw ItemImport.ImportProblem(ItemImport.Problem.NO_APP, it) }
            ItemImport.relabel(keyed, global, oldId, identity.itemId, statements)
        }
        ItemImport.placeInGarden(activity, keyed, identity.itemId)
        var parentId = landing.parentId
        withContext(Dispatchers.IO) {
            val library = LibraryStore()
            for (create in landing.create) {
                if (!library.createFolderWithId(create.id, create.name, create.parentId)) { Slog.d(TAG) { "folder id taken under us: landing one level up" }; parentId = create.parentId; break }
            }
            val index = IndexStore()
            val now = System.currentTimeMillis()
            if (index.aliveItem(identity.itemId) != null) {
                // Replace: the row stays, under the imported name, moved to where it was.
                index.rename(identity.itemId, naming.name, now)
            } else {
                index.insert(identity.itemId, manifest.kind, naming.name, now, parentId)
            }
            naming.retireId?.let { retire(it) }
        }
        ImportOverlay.stage(activity, R.string.import_stage_finishing)
        ImportOverlay.hide(activity)
        onImported()
        confirmImported(parentId)
    }

    /**
     * A file an app takes in: it becomes a new item of the app's kind, in the folder the library
     * is showing, named after the file (with the question every import asks when that name is
     * taken there). The bytes land in the cache by Soil's own hand (nothing
     * of them is an item yet, so there is no key to find and nothing to ask), the item is made
     * empty as New makes one, and the app writes the file into it. An app that refuses the file
     * leaves nothing behind: the empty item is taken away again.
     */
    private suspend fun importIntoApp(app: AppImporter, origin: Origin, incoming: File, displayName: String) {
        if (KeySession.get() == null) throw ItemImport.ImportProblem(ItemImport.Problem.NO_KEY)
        val fetched = when (origin) {
            is Origin.Cloud -> download(origin, incoming)
            is Origin.Document -> withContext(Dispatchers.IO) {
                val copied = runCatching {
                    activity.contentResolver.openInputStream(origin.uri)?.use { input -> incoming.outputStream().use { output -> input.copyTo(output) } }
                }.getOrNull() ?: throw ItemImport.ImportProblem(ItemImport.Problem.DELIVERY)
                if (sourceSizes(origin.uri).any { it > copied }) throw ItemImport.ImportProblem(ItemImport.Problem.SHORT)
                incoming
            }
        }
        val parentId = currentFolder()
        val id = UUID.randomUUID().toString()
        // The same question a .soil import asks of a name already in the folder: Replace, or
        // Keep both under "X Copy". Asked before anything is made, so Cancel leaves nothing.
        val naming = resolveName(ImportNames.fromDisplayName(displayName), parentId, id, keepBothChosen = false) ?: return
        val name = naming.name
        ImportOverlay.stage(activity, R.string.import_stage_importing)
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // The file first: a row with no file is an item that cannot be opened.
            ItemFiles.createEmpty(activity, id, name, now, app.kind)
            IndexStore().insert(id, app.kind, name, now, parentId)
        }
        try {
            AppRenderers.ingest(activity, app.renderer, id, ImporterMatch.extensionOf(displayName), fetched)
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { retire(id) }
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "the app would not take the file: ${e.javaClass.simpleName}" }
            withContext(NonCancellable + Dispatchers.IO) { retire(id) }
            problem(
                R.string.import_failed_title,
                activity.getString(
                    when (e.message) {
                        Seam.INGEST_NOT_TEXT -> R.string.import_not_text_body
                        Seam.INGEST_TOO_LARGE -> R.string.import_too_large_body
                        else -> R.string.import_generic_body
                    },
                ),
            )
            return
        }
        // What Replace stands in for goes last, once the new item holds its words: a file the
        // app refused has replaced nothing.
        naming.retireId?.let { old -> withContext(Dispatchers.IO) { retire(old) } }
        ItemSessions.changed()
        ImportOverlay.hide(activity)
        onImported()
        confirmImported(parentId)
    }

    /** The library's own delete, for the item a Replace stands in for. */
    private fun retire(id: String) {
        if (LibraryStore().deleteItem(id)) LibraryFiles.deleteItemFile(activity, id)
    }

    private suspend fun chooseImporter(cands: List<Candidate>, displayName: String): Candidate? {
        val matches = ImporterMatch.matching(cands.map { it.info.fileExtensions }, displayName)
        return when {
            matches.isEmpty() -> { problem(R.string.import_unsupported_title, activity.getString(R.string.import_unsupported_body)); null }
            matches.size == 1 -> cands[matches.first()]
            else -> {
                val pick = ImportDialogs.pickFromList(activity, R.string.import_format_title, matches.map { cands[it].info.formatLabel }) ?: return null
                cands[matches[pick]]
            }
        }
    }

    /** The importer streams [source] into [incoming]; [sizes] are what the source said of itself, corroboration for the count. */
    private suspend fun deliver(chosen: Candidate, source: ParcelFileDescriptor, sizes: List<Long>, incoming: File, displayName: String) {
        val destination = withContext(Dispatchers.IO) {
            runCatching { ParcelFileDescriptor.open(incoming, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE) }.getOrNull()
        }
        if (destination == null) { withContext(Dispatchers.IO) { runCatching { source.close() } }; throw ItemImport.ImportProblem(ItemImport.Problem.WRITE) }
        val spec = try { ImportSpec(emptyMap(), ImportNames.specDisplayName(displayName, ExportContract.MAX_NAME_CHARS)) } catch (_: IllegalArgumentException) { ImportSpec(emptyMap(), "") }
        val result = try {
            ImporterClient(activity, chosen.extension).importDocument(source, destination, spec)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "import call failed: ${e.javaClass.simpleName}" }
            throw ItemImport.ImportProblem(ItemImport.Problem.DELIVERY, e)
        }
        val landed = withContext(Dispatchers.IO) { incoming.length() }
        if (landed != result.bytesWritten) { Log.w(TAG, "delivery landed $landed of ${result.bytesWritten} reported bytes"); throw ItemImport.ImportProblem(ItemImport.Problem.SHORT) }
        if (landed == 0L) throw ItemImport.ImportProblem(ItemImport.Problem.NOT_AN_ITEM)
        if (sizes.any { it > landed }) { Log.w(TAG, "source reports $sizes for $landed delivered bytes"); throw ItemImport.ImportProblem(ItemImport.Problem.SHORT) }
        Slog.d(TAG) { "delivered $landed bytes" }
    }

    private suspend fun unlock(incoming: File, global: String): ImportKeying.Opening? {
        when (withContext(Dispatchers.IO) { SoilCrypto.probe(incoming) }) {
            SoilFileKind.Invalid -> throw ItemImport.ImportProblem(ItemImport.Problem.NOT_AN_ITEM)
            SoilFileKind.Plaintext -> return ImportKeying.Opening.Plaintext
            SoilFileKind.Encrypted -> Unit
        }
        if (withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(incoming, global) }) { Slog.d(TAG) { "incoming opens under this device's key" }; return ImportKeying.Opening.Encrypted(global) }
        ImportOverlay.stage(activity, R.string.import_stage_unlocking)
        var errorRes: Int? = null
        while (true) {
            val until = AttemptLimiter.check(activity, ATTEMPT_BUCKET)
            val remaining = until - System.currentTimeMillis()
            if (remaining > 0) { problem(R.string.import_locked_out_title, activity.getString(R.string.import_locked_out_body, formatSeconds(remaining))); return null }
            val typed = ImportDialogs.passphrase(activity, R.string.import_passphrase_title, R.string.import_passphrase_body, errorRes) ?: return null
            if (withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(incoming, typed) }) { AttemptLimiter.recordSuccess(activity, ATTEMPT_BUCKET); return ImportKeying.Opening.Encrypted(typed) }
            AttemptLimiter.recordFailure(activity, ATTEMPT_BUCKET)
            errorRes = R.string.import_passphrase_wrong
        }
    }

    private class Identity(val itemId: String, val parentId: String = "", val placementDecided: Boolean = false, val keepBothChosen: Boolean = false)

    private suspend fun resolveIdentity(manifest: ItemImport.Manifest): Identity? {
        val fileId = manifest.fileId ?: return Identity(UUID.randomUUID().toString())
        val existing = withContext(Dispatchers.IO) { IndexStore().aliveItem(fileId) }
        if (existing == null) {
            // A deleted item's id, or a folder's, is not reused: a fresh one, and no question.
            val taken = withContext(Dispatchers.IO) { LibraryStore().item(fileId) != null || LibraryStore().folder(fileId) != null }
            return Identity(if (taken) UUID.randomUUID().toString() else fileId)
        }
        if (existing.kind != manifest.kind) return Identity(UUID.randomUUID().toString())
        val choice = ImportDialogs.choose(activity, R.string.import_collision_title, activity.getString(R.string.import_collision_body, existing.name), R.string.import_replace, R.string.import_keep_both) ?: return null
        return when (choice) {
            ImportDialogs.Choice.PRIMARY -> {
                if (ItemSessions.isHeld(fileId)) throw ItemImport.ImportProblem(ItemImport.Problem.IN_USE)
                Identity(fileId, existing.parentId, placementDecided = true)
            }
            ImportDialogs.Choice.SECONDARY -> Identity(UUID.randomUUID().toString(), keepBothChosen = true)
        }
    }

    private class Landing(val parentId: String, val create: List<AncestryPlan.Create> = emptyList())

    private suspend fun placement(manifest: ItemImport.Manifest): Landing? {
        if (manifest.folderPath.isEmpty()) return Landing(currentFolder())
        val choice = ImportDialogs.choose(activity, R.string.import_placement_title, activity.getString(R.string.import_placement_body), R.string.import_placement_own, R.string.import_placement_choose) ?: return null
        if (choice == ImportDialogs.Choice.SECONDARY) {
            val deferred = CompletableDeferred<String?>()
            folderPick = deferred
            folderLauncher.launch(FolderPickerActivity.saveIntent(activity, FolderPickerActivity.Hierarchy.LIBRARY))
            val picked = deferred.await() ?: return null
            return Landing(picked)
        }
        val slots = withContext(Dispatchers.IO) {
            val library = LibraryStore()
            manifest.folderPath.mapNotNull { SafeImportId.orNull(it.id) }.distinct().associateWith { id ->
                when {
                    library.folder(id) != null -> AncestryPlan.Slot.LIVE_FOLDER
                    library.item(id) != null -> AncestryPlan.Slot.BLOCKED
                    else -> AncestryPlan.Slot.MISSING
                }
            }
        }
        val plan = AncestryPlan.plan(manifest.folderPath) { slots[it] ?: AncestryPlan.Slot.BLOCKED }
        if (plan.truncated) Slog.d(TAG) { "ancestry truncated: landing one level up" }
        return Landing(plan.parentId, plan.create)
    }

    private class Naming(val name: String, val retireId: String?)

    private suspend fun resolveName(name: String, parentId: String, itemId: String, keepBothChosen: Boolean): Naming? {
        val siblings = withContext(Dispatchers.IO) { LibraryStore().items(parentId) }.filter { it.id != itemId }
        val clash = siblings.firstOrNull { it.name == name } ?: return Naming(name, null)
        val taken = siblings.map { it.name }.toHashSet()
        if (keepBothChosen) return Naming(ImportNames.keepBothName(name) { it in taken }, null)
        val choice = ImportDialogs.choose(activity, R.string.import_name_title, activity.getString(R.string.import_name_body, name), R.string.import_replace, R.string.import_keep_both) ?: return null
        return when (choice) {
            ImportDialogs.Choice.PRIMARY -> {
                if (ItemSessions.isHeld(clash.id)) throw ItemImport.ImportProblem(ItemImport.Problem.IN_USE)
                Naming(name, clash.id)
            }
            ImportDialogs.Choice.SECONDARY -> Naming(ImportNames.keepBothName(name) { it in taken }, null)
        }
    }

    private suspend fun confirmImported(parentId: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val message = if (parentId == currentFolder()) activity.getString(R.string.import_done_body)
        else activity.getString(R.string.import_done_body_in, withContext(Dispatchers.IO) { LibraryStore().folder(parentId)?.name } ?: activity.getString(R.string.library_root))
        Dialogs.confirm(activity, R.string.import_done_title, message)
    }

    private fun problem(@StringRes titleRes: Int, message: String) {
        ImportOverlay.hide(activity)
        Dialogs.problem(activity, titleRes, message)
    }

    @StringRes
    private fun problemBody(problem: ItemImport.Problem): Int = when (problem) {
        ItemImport.Problem.DELIVERY -> R.string.import_delivery_body
        ItemImport.Problem.SHORT -> R.string.import_short_body
        ItemImport.Problem.NOT_AN_ITEM -> R.string.import_not_an_item_body
        ItemImport.Problem.NO_KEY -> R.string.import_locked_body
        ItemImport.Problem.KEYING -> R.string.import_keying_body
        ItemImport.Problem.UNREADABLE -> R.string.import_unreadable_body
        ItemImport.Problem.IN_USE -> R.string.import_in_use_body
        ItemImport.Problem.WRITE -> R.string.import_write_body
        ItemImport.Problem.NO_APP -> R.string.import_no_app_body
        ItemImport.Problem.NEWER -> R.string.import_newer_body
    }

    private fun displayNameOf(uri: Uri): String {
        runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) return c.getString(0).orEmpty() }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun sourceSizes(uri: Uri): List<Long> {
        val sizes = ArrayList<Long>(2)
        runCatching { activity.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) sizes += c.getLong(0) } }
        runCatching { activity.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> pfd.statSize.takeIf { it >= 0L }?.let { sizes += it } } }
        return sizes
    }

    private fun formatSeconds(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }

    private companion object {
        const val TAG = "ImportFlow"
        const val ATTEMPT_BUCKET = "IMPORT"
        const val CLOUD_FILE = "cloud.download"
    }
}
