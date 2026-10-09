package com.symmetricalpalmtree.soil.backup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.cloud.CloudArgs
import com.symmetricalpalmtree.soil.cloud.CloudClient
import com.symmetricalpalmtree.soil.cloud.CloudConnectEntry
import com.symmetricalpalmtree.soil.cloud.CloudNetworkFailed
import com.symmetricalpalmtree.soil.cloud.CloudWording
import com.symmetricalpalmtree.soil.cloud.CloudWords
import com.symmetricalpalmtree.soil.crypto.GlobalRotation
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityBackupBinding
import com.symmetricalpalmtree.soil.export.ExportPanel
import com.symmetricalpalmtree.soil.ext.CloudStatus
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.restore.RestoreActivity
import com.symmetricalpalmtree.soil.templates.NameDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Backup**: where backups go, the button that runs one, and what the last run did. Manual only;
 * one run at a time; never a disabled control; every outcome a dialog with the counts. The cloud
 * section is GONE unless a provider is installed, re-asked on every resume: the account line,
 * Connect or Disconnect, this device's folder (typed at first use), the "back up to" tick and
 * the cloud's own status line. The Restore door is a row under it.
 */
class BackupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupBinding
    private lateinit var panel: ExportPanel
    private val running = AtomicBoolean(false)
    private var progress: AlertDialog? = null

    private var cloud: CloudConnectEntry? = null
    private var cloudRef: Extension? = null
    private var cloudStatus: CloudStatus? = null
    private var cloudName: String? = null
    private var cloudBusy = false
    private var deviceFolder: String? = null
    private var cloudEnabled = false

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { Slog.d(TAG) { "folder picker cancelled" }; return@registerForActivityResult }
        lifecycleScope.launch { adoptFolder(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SoilIndex.isReady() || KeySession.get() == null) { finish(); return }
        binding = ActivityBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        panel = ExportPanel(this)
        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnChoose.setOnClickListener { onChooseTap() }
        binding.btnRun.setOnClickListener { onRunTap() }
        binding.btnRestore.setOnClickListener { onRestoreTap() }
        cloud = CloudConnectEntry(this) { lifecycleScope.launch { renderCloud() } }
        binding.btnCloudConnect.setOnClickListener { onCloudButtonTap() }
        binding.btnCloudFolder.setOnClickListener { askDeviceFolder() }
        lifecycleScope.launch { render() }
    }

    override fun onResume() {
        super.onResume()
        if (!SoilIndex.isReady()) { finish(); return }
        lifecycleScope.launch { renderCloud() }
    }

    override fun onDestroy() {
        progress?.let { runCatching { it.dismiss() } }
        progress = null
        cloud?.close()
        cloud = null
        super.onDestroy()
    }

    // ── The lines ──────

    private suspend fun render() {
        val config = withContext(Dispatchers.IO) { BackupStore().read() }
        if (isFinishing || isDestroyed) return
        binding.folderPath.text = config.treeUri?.let { folderLabel(it) } ?: getString(R.string.backup_no_folder)
        val at = config.lastRunAt
        binding.status.text = if (at == null) getString(R.string.backup_status_never)
        else getString(R.string.backup_status_last, DateFormat.getDateTimeInstance().format(Date(at)), config.lastCopied ?: 0, config.lastSkipped ?: 0)
    }

    /** The chosen folder as readably as a tree URI allows: the document id's path after the volume. */
    private fun folderLabel(treeUri: String): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull() ?: return treeUri
        return id.substringAfter(':').ifEmpty { id }
    }

    // ── The folder ──────

    private fun onChooseTap() {
        try {
            folderLauncher.launch(null)
        } catch (e: Exception) {
            Log.w(TAG, "no folder picker: ${e.javaClass.simpleName}")
            Dialogs.problem(this, R.string.backup_no_picker_title, R.string.backup_no_picker_body)
        }
    }

    /** Take the lasting grant, release the previous folder's, store it. A different folder resets the stamp map. */
    private suspend fun adoptFolder(uri: Uri) {
        val granted = withContext(Dispatchers.IO) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onFailure { Log.w(TAG, "could not persist the folder grant: ${it.javaClass.simpleName}") }.isSuccess
        }
        if (isFinishing || isDestroyed) return
        if (!granted) { Dialogs.problem(this, R.string.backup_folder_failed_title, R.string.backup_folder_failed_body); return }
        val stored = uri.toString()
        withContext(Dispatchers.IO) {
            val store = BackupStore()
            val config = store.read()
            val changed = config.treeUri != stored
            if (changed) config.treeUri?.let { previous ->
                runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            }
            store.write(config.copy(treeUri = stored, stamps = if (changed) emptyMap() else config.stamps))
            Slog.d(TAG) { "backup folder set (destination changed: $changed)" }
        }
        render()
    }

    // ── The run ──────

    private fun onRunTap() {
        if (!running.compareAndSet(false, true)) { Slog.d(TAG) { "back-up tap ignored: a run is already going" }; return }
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) { BackupStore().read() }
            val ref = cloud?.discover()
            if (isFinishing || isDestroyed) { running.set(false); return@launch }
            val legs = CloudBackupRules.legs(config.treeUri != null, config.cloudEnabled, ref != null, config.cloudDeviceFolder != null)
            if (legs.none) {
                running.set(false)
                if (!isFinishing && !isDestroyed) noDestination(ref)
                return@launch
            }
            showProgress()
            val outcome = try {
                BackupEngine.run(applicationContext) { p -> runOnUiThread { updateProgress(p) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "backup run threw", e)
                BackupEngine.Outcome(local = BackupEngine.Result(failed = 1))
            } finally {
                running.set(false)
                hideProgress()
            }
            render()
            renderCloud()
            report(outcome)
        }
    }

    private fun showProgress() {
        if (isFinishing || isDestroyed) return
        progress = Dialogs.style(AlertDialog.Builder(this).setMessage(getString(R.string.backup_progress, 0, 0)).setCancelable(false).create()).also { it.show() }
    }

    private fun updateProgress(p: BackupEngine.Progress) {
        if (isFinishing || isDestroyed) return
        progress?.setMessage(if (p.leg == BackupEngine.Leg.CLOUD) getString(R.string.backup_progress_cloud, providerName(), p.done, p.total) else getString(R.string.backup_progress, p.done, p.total))
    }

    private fun hideProgress() { progress?.let { runCatching { it.dismiss() } }; progress = null }

    /** One dialog, one block per leg that ran. */
    private fun report(outcome: BackupEngine.Outcome) {
        if (isFinishing || isDestroyed) return
        when (outcome.problem) {
            BackupEngine.Problem.NO_KEY -> return Dialogs.problem(this, R.string.backup_locked_title, R.string.backup_locked_body)
            BackupEngine.Problem.ROTATION_PENDING -> return Dialogs.problem(this, R.string.restore_problem_rotation_title, R.string.encryption_resume_banner)
            BackupEngine.Problem.NO_DESTINATION -> return noDestination(cloud?.ref)
            else -> Unit
        }
        val body = buildString {
            outcome.local?.let { append(localBlock(it)) }
            outcome.cloud?.let { if (isNotEmpty()) append("\n\n"); append(cloudBlock(it)) }
        }
        if (CloudBackupRules.clean(outcome)) Dialogs.confirm(this, R.string.backup_done_title, body) { finish() }
        else Dialogs.problem(this, R.string.backup_problem_title, body + "\n\n" + getString(R.string.backup_problem_tail))
    }

    private fun noDestination(ref: Extension?) {
        if (isFinishing || isDestroyed) return
        if (ref != null) Dialogs.problem(this, R.string.backup_no_destination_title, getString(R.string.backup_no_destination_body, providerName(ref)))
        else Dialogs.problem(this, R.string.backup_no_folder_title, R.string.backup_no_folder_body)
    }

    private fun localBlock(r: BackupEngine.Result): String {
        if (r.problem == BackupEngine.Problem.FOLDER_GONE) return getString(R.string.backup_local_folder_gone)
        return legBlock(r, R.string.backup_done_body, R.string.backup_counts_failed)
    }

    private fun cloudBlock(r: BackupEngine.Result): String {
        val name = providerName()
        return legBlock(r, R.string.cloud_counts, R.string.cloud_counts_failed, name) + (cloudProblem(r.problem)?.let { "\n" + getString(it, name) } ?: "")
    }

    private fun legBlock(r: BackupEngine.Result, countsRes: Int, countsFailedRes: Int, vararg prefixArgs: Any): String {
        val clean = CloudBackupRules.legClean(r)
        val skipped = r.upToDate + r.excluded + r.held + r.missing
        return buildString {
            append(if (clean) getString(countsRes, *prefixArgs, r.copied, skipped) else getString(countsFailedRes, *prefixArgs, r.copied, skipped, r.failed))
            append(storesLine(r))
            if (!clean) { append('\n'); append(getString(if (r.indexCopied) R.string.backup_index_copied else R.string.backup_index_failed)) }
        }
    }

    private fun cloudProblem(problem: BackupEngine.Problem?): Int? = when (problem) {
        BackupEngine.Problem.CLOUD_NOT_CONNECTED -> R.string.cloud_problem_not_connected
        BackupEngine.Problem.CLOUD_NETWORK -> R.string.cloud_problem_network
        BackupEngine.Problem.CLOUD_UNANSWERED -> R.string.cloud_problem_unanswered
        BackupEngine.Problem.CLOUD_GONE -> R.string.cloud_problem_gone
        else -> null
    }

    private fun storesLine(r: BackupEngine.Result): String = buildString {
        if (r.storesCopied > 0) { append('\n'); append(getString(if (r.storesCopied == 1) R.string.backup_stores_copied else R.string.backup_stores_copied_plural, r.storesCopied)) }
        if (r.storesFailed > 0) { append('\n'); append(getString(if (r.storesFailed == 1) R.string.backup_stores_failed else R.string.backup_stores_failed_plural, r.storesFailed)) }
    }

    // ── Restore ──────

    private fun onRestoreTap() {
        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) { GlobalRotation.hasMarker(applicationContext) }
            if (isFinishing || isDestroyed) return@launch
            if (pending) Dialogs.problem(this@BackupActivity, R.string.restore_problem_rotation_title, R.string.restore_problem_rotation_body)
            else startActivity(RestoreActivity.intent(this@BackupActivity, cloudName))
        }
    }

    // ── The cloud section ──────

    private suspend fun renderCloud() {
        val entry = cloud ?: return
        val ref = entry.discover()
        if (isFinishing || isDestroyed) return
        cloudRef = ref
        if (ref == null) { cloudStatus = null; binding.cloudSection.visibility = View.GONE; return }
        binding.cloudSection.visibility = View.VISIBLE
        val status = try {
            CloudClient.status(this, ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Slog.d(TAG) { "cloud status unavailable: ${e.javaClass.simpleName}" }
            null
        }
        if (isFinishing || isDestroyed) return
        cloudStatus = status
        if (status != null && status.providerName.isNotBlank()) cloudName = status.providerName
        val name = providerName(ref)
        binding.cloudStatus.text = if (status == null) CloudWording.unavailableLine(name, cloudWords(), ::cloudLine) else CloudWording.statusLine(status, cloudWords(), ::cloudLine)
        binding.btnCloudConnect.setText(if (CloudWording.showsDisconnect(status)) R.string.cloud_disconnect else R.string.cloud_connect)
        val config = withContext(Dispatchers.IO) { BackupStore().read() }
        if (isFinishing || isDestroyed) return
        deviceFolder = config.cloudDeviceFolder
        cloudEnabled = config.cloudEnabled
        binding.cloudFolderCaption.text = getString(R.string.cloud_device_folder_caption, name)
        binding.cloudFolder.text = config.cloudDeviceFolder ?: getString(R.string.cloud_device_folder_none)
        binding.btnCloudFolder.setText(if (config.cloudDeviceFolder == null) R.string.cloud_device_folder_set else R.string.cloud_device_folder_rename)
        binding.cloudEnabledRow.removeAllViews()
        binding.cloudEnabledRow.addView(panel.toggle(getString(R.string.cloud_backup_enabled, name), config.cloudEnabled) { onCloudEnabledTap() })
        val at = config.cloudLastRunAt
        binding.cloudLast.text = if (at == null) getString(R.string.cloud_status_never, name)
        else getString(R.string.cloud_status_last, DateFormat.getDateTimeInstance().format(Date(at)), config.cloudLastCopied ?: 0, config.cloudLastSkipped ?: 0)
    }

    /** The tick is an intention. Turning it on with no folder named asks for the name first. */
    private fun onCloudEnabledTap() {
        if (cloudEnabled) { setCloudEnabled(false); return }
        if (deviceFolder == null) askDeviceFolder(thenEnable = true) else setCloudEnabled(true)
    }

    private fun setCloudEnabled(enabled: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val store = BackupStore()
                val config = store.read()
                if (config.cloudEnabled != enabled) store.write(config.copy(cloudEnabled = enabled))
            }
            Slog.d(TAG) { "cloud backup enabled=$enabled" }
            renderCloud()
        }
    }

    /**
     * This device's folder under `Backups/`, typed by the person (decision 2026-10-03), the model
     * name as the suggestion. A different name resets the cloud stamps: a stamp is a statement
     * about one destination. Judged by the seam's own bounds before anything is stored.
     */
    private fun askDeviceFolder(thenEnable: Boolean = false) {
        val current = deviceFolder
        var accepting = false
        NameDialog.show(this, titleRes = R.string.cloud_device_folder_title, confirmRes = if (current == null) R.string.cloud_device_folder_set else R.string.cloud_device_folder_rename, initial = current ?: DeviceFolder.suggest(), hintRes = R.string.cloud_device_folder_hint) { typed, dismiss ->
            if (accepting) return@show
            val name = typed.trim()
            if (name == current) { dismiss(); if (thenEnable) setCloudEnabled(true); return@show }
            if (runCatching { CloudArgs.requireName(name) }.isFailure) {
                Dialogs.problem(this, R.string.name_problem_title, getString(R.string.cloud_device_folder_problem_body))
                return@show
            }
            accepting = true
            dismiss()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val store = BackupStore()
                        store.write(store.read().copy(cloudDeviceFolder = name, cloudStamps = emptyMap(), cloudEnabled = if (thenEnable) true else store.read().cloudEnabled))
                    }
                    Slog.d(TAG) { "device folder set; cloud stamps reset" }
                    renderCloud()
                } finally {
                    accepting = false
                }
            }
        }
    }

    private fun providerName(ref: Extension? = cloudRef): String = cloudName ?: ref?.label ?: getString(R.string.cloud_caption)

    private fun cloudWords() = CloudWords(getString(R.string.cloud_state_not_connected), getString(R.string.cloud_state_connected), getString(R.string.cloud_state_not_configured), getString(R.string.cloud_state_unavailable))

    private fun cloudLine(provider: String, detail: String): String = getString(R.string.cloud_status_line, provider, detail)

    private fun onCloudButtonTap() {
        if (cloudBusy) return
        if (CloudWording.showsDisconnect(cloudStatus)) confirmDisconnect() else connect()
    }

    private fun connect() {
        val entry = cloud ?: return
        val status = cloudStatus
        if (status != null && !status.configured) { Dialogs.problem(this, R.string.cloud_not_configured_title, R.string.cloud_not_configured_body); return }
        entry.open()
    }

    private fun confirmDisconnect() {
        val name = providerName()
        Dialogs.style(
            AlertDialog.Builder(this).setTitle(getString(R.string.cloud_disconnect_title, name)).setMessage(R.string.cloud_disconnect_body)
                .setPositiveButton(R.string.cloud_disconnect) { _, _ -> disconnect() }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
        ).show()
    }

    private fun disconnect() {
        val ref = cloudRef ?: return
        cloudBusy = true
        val dialog = Dialogs.style(AlertDialog.Builder(this).setMessage(R.string.cloud_disconnecting).setCancelable(false).create()).also { it.show() }
        lifecycleScope.launch {
            var failed = false
            try {
                CloudClient.disconnect(this@BackupActivity, ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudNetworkFailed) {
                Slog.d(TAG) { "disconnect: the revoke did not reach the provider; forgotten locally" }
            } catch (e: Exception) {
                Slog.d(TAG) { "disconnect failed: ${e.javaClass.simpleName}" }
                failed = true
            } finally {
                runCatching { dialog.dismiss() }
                cloudBusy = false
            }
            if (isFinishing || isDestroyed) return@launch
            renderCloud()
            if (failed && !isFinishing && !isDestroyed) Dialogs.problem(this@BackupActivity, R.string.cloud_disconnect_failed_title, R.string.cloud_disconnect_failed_body)
        }
    }

    private companion object { const val TAG = "BackupActivity" }
}
