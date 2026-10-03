package com.symmetricalpalmtree.soil.importing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.crypto.AttemptLimiter
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.crypto.SoilCrypto
import com.symmetricalpalmtree.soil.crypto.SoilFileKind
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.data.item.ItemSessions
import com.symmetricalpalmtree.soil.export.AppRenderers
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

    private class Candidate(val extension: Extension, val info: ImporterInfo)

    private var candidates: List<Candidate> = emptyList()
    private var isBusy = false
    var isImporting: Boolean = false
        private set
    private var discovering = false
    private var folderPick: CompletableDeferred<String?>? = null

    private val openLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) runImport(uri) else { isBusy = false; Slog.d(TAG) { "document picker cancelled" } }
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
            val found = try { withContext(Dispatchers.IO) { Extensions.importers(activity) }.isNotEmpty() } finally { discovering = false }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (!isImporting && found != installed) { installed = found; onInstalledChanged() }
        }
    }

    fun showBusyGuard() = Dialogs.problem(activity, R.string.import_busy_title, R.string.import_busy_body)

    fun onTap() {
        if (isImporting) { showBusyGuard(); return }
        if (isBusy) return
        isBusy = true
        activity.lifecycleScope.launch {
            var handed = false
            try {
                val cands = loadCandidates().also { candidates = it }
                if (cands.isEmpty()) { problem(R.string.import_none_title, activity.getString(R.string.import_none_body)); refresh(); return@launch }
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(ImporterMatch.ANY_TYPE)
                    .putExtra(Intent.EXTRA_MIME_TYPES, ImporterMatch.mimeFilter(cands.map { it.info.mimeTypes }))
                handed = try {
                    openLauncher.launch(intent); true
                } catch (e: Exception) {
                    Log.w(TAG, "no document picker: ${e.javaClass.simpleName}")
                    problem(R.string.import_no_picker_title, activity.getString(R.string.import_no_picker_body))
                    false
                }
            } finally {
                if (!handed) isBusy = false
            }
        }
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
        return kept
    }

    private fun runImport(uri: Uri) {
        isBusy = true
        isImporting = true
        ImportOverlay.show(activity, R.string.import_stage_reading)
        activity.lifecycleScope.launch {
            try {
                import(uri)
            } catch (e: ItemImport.ImportProblem) {
                Slog.d(TAG) { "import problem: ${e.problem}" }
                problem(R.string.import_failed_title, activity.getString(problemBody(e.problem)))
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

    private suspend fun import(uri: Uri) {
        val displayName = withContext(Dispatchers.IO) { displayNameOf(uri) }
        val cands = candidates.ifEmpty { loadCandidates().also { candidates = it } }
        if (cands.isEmpty()) { problem(R.string.import_none_title, activity.getString(R.string.import_none_body)); return }
        val chosen = chooseImporter(cands, displayName) ?: return
        val incoming = withContext(Dispatchers.IO) { ItemImport.prepareCache(activity) }
        deliver(chosen, uri, incoming, displayName)

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

    private suspend fun deliver(chosen: Candidate, uri: Uri, incoming: File, displayName: String) {
        val sizes = withContext(Dispatchers.IO) { sourceSizes(uri) }
        val source = withContext(Dispatchers.IO) { runCatching { activity.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() }
            ?: throw ItemImport.ImportProblem(ItemImport.Problem.DELIVERY)
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
    }
}
