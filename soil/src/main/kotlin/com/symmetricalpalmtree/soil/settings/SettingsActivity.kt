package com.symmetricalpalmtree.soil.settings

import android.content.Intent
import android.os.Bundle
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.data.index.LinkRebuild
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.bootstrap.Screen
import com.symmetricalpalmtree.soil.bootstrap.Screens
import com.symmetricalpalmtree.soil.cloud.CloudClient
import com.symmetricalpalmtree.soil.cloud.CloudConnectEntry
import com.symmetricalpalmtree.soil.cloud.CloudNetworkFailed
import com.symmetricalpalmtree.soil.cloud.CloudWording
import com.symmetricalpalmtree.soil.cloud.CloudWords
import com.symmetricalpalmtree.soil.ext.CloudStatus
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.databinding.ActivitySettingsBinding
import com.symmetricalpalmtree.soil.ext.Recognizer
import com.symmetricalpalmtree.soil.ext.Recognizers
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.tags.TagRowView
import com.symmetricalpalmtree.soil.templates.TemplatesActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * **Soil's one Settings screen**, behind the gear on the home top bar: what the apps are
 * recognised with (which installed recogniser, in which language), the paper library's door,
 * the cloud account (the provider's status line; a tap connects or disconnects), and the
 * Encryption screen's door. Rows are
 * built in code, each a label over its current answer; a tap asks with a sheet or a dialog.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SettingsPrefs
    private var installed: List<Recognizer> = emptyList()

    private var cloud: CloudConnectEntry? = null
    private var cloudRef: Extension? = null
    private var cloudStatus: CloudStatus? = null
    private var cloudBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        prefs = SettingsPrefs(this)
        binding.btnBack.setOnClickListener { finish() }
        cloud = CloudConnectEntry(this) { lifecycleScope.launch { loadCloud(); if (!isFinishing && !isDestroyed) render() } }
    }

    override fun onDestroy() {
        cloud?.close()
        cloud = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Read again at every showing: an extension can be installed or removed meanwhile.
        lifecycleScope.launch {
            installed = withContext(Dispatchers.IO) { Recognizers.installed(this@SettingsActivity) }
            loadCloud()
            if (!isFinishing && !isDestroyed) render()
        }
    }

    private fun render() {
        binding.rows.removeAllViews()
        val choice = Recognizers.choose(installed, prefs.recognizerKey, prefs.recognizerLanguage)
        val detail = when {
            installed.isEmpty() -> getString(R.string.settings_recognizer_none_installed)
            choice == null -> getString(R.string.settings_recognizer_none)
            else -> getString(R.string.settings_recognizer_value, choice.recognizer.label, languageName(choice.languageTag))
        }
        binding.rows.addView(TagRowView.buildTarget(this, getString(R.string.settings_recognizer), detail) { askRecognizer() })
        binding.rows.addView(TagRowView.buildTarget(this, getString(R.string.settings_templates), getString(R.string.settings_templates_detail)) {
            startActivity(Intent(this, TemplatesActivity::class.java))
        })
        binding.rows.addView(TagRowView.buildTarget(this, getString(R.string.settings_cloud), cloudDetail()) { onCloudTap() })
        // Through the gate: while the key is unsaved or the library locked, this leads to the screen that opens it.
        binding.rows.addView(TagRowView.buildTarget(this, getString(R.string.settings_encryption), getString(R.string.settings_encryption_detail)) { Screens.open(this, Screen.ENCRYPTION) })
        binding.rows.addView(TagRowView.buildTarget(this, getString(R.string.settings_links), getString(R.string.settings_links_detail)) { rebuildLinks() })
    }

    // ── Cloud ──────

    /** The provider's status never touches the network, so it is read at every showing. */
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

    private fun cloudWords() = CloudWords(getString(R.string.cloud_state_not_connected), getString(R.string.cloud_state_connected), getString(R.string.cloud_state_not_configured), getString(R.string.cloud_state_unavailable))

    private fun cloudDetail(): String {
        val ref = cloudRef ?: return getString(R.string.settings_cloud_none)
        val joiner = { provider: String, detail: String -> getString(R.string.cloud_status_line, provider, detail) }
        val status = cloudStatus ?: return CloudWording.unavailableLine(ref.label, cloudWords(), joiner)
        return CloudWording.statusLine(status, cloudWords(), joiner)
    }

    /** Connected: offer Disconnect. A build without credentials: say so. Otherwise: the sign-in. */
    private fun onCloudTap() {
        if (cloudBusy) return
        val ref = cloudRef
        if (ref == null) { Dialogs.problem(this, R.string.settings_cloud, R.string.settings_cloud_none_body); return }
        val status = cloudStatus
        when {
            CloudWording.showsDisconnect(status) -> {
                val name = status?.providerName?.takeIf { it.isNotBlank() } ?: ref.label
                Dialogs.style(
                    AlertDialog.Builder(this).setTitle(getString(R.string.cloud_disconnect_title, name)).setMessage(R.string.cloud_disconnect_body)
                        .setPositiveButton(R.string.cloud_disconnect) { _, _ -> disconnect(ref) }
                        .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null).create(),
                ).show()
            }
            status != null && !status.configured -> Dialogs.problem(this, R.string.cloud_not_configured_title, R.string.cloud_not_configured_body)
            else -> cloud?.open()
        }
    }

    private fun disconnect(ref: Extension) {
        cloudBusy = true
        val progress = Dialogs.style(AlertDialog.Builder(this).setMessage(R.string.cloud_disconnecting).setCancelable(false).create()).also { it.show() }
        lifecycleScope.launch {
            try {
                CloudClient.disconnect(this@SettingsActivity, ref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudNetworkFailed) {
                // The provider forgets the token locally whichever way the revoke went.
                Slog.d(TAG) { "disconnect: the revoke did not reach the service" }
            } catch (e: Exception) {
                Slog.d(TAG) { "disconnect failed: ${e.javaClass.simpleName}" }
                if (!isFinishing && !isDestroyed) Dialogs.problem(this@SettingsActivity, R.string.cloud_disconnect_failed_title, R.string.cloud_disconnect_failed_body)
            } finally {
                cloudBusy = false
                runCatching { progress.dismiss() }
            }
            loadCloud()
            if (!isFinishing && !isDestroyed) render()
        }
    }

    /** Every installed recogniser in each of its languages, and None. A missing recogniser is said, not offered. */
    /** The link index rebuilt from every file: the way back when a write was missed. */
    private fun rebuildLinks() {
        if (!SoilIndex.isReady()) { Dialogs.problem(this, R.string.settings_links, R.string.settings_links_locked); return }
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { LinkRebuild.rebuild(applicationContext) }.getOrNull() }
            if (isFinishing || isDestroyed) return@launch
            val body = when {
                outcome == null -> getString(R.string.settings_links_failed)
                outcome.skipped == 0 -> getString(R.string.settings_links_done, outcome.items)
                else -> getString(R.string.settings_links_done_skipped, outcome.items, outcome.skipped)
            }
            Dialogs.confirm(this@SettingsActivity, getString(R.string.settings_links), body)
        }
    }

    private fun askRecognizer() {
        if (installed.isEmpty()) {
            com.symmetricalpalmtree.soil.paper.core.Dialogs.problem(this, R.string.settings_recognizer, R.string.settings_recognizer_none_installed_body)
            return
        }
        val choice = Recognizers.choose(installed, prefs.recognizerKey, prefs.recognizerLanguage)
        val sheet = ActionSheetDialog(this).title(getString(R.string.settings_recognizer))
        for (r in installed) for (tag in r.languages) {
            val ticked = choice != null && choice.recognizer.key == r.key && choice.languageTag == tag
            sheet.addAction(if (ticked) com.symmetricalpalmtree.soil.paper.R.drawable.ic_check else null, getString(R.string.settings_recognizer_value, r.label, languageName(tag))) {
                prefs.recognizerKey = r.key
                prefs.recognizerLanguage = tag
                render()
            }
        }
        sheet.addAction(if (choice == null) com.symmetricalpalmtree.soil.paper.R.drawable.ic_check else null, getString(R.string.settings_recognizer_none)) {
            prefs.recognizerKey = Recognizers.NONE
            prefs.recognizerLanguage = null
            render()
        }
        sheet.show()
    }

    private fun languageName(tag: String): String = Locale.forLanguageTag(tag).let { l -> l.getDisplayName(l).ifEmpty { tag } }

    private companion object { const val TAG = "SettingsActivity" }
}
