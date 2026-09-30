package com.symmetricalpalmtree.soil.encryption

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.bootstrap.KeyGate
import com.symmetricalpalmtree.soil.bootstrap.Library
import com.symmetricalpalmtree.soil.bootstrap.RecoveryKeyActivity
import com.symmetricalpalmtree.soil.bootstrap.Screen
import com.symmetricalpalmtree.soil.bootstrap.Screens
import com.symmetricalpalmtree.soil.crypto.GlobalKey
import com.symmetricalpalmtree.soil.crypto.GlobalRotation
import com.symmetricalpalmtree.soil.crypto.KeyMaterial
import com.symmetricalpalmtree.soil.crypto.PassphraseRules
import com.symmetricalpalmtree.soil.crypto.PassphraseStore
import com.symmetricalpalmtree.soil.data.index.IndexStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityEncryptionBinding
import com.symmetricalpalmtree.soil.databinding.DialogPassphraseCurrentBinding
import com.symmetricalpalmtree.soil.databinding.DialogPassphraseNewBinding
import com.symmetricalpalmtree.soil.pad.ScratchPadActivity
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * **Encryption** — the one screen about the secret that opens the library: where a person can see
 * it again, replace it, and drop it from this device.
 *
 *  - **Status.** Whether a recovery key is cached here, and how many items open under it.
 *  - **Reveal recovery key…** — the key in monospace with Copy / Close. No re-authentication: the
 *    device's own lock is the gate.
 *  - **Change passphrase…** — the whole library's key, replaced in place. Verify the current key
 *    by string match against the cache (the recovery key's confusable fold is accepted, since the
 *    field is where a hand-transcribed key lands), then *New passphrase* — **Generate a new
 *    recovery key** (the default) or **Choose my own** under [PassphraseRules] — then a confirm.
 *    [GlobalRotation] then re-keys every item, the stores and the index last, under a
 *    non-cancelable progress dialog whose Cancel stops **after** the file in hand and leaves the
 *    rest to the banner's Resume. The engine closes the index for its own turn, so **while a
 *    rotation runs this screen touches nothing but dialogs**; when it ends, the index is opened
 *    again.
 *  - **Resume** — while a rotation marker exists the banner replaces Change passphrase and Forget
 *    (both GONE): neither is a safe thing to start on top of a library that is in two keys.
 *  - **Forget on this device…** — confirm, then the cached passphrase, the RAM copy and every
 *    cached raw key leave this device and the library locks. Nothing is decrypted or modified.
 *
 * **Nothing here runs while the Scratch Pad is open**: its store cannot be re-keyed
 * or closed under a live page, so the person is asked to close the pad first.
 *
 * The passphrase is never logged and never rides an Intent; the only place it goes from here is
 * the clipboard, on the person's own tap.
 */
class EncryptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEncryptionBinding

    /** True from the moment a rotation starts until its outcome has been dealt with. */
    private var rotating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The gate, before anything is inflated. A rotation in flight is what this screen is
        // FOR, so that route opens here rather than leading away.
        val route = Library.status.value.route
        if (route != KeyGate.Route.OPEN && route != KeyGate.Route.RESUME_ROTATION) {
            Screens.open(applicationContext, Screen.ENCRYPTION)
            finish()
            return
        }
        binding = ActivityEncryptionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        binding.btnBack.setOnClickListener { if (!rotating) finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnReveal.setOnClickListener { reveal() }
        binding.btnChange.setOnClickListener { if (padIsShut()) changePassphrase() }
        binding.btnResume.setOnClickListener { if (padIsShut()) runRotation(resume = true) }
        binding.btnForget.setOnClickListener { if (padIsShut()) confirmForget() }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    /** A rotation cannot be walked away from by Back: it finishes the file in hand first. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!rotating) super.onBackPressed()
    }

    /** Explains, rather than refusing in silence, when the pad is open. */
    private fun padIsShut(): Boolean {
        if (!ScratchPadActivity.isOpen) return true
        Dialogs.problem(this, R.string.encryption_pad_open_title, R.string.encryption_pad_open_body)
        return false
    }

    /**
     * The two status lines and the resume banner. Off Main: the key and the marker are Keystore
     * reads and the count is a query. Never run while a rotation has the index closed.
     */
    private fun renderStatus() {
        if (rotating || !SoilIndex.isReady()) return
        lifecycleScope.launch {
            val (isSet, hasMarker, count) = withContext(Dispatchers.IO) {
                Triple(
                    PassphraseStore.getGlobalPassphrase(this@EncryptionActivity) != null,
                    GlobalRotation.hasMarker(this@EncryptionActivity),
                    runCatching { IndexStore().globalItems().size }.getOrDefault(0),
                )
            }
            binding.keyStatus.setText(if (isSet) R.string.encryption_key_set else R.string.encryption_key_not_set)
            binding.keyCount.text = when (count) {
                0 -> getString(R.string.encryption_count_zero)
                1 -> getString(R.string.encryption_count_one)
                else -> getString(R.string.encryption_count_many, count)
            }
            // While a change is half-done the only offer is to finish it.
            binding.resumeBanner.visibility = if (hasMarker) View.VISIBLE else View.GONE
            binding.btnChange.visibility = if (hasMarker) View.GONE else View.VISIBLE
            binding.btnForget.visibility = if (hasMarker) View.GONE else View.VISIBLE
        }
    }

    // ── Reveal ───────────────────────────────────────────────────────────────

    private fun reveal() {
        lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) { PassphraseStore.getGlobalPassphrase(this@EncryptionActivity) }
            if (key == null) {
                // Cannot happen behind the gate short of a Keystore wipe under a live process — a
                // dialog, because on e-ink a toast is missable.
                Dialogs.problem(this@EncryptionActivity, R.string.encryption_reveal_none_title, R.string.encryption_reveal_none_body)
                return@launch
            }
            val keyView = TextView(this@EncryptionActivity).apply {
                text = key
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                textSize = 16f
                letterSpacing = 0.02f
                setTextColor(ContextCompat.getColor(context, com.symmetricalpalmtree.soil.paper.R.color.inkBlack))
                // Inside the dialog's own message inset, so the key lines up with the body text.
                val d = resources.displayMetrics.density
                setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (4 * d).toInt())
            }
            Dialogs.style(
                AlertDialog.Builder(this@EncryptionActivity)
                    .setTitle(R.string.encryption_reveal_title)
                    .setMessage(R.string.encryption_reveal_body)
                    .setView(keyView)
                    .setPositiveButton(R.string.recovery_copy) { _, _ -> copyToClipboard(key) }
                    .setNegativeButton(R.string.encryption_close, null)
                    .create()
            ).show()
        }
    }

    private fun copyToClipboard(key: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.recovery_clip_label), key))
        // A toast: it only confirms something that already happened.
        Toast.makeText(this, R.string.recovery_copied, Toast.LENGTH_SHORT).show()
    }

    // ── Change passphrase ────────────────────────────────────────────────────

    /** Step 1: prove the current key. A string match against the cache — no file is touched yet. */
    private fun changePassphrase() {
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { PassphraseStore.getGlobalPassphrase(this@EncryptionActivity) }
            if (current == null) {
                Dialogs.problem(this@EncryptionActivity, R.string.encryption_reveal_none_title, R.string.encryption_reveal_none_body)
                return@launch
            }
            askCurrent(current)
        }
    }

    /**
     * "Current passphrase". The positive button is wired **after** `show()` so a wrong entry keeps
     * the dialog (and the typing) — the default listener dismisses before anything can object. The
     * keyboard is never touched: on Supernote a hardware keyboard only delivers keys while it is
     * shown.
     */
    private fun askCurrent(current: String) {
        if (isFinishing || isDestroyed) return
        val view = DialogPassphraseCurrentBinding.inflate(layoutInflater)
        val dialog = Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_current_title)
                .setMessage(R.string.encryption_current_body)
                .setView(view.root)
                .setPositiveButton(R.string.encryption_continue, null)
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create()
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = view.field.text.toString().trim()
            // The fold rescues a hand-transcribed recovery key (O for 0, l for 1); a typed
            // passphrase matches plainly. Neither branch echoes anything anywhere.
            if (typed == current || GlobalKey.normalize(typed) == current) {
                dialog.dismiss()
                askNew(current)
            } else {
                view.error.visibility = View.VISIBLE
            }
        }
    }

    /** Step 2: generate a fresh recovery key, or type one under [PassphraseRules]. */
    private fun askNew(current: String) {
        if (isFinishing || isDestroyed) return
        val view = DialogPassphraseNewBinding.inflate(layoutInflater)
        view.modeGroup.setOnCheckedChangeListener { _, checked ->
            view.ownFields.visibility = if (checked == R.id.modeChoose) View.VISIBLE else View.GONE
            view.error.visibility = View.GONE
        }
        val dialog = Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_new_title)
                .setView(view.root)
                .setPositiveButton(R.string.encryption_continue, null)
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create()
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (view.modeGenerate.isChecked) {
                dialog.dismiss()
                confirmChange(GlobalKey.mint(), minted = true)
                return@setOnClickListener
            }
            val typed = view.newField.text.toString()
            when (val verdict = PassphraseRules.check(typed, view.confirmField.text.toString(), current)) {
                PassphraseRules.Verdict.OK -> {
                    dialog.dismiss()
                    confirmChange(PassphraseRules.normalize(typed), minted = false)
                }
                else -> {
                    view.error.setText(
                        when (verdict) {
                            PassphraseRules.Verdict.TOO_SHORT -> R.string.encryption_rule_short
                            PassphraseRules.Verdict.MISMATCH -> R.string.encryption_rule_mismatch
                            else -> R.string.encryption_rule_same
                        }
                    )
                    view.error.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Step 3: say what is about to happen, and how long it may take. */
    private fun confirmChange(newPassphrase: String, minted: Boolean) {
        if (isFinishing || isDestroyed) return
        val body = StringBuilder(getString(R.string.encryption_change_body))
        if (minted) body.append("\n\n").append(getString(R.string.encryption_change_minted))
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_change_title)
                .setMessage(body.toString())
                .setPositiveButton(R.string.encryption_change_confirm) { _, _ ->
                    if (padIsShut()) runRotation(resume = false, newPassphrase = newPassphrase, minted = minted)
                }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create()
        ).show()
    }

    /**
     * The rotation itself. The screen stays on for the duration (a rekey is two KDF verifies plus
     * a copy per file), the progress dialog is not cancelable, and its Cancel only asks:
     * [GlobalRotation] finishes the file in hand and leaves the rest to the marker. Whatever the
     * outcome, the index is opened again before anything else is shown — under whichever key it
     * is now under.
     */
    private fun runRotation(resume: Boolean, newPassphrase: String? = null, minted: Boolean = false) {
        val cancel = AtomicBoolean(false)
        var stopping = false
        var progress: GlobalRotation.Progress? = null

        val dialog = Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_progress_title)
                .setMessage(getString(R.string.encryption_progress_keep_open))
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .setCancelable(false)
                .create()
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            // A tapped Cancel that looks dead is worse than none on e-ink: the last line changes
            // and the button leaves. The dialog itself stays until the file in hand is finished.
            cancel.set(true)
            stopping = true
            progress?.let { p -> dialog.setMessage(progressText(p, stopping = true)) }
            it.visibility = View.GONE
        }

        rotating = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            val onProgress: suspend (GlobalRotation.Progress) -> Unit = { p ->
                withContext(Dispatchers.Main) {
                    progress = p
                    dialog.setMessage(progressText(p, stopping))
                }
            }
            try {
                val result = if (resume) {
                    GlobalRotation.resume(this@EncryptionActivity, onProgress, cancel)
                } else {
                    GlobalRotation.start(this@EncryptionActivity, newPassphrase!!, minted, onProgress, cancel)
                }
                Slog.d(TAG) { "rotation result: ${result::class.simpleName}" }
                // The engine closed the index for its own turn. Open it again, whatever happened.
                SoilIndex.ensureReady(this@EncryptionActivity)
                Library.refresh(this@EncryptionActivity)
                if (isFinishing || isDestroyed) return@launch
                dialog.dismiss()
                when (result) {
                    is GlobalRotation.Result.Complete -> showComplete(result)
                    is GlobalRotation.Result.Cancelled -> showCancelled(result)
                    is GlobalRotation.Result.Failed -> showFailed(result)
                }
            } finally {
                rotating = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                renderStatus()
            }
        }
    }

    /** Count, then what is being re-keyed, then the one instruction. */
    private fun progressText(p: GlobalRotation.Progress, stopping: Boolean): String {
        val label = when (val l = p.label) {
            is GlobalRotation.Label.Item -> l.name.ifBlank { getString(R.string.encryption_progress_item) }
            GlobalRotation.Label.Stores -> getString(R.string.encryption_progress_stores)
            GlobalRotation.Label.Index -> getString(R.string.encryption_progress_index)
        }
        val tail = getString(
            if (stopping) R.string.encryption_progress_stopping else R.string.encryption_progress_keep_open
        )
        return getString(R.string.encryption_progress_count, p.done + 1, p.total) + "\n" + label + "\n\n" + tail
    }

    private fun showComplete(result: GlobalRotation.Result.Complete) {
        val body = StringBuilder(
            when (result.items) {
                0 -> getString(R.string.encryption_done_zero)
                1 -> getString(R.string.encryption_done_one)
                else -> getString(R.string.encryption_done_many, result.items)
            }
        )
        if (result.quarantined > 0) {
            body.append("\n\n").append(
                if (result.quarantined == 1) getString(R.string.encryption_done_quarantined_one)
                else getString(R.string.encryption_done_quarantined_many, result.quarantined)
            )
        }
        // A minted key has not been seen yet: the commit cleared the acknowledgement, so the one
        // way on is to be shown it.
        val minted = Library.status.value.route == KeyGate.Route.RECOVERY_KEY
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_done_title)
                .setMessage(body.toString())
                .setPositiveButton(if (minted) R.string.encryption_done_show_key else R.string.encryption_done_finish) { _, _ ->
                    if (minted) startActivity(Intent(this, RecoveryKeyActivity::class.java))
                    finish()
                }
                .setCancelable(false)
                .create()
        ).show()
    }

    private fun showCancelled(result: GlobalRotation.Result.Cancelled) {
        Dialogs.confirm(
            this,
            getString(R.string.encryption_paused_title),
            if (result.remaining == 1) getString(R.string.encryption_paused_one)
            else getString(R.string.encryption_paused_many, result.remaining),
        )
    }

    private fun showFailed(result: GlobalRotation.Result.Failed) {
        val body = getString(
            when (result.reason) {
                GlobalRotation.Reason.NO_CACHED_GLOBAL -> R.string.encryption_failed_no_key
                GlobalRotation.Reason.TRANSIENT -> R.string.encryption_failed_transient
                GlobalRotation.Reason.STUCK -> R.string.encryption_failed_stuck
            }
        )
        Dialogs.confirm(this, getString(R.string.encryption_failed_title), body) {
            // The library may have locked under us (the key left this device mid-rotation).
            if (!SoilIndex.isReady()) finish()
        }
    }

    // ── Forget ───────────────────────────────────────────────────────────────

    private fun confirmForget() {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_forget_title)
                .setMessage(R.string.encryption_forget_body)
                .setPositiveButton(R.string.encryption_forget_confirm) { _, _ -> if (padIsShut()) forget() }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create()
        ).show()
    }

    /**
     * Drop every copy of the key this device holds — the Keystore-backed cache, the process-RAM
     * copy, and every cached raw key — and lock the library. Nothing on disk changes: every item,
     * every store and the index stay exactly as they are, and opening them again needs the key.
     */
    private fun forget() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                PassphraseStore.clearGlobalPassphrase(this@EncryptionActivity)
                KeyMaterial.clearAll(this@EncryptionActivity)
            }
            // Closes the stores and the index and drops the RAM copy of the key.
            SoilIndex.lock(this@EncryptionActivity)
            Library.refresh(this@EncryptionActivity)
            Slog.d(TAG) { "recovery key forgotten on this device; the library is locked" }
            finish()
        }
    }

    private companion object {
        const val TAG = "Encryption"
    }
}
