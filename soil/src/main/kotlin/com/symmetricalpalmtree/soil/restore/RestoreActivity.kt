package com.symmetricalpalmtree.soil.restore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.bootstrap.Library
import com.symmetricalpalmtree.soil.cloud.CloudProviders
import com.symmetricalpalmtree.soil.crypto.PassphraseRules
import com.symmetricalpalmtree.soil.crypto.AttemptLimiter
import com.symmetricalpalmtree.soil.crypto.KeySession
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityRestoreBinding
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.home.HomeActivity
import com.symmetricalpalmtree.soil.importing.ImportDialogs
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Restore**: the one screen that puts a whole library back. Two states in one layout: the
 * sources (a folder on this device, and the cloud, GONE without a provider), and the backups the
 * chosen source holds, one row each. The grant on a picked tree is never persisted. The run is one
 * non-cancelable progress dialog through the engine's doors, and every ending is a dialog. After
 * a commit or a rollback the index is reopened here and Soil returns to Home. The proven
 * passphrase is one local value between the proof and the commit, never a field or a log line.
 */
class RestoreActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRestoreBinding
    private var source: RestoreSource? = null
    private var cloudRef: Extension? = null
    private var progress: AlertDialog? = null
    private val running = AtomicBoolean(false)

    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { Slog.d(TAG) { "folder picker cancelled" }; return@registerForActivityResult }
        lifecycleScope.launch { adoptFolder(uri) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SoilIndex.isReady() || KeySession.get() == null) { finish(); return }
        binding = ActivityRestoreBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        binding.btnBack.setOnClickListener { if (running.get()) showBusyGuard() else finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnFromFolder.setOnClickListener { onPickFolderTap() }
        binding.btnFromCloud.setOnClickListener { onCloudTap() }
        binding.btnChooseAnother.setOnClickListener { onChooseAnotherTap() }
        discoverCloud()
    }

    override fun onResume() {
        super.onResume()
        if (!running.get() && !SoilIndex.isReady()) { finish(); return }
        discoverCloud()
    }

    private fun discoverCloud() {
        lifecycleScope.launch {
            val ref = withContext(Dispatchers.IO) { runCatching { CloudProviders.installed(this@RestoreActivity) }.getOrNull() }
            if (isFinishing || isDestroyed) return@launch
            cloudRef = ref
            binding.btnFromCloud.visibility = if (ref != null) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroy() {
        progress?.let { runCatching { it.dismiss() } }
        progress = null
        super.onDestroy()
    }

    private fun showBusyGuard() = Dialogs.problem(this, R.string.restore_progress_title, R.string.restore_progress_keep_open)

    // ── Sources ──────

    private fun onPickFolderTap() {
        if (running.get()) return
        try {
            folderLauncher.launch(null)
        } catch (e: Exception) {
            Log.w(TAG, "no folder picker: ${e.javaClass.simpleName}")
            Dialogs.problem(this, R.string.restore_no_picker_title, R.string.restore_no_picker_body)
        }
    }

    private fun onChooseAnotherTap() {
        if (running.get()) return
        source = null
        binding.rows.removeAllViews()
        binding.listPane.visibility = View.GONE
        binding.sourcesPane.visibility = View.VISIBLE
    }

    private fun onCloudTap() {
        if (running.get()) return
        val ref = cloudRef ?: run { discoverCloud(); return }
        lifecycleScope.launch { adopt(CloudRestoreSource(applicationContext, ref), R.string.restore_reading_cloud, getString(R.string.restore_cloud_source_label, providerName()), showCaption = false) }
    }

    private fun providerName(): String = intent.getStringExtra(EXTRA_PROVIDER_NAME) ?: cloudRef?.label ?: getString(R.string.cloud_caption)

    private suspend fun adoptFolder(uri: Uri) = adopt(SafRestoreSource(contentResolver, uri), R.string.restore_reading, folderLabel(uri), showCaption = true)

    private suspend fun adopt(picked: RestoreSource, progressRes: Int, label: String, showCaption: Boolean) {
        showProgress(getString(progressRes))
        val result = picked.listBackups()
        hideProgress()
        if (isFinishing || isDestroyed) return
        when (result) {
            is ListResult.Failed -> sourceProblem(result.problem)
            is ListResult.Backups -> {
                source = picked
                binding.listCaption.visibility = if (showCaption) View.VISIBLE else View.GONE
                binding.folderPath.text = label
                Slog.d(TAG) { "listed ${result.backups.size} backup(s)" }
                renderList(result.backups)
            }
        }
    }

    private fun folderLabel(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return getString(R.string.restore_folder_caption)
        return id.substringAfter(':').ifEmpty { id }
    }

    // ── The list ──────

    private fun renderList(backups: List<RestoreBackup>) {
        binding.sourcesPane.visibility = View.GONE
        binding.listPane.visibility = View.VISIBLE
        binding.rows.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (backup in backups) binding.rows.addView(buildRow(inflater, backup))
    }

    private fun buildRow(inflater: LayoutInflater, backup: RestoreBackup): View {
        val view = inflater.inflate(R.layout.item_restore_backup, binding.rows, false)
        view.findViewById<TextView>(R.id.restoreRowName).text = backup.name
        view.findViewById<TextView>(R.id.restoreRowDetail).text = getString(R.string.restore_row_detail, itemsText(backup.itemCount), Formatter.formatShortFileSize(this, backup.totalBytes.coerceAtLeast(0L)), stampText(backup.indexModifiedAt))
        view.setOnClickListener { confirmReplace(backup) }
        return view
    }

    private fun itemsText(count: Int): String = if (count == 1) getString(R.string.restore_items_one) else getString(R.string.restore_items_many, count)
    private fun storesText(count: Int): String = if (count == 1) getString(R.string.restore_stores_one) else getString(R.string.restore_stores_many, count)

    private fun stampText(at: Long): String {
        val date = Date(at)
        return android.text.format.DateFormat.getMediumDateFormat(this).format(date) + " " + android.text.format.DateFormat.getTimeFormat(this).format(date)
    }

    // ── The run ──────

    private fun confirmReplace(backup: RestoreBackup) {
        if (running.get()) return
        Dialogs.style(
            AlertDialog.Builder(this).setTitle(R.string.restore_confirm_title)
                .setMessage(getString(R.string.restore_confirm_body, backup.name, itemsText(backup.itemCount), stampText(backup.indexModifiedAt)))
                .setPositiveButton(R.string.restore_confirm_replace) { _, _ -> runRestore(backup) }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
        ).show()
    }

    private fun runRestore(backup: RestoreBackup) {
        val src = source ?: return
        if (!running.compareAndSet(false, true)) return
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            try {
                restoreFlow(src, backup)
            } finally {
                running.set(false)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                hideProgress()
            }
        }
    }

    private suspend fun restoreFlow(src: RestoreSource, backup: RestoreBackup) {
        showProgress(getString(R.string.restore_progress_checking), getString(R.string.restore_progress_title))
        RestoreEngine.preflight(this, backup)?.let { hideProgress(); problemDialog(it); return }

        val copyLine = if (src is CloudRestoreSource) R.string.restore_progress_downloading else R.string.restore_progress_copying
        val manifest = when (val staged = RestoreEngine.stage(this, src, backup) { done, total -> runOnUiThread { setProgress(getString(copyLine, done, total)) } }) {
            is RestoreEngine.StageResult.Failed -> { hideProgress(); problemDialog(staged.problem); return }
            is RestoreEngine.StageResult.Staged -> staged.manifest
        }

        setProgress(getString(R.string.restore_progress_checking))
        RestoreEngine.validate(this, manifest, RestoreEngine.INDEX_ONLY)?.let { discardStaging(); hideProgress(); problemDialog(it); return }

        setProgress(getString(R.string.restore_progress_unlocking))
        val proven = RestoreEngine.proveCached(this) ?: run {
            hideProgress()
            if (isFinishing || isDestroyed) { discardStaging(); return }
            val typed = askKey()
            if (typed == null) { discardStaging(); Slog.d(TAG) { "key prompt abandoned; staging discarded" }; return }
            showProgress(getString(R.string.restore_progress_unlocking), getString(R.string.restore_progress_title))
            typed
        }

        setProgress(getString(R.string.restore_progress_checking))
        val prunedResult = when (val r = RestoreEngine.pruneOrphans(this, manifest, proven) { done, total -> runOnUiThread { setProgress(getString(R.string.restore_progress_checking_stores, done, total)) } }) {
            is RestoreEngine.PruneResult.Failed -> { discardStaging(); hideProgress(); problemDialog(r.problem); return }
            is RestoreEngine.PruneResult.Pruned -> r
        }
        val pruned = prunedResult.manifest
        RestoreEngine.validate(this, pruned, RestoreEngine.ITEMS)?.let { discardStaging(); hideProgress(); problemDialog(it); return }

        setProgress(getString(R.string.restore_progress_installing))
        val outcome = RestoreEngine.commit(this, pruned, proven, prunedResult.leftOut, prunedResult.missing)
        Slog.d(TAG) { "restore outcome: ${outcome::class.simpleName}" }
        if (outcome !is RestoreEngine.Outcome.Refused) {
            // The index is closed: open it again under whichever key is now this device's, so Home finds the library open.
            withContext(Dispatchers.IO) {
                SoilIndex.ensureReady(applicationContext)
                // The restored files are the truth of what links where: the index follows them.
                runCatching { com.symmetricalpalmtree.soil.data.index.LinkRebuild.rebuild(applicationContext) }
            }
            Library.refresh(applicationContext)
        }
        hideProgress()
        onOutcome(outcome, backup.name)
    }

    private suspend fun discardStaging() = withContext(Dispatchers.IO) { runCatching { RestoreStaging.discard(applicationContext) }; Unit }

    private fun onOutcome(outcome: RestoreEngine.Outcome, backupName: String) {
        when (outcome) {
            is RestoreEngine.Outcome.Committed -> endDialog(getString(R.string.restore_done_title), getString(R.string.restore_done_body, itemsText(outcome.items), storesText(outcome.stores), backupName) + leftOutText(outcome.leftOut) + missingText(outcome.missing))
            is RestoreEngine.Outcome.RolledBack -> endDialog(
                getString(R.string.restore_failed_title),
                getString(if (outcome.repaired) R.string.restore_failed_body else R.string.restore_failed_unrepaired_body),
            )
            is RestoreEngine.Outcome.Interrupted -> endDialog(getString(R.string.restore_interrupted_title), getString(R.string.restore_interrupted_body, (outcome.problem as? RestoreEngine.Problem.Unexpected)?.what ?: ""))
            is RestoreEngine.Outcome.Refused -> problemDialog(outcome.problem)
        }
    }

    private fun leftOutText(leftOut: List<String>): String {
        if (leftOut.isEmpty()) return ""
        val head = if (leftOut.size == 1) getString(R.string.restore_done_left_out_one) else getString(R.string.restore_done_left_out_many, leftOut.size)
        return "\n\n" + head + "\n" + leftOut.joinToString("\n")
    }

    /** Items the backup's index names but the backup did not carry, listed after a line that says so. */
    private fun missingText(missing: List<String>): String {
        if (missing.isEmpty()) return ""
        val head = if (missing.size == 1) getString(R.string.restore_done_missing_one) else getString(R.string.restore_done_missing_many, missing.size)
        return "\n\n" + head + "\n" + missing.joinToString("\n")
    }

    /** The one ending with one action: back to Home, which is open on the library as it now is. */
    private fun endDialog(title: CharSequence, body: CharSequence) {
        if (isFinishing || isDestroyed) return
        Dialogs.style(AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(R.string.restore_restart) { _, _ -> goHome() }.setCancelable(false).create()).show()
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finishAffinity()
    }

    // ── The key prompt ──────

    /** Typed, verified as typed then as a recovery key, under the RESTORE lockout. Null when the person gave up. */
    private suspend fun askKey(): String? {
        var errorRes: Int? = null
        while (true) {
            val until = AttemptLimiter.check(this, RestoreEngine.LIMITER_KEY)
            val remaining = until - System.currentTimeMillis()
            if (remaining > 0) { Dialogs.problem(this, R.string.restore_key_title, getString(R.string.unlock_locked_out, formatSeconds(remaining))); return null }
            val typed = ImportDialogs.passphrase(this, R.string.restore_key_title, R.string.restore_key_body, errorRes, R.string.restore_key_hint) ?: return null
            showProgress(getString(R.string.restore_progress_unlocking), getString(R.string.restore_progress_title))
            val proven = RestoreEngine.proveTyped(this, PassphraseRules.normalize(typed))
            hideProgress()
            if (proven != null) return proven
            errorRes = R.string.restore_key_wrong
        }
    }

    private fun formatSeconds(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }

    // ── Progress ──────

    private fun showProgress(message: String, title: String? = null) {
        if (isFinishing || isDestroyed) return
        hideProgress()
        progress = Dialogs.style(
            AlertDialog.Builder(this).apply { if (title != null) setTitle(title) }
                .setMessage(if (title == null) message else message + "\n\n" + getString(R.string.restore_progress_keep_open))
                .setCancelable(false).create(),
        ).also { it.show() }
    }

    private fun setProgress(message: String) {
        if (isFinishing || isDestroyed) return
        progress?.setMessage(message + "\n\n" + getString(R.string.restore_progress_keep_open))
    }

    private fun hideProgress() { progress?.let { runCatching { it.dismiss() } }; progress = null }

    // ── Every problem, by name ──────

    private fun problemDialog(problem: RestoreEngine.Problem) {
        if (isFinishing || isDestroyed) return
        when (problem) {
            RestoreEngine.Problem.RotationPending -> Dialogs.problem(this, R.string.restore_problem_rotation_title, R.string.restore_problem_rotation_body)
            RestoreEngine.Problem.ItemHeld -> Dialogs.problem(this, R.string.restore_problem_held_title, R.string.restore_problem_held_body)
            is RestoreEngine.Problem.NotEnoughSpace -> Dialogs.problem(this, R.string.restore_problem_space_title, getString(R.string.restore_problem_space_body, Formatter.formatShortFileSize(this, problem.shortfallBytes.coerceAtLeast(0L))))
            is RestoreEngine.Problem.Source -> sourceProblem(problem.problem)
            is RestoreEngine.Problem.InvalidFile -> Dialogs.problem(this, R.string.restore_problem_invalid_title, getString(R.string.restore_problem_invalid_body, problem.fileName))
            RestoreEngine.Problem.ParkFailed -> Dialogs.problem(this, R.string.restore_problem_park_title, R.string.restore_problem_park_body)
            is RestoreEngine.Problem.SwapFailed -> Dialogs.problem(this, R.string.restore_problem_swap_title, getString(R.string.restore_problem_swap_body, problem.step.toString()))
            is RestoreEngine.Problem.Unexpected -> Dialogs.problem(this, R.string.restore_problem_unexpected_title, getString(R.string.restore_problem_unexpected_body, problem.what))
        }
    }

    private fun sourceProblem(problem: RestoreProblem) {
        if (isFinishing || isDestroyed) return
        when (problem) {
            RestoreProblem.SourceUnreachable -> Dialogs.problem(this, R.string.restore_problem_unreachable_title, R.string.restore_problem_unreachable_body)
            RestoreProblem.ListingFailed -> Dialogs.problem(this, R.string.restore_problem_listing_title, R.string.restore_problem_listing_body)
            RestoreProblem.NotABackup -> Dialogs.problem(this, R.string.restore_problem_not_backup_title, R.string.restore_problem_not_backup_body)
            is RestoreProblem.FetchFailed -> Dialogs.problem(this, R.string.restore_problem_fetch_title, getString(R.string.restore_problem_fetch_body, problem.fileName))
            RestoreProblem.CloudNotConnected -> cloudProblem(R.string.restore_problem_cloud_not_connected_title, R.string.restore_problem_cloud_not_connected_body)
            RestoreProblem.CloudNetwork -> cloudProblem(R.string.restore_problem_cloud_network_title, R.string.restore_problem_cloud_network_body)
            RestoreProblem.CloudUnanswered -> cloudProblem(R.string.restore_problem_cloud_unanswered_title, R.string.restore_problem_cloud_unanswered_body)
            RestoreProblem.CloudGone -> cloudProblem(R.string.restore_problem_cloud_gone_title, R.string.restore_problem_cloud_gone_body)
        }
    }

    private fun cloudProblem(title: Int, body: Int) = Dialogs.problem(this, getString(title, providerName()), getString(body, providerName()))

    companion object {
        private const val TAG = "RestoreActivity"
        const val EXTRA_PROVIDER_NAME = "providerName"

        fun intent(context: Context, providerName: String? = null): Intent =
            Intent(context, RestoreActivity::class.java).apply { providerName?.let { putExtra(EXTRA_PROVIDER_NAME, it) } }
    }
}
