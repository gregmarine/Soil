package com.symmetricalpalmtree.soil.bootstrap

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.crypto.AttemptLimiter
import com.symmetricalpalmtree.soil.crypto.GlobalKey
import com.symmetricalpalmtree.soil.crypto.PassphraseStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityUnlockBinding
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Passphrase (recovery key) entry, when no key on this device opens the index: after a reinstall,
 * a restore, or *Forget on this device*. Rate-limited by [AttemptLimiter]; a wrong key shows an
 * error and the file is untouched. No "forgot" path — the recovery key IS the passphrase.
 *
 * **The keyboard is never hidden.** On Supernote a hardware keyboard only delivers keys while the
 * on-screen one is shown, so hiding it on an attempt would strand a keyboard user after the first
 * wrong key. There is no `hideIme()` here and there must not be one.
 */
class UnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockBinding
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SoilIndex.isReady()) { Screens.openThen(this); finish(); return }
        binding = ActivityUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root, followIme = true)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnUnlock.setOnClickListener { attempt() }
        binding.keyInput.setOnEditorActionListener { _, _, _ -> attempt(); true }
        refreshLockout()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Show/hide the lockout countdown; the entry and its button are absent while locked out. */
    private fun refreshLockout() {
        val until = AttemptLimiter.check(this)
        val remaining = until - System.currentTimeMillis()
        if (remaining > 0) {
            binding.entryRow.visibility = View.GONE
            binding.btnUnlock.visibility = View.GONE
            binding.lockoutText.visibility = View.VISIBLE
            binding.lockoutText.text = getString(R.string.unlock_locked_out, formatSeconds(remaining))
            handler.postDelayed({ refreshLockout() }, 1000L)
        } else {
            binding.lockoutText.visibility = View.GONE
            binding.entryRow.visibility = View.VISIBLE
            binding.btnUnlock.visibility = View.VISIBLE
        }
    }

    private fun attempt() {
        if (busy) return
        val typed = binding.keyInput.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) return
        if (AttemptLimiter.check(this) > System.currentTimeMillis()) { refreshLockout(); return }
        // No hideIme() — see the class note.
        busy = true
        binding.errorText.visibility = View.GONE
        binding.progressText.visibility = View.VISIBLE
        lifecycleScope.launch {
            // Recovery keys are upper-case Crockford; accept a hand-transcription too (case + the
            // O→0, I/L→1 confusables the alphabet omits — see GlobalKey.normalize).
            var ok = SoilIndex.unlockAndOpen(this@UnlockActivity, typed)
            val normalized = GlobalKey.normalize(typed)
            if (!ok && normalized != typed) ok = SoilIndex.unlockAndOpen(this@UnlockActivity, normalized)
            withContext(Dispatchers.IO) {
                if (ok) {
                    AttemptLimiter.recordSuccess(this@UnlockActivity)
                    // The person just typed the key — they have it; it need not be shown again.
                    PassphraseStore.setRecoveryKeyAcknowledged(this@UnlockActivity)
                } else {
                    AttemptLimiter.recordFailure(this@UnlockActivity)
                }
            }
            busy = false
            binding.progressText.visibility = View.GONE
            if (ok) {
                Library.refresh(this@UnlockActivity)
                Screens.openThen(this@UnlockActivity)
                finish()
            } else {
                binding.errorText.visibility = View.VISIBLE
                binding.keyInput.text?.clear()
                refreshLockout()
            }
        }
    }

    private fun formatSeconds(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }
}
