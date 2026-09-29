package com.symmetricalpalmtree.soil.bootstrap

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.crypto.PassphraseStore
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityRecoveryKeyBinding
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the recovery key — the global passphrase, minted on first launch and again by a rotation
 * that mints. Continue requires the "I've saved it" tick; the acknowledgement is kept, and until
 * it is given nothing is written under the key: the Scratch Pad and the Encryption screen lead
 * here first.
 *
 * It is never pushed at the person. Soil is the home screen, and a screen pushed at boot would
 * land under the firmware's own; the home screen asks, and this opens when it is asked for.
 *
 * The key is displayed and copied to the clipboard on request — never logged, never in an Intent.
 */
class RecoveryKeyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // There is a key to show only while the library is open under it.
        if (!SoilIndex.isReady()) { finish(); return }
        val binding = ActivityRecoveryKeyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) { PassphraseStore.getGlobalPassphrase(this@RecoveryKeyActivity) }
            if (key == null) { finish(); return@launch }
            binding.keyText.text = key

            binding.btnCopy.setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.recovery_clip_label), key))
                Toast.makeText(this@RecoveryKeyActivity, R.string.recovery_copied, Toast.LENGTH_SHORT).show()
            }
            binding.btnContinue.setOnClickListener {
                if (!binding.checkSaved.isChecked) {
                    // Dialog, not a toast: this explains why a tap did *nothing*. A toast only
                    // ever confirms something that happened (the Copy button above), and on e-ink
                    // a missed toast reads as a dead button.
                    Dialogs.problem(this@RecoveryKeyActivity, R.string.recovery_tick_title, R.string.recovery_tick_first)
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { PassphraseStore.setRecoveryKeyAcknowledged(this@RecoveryKeyActivity) }
                    Library.refresh(this@RecoveryKeyActivity)
                    Screens.openThen(this@RecoveryKeyActivity)
                    finish()
                }
            }
        }
    }
}
