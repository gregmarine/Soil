package com.symmetricalpalmtree.soil.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.databinding.ActivitySettingsBinding
import com.symmetricalpalmtree.soil.ext.Recognizer
import com.symmetricalpalmtree.soil.ext.Recognizers
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.tags.TagRowView
import com.symmetricalpalmtree.soil.templates.TemplatesActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * **Soil's one Settings screen**, behind the gear on the home top bar: what the apps are
 * recognised with (which installed recogniser, in which language), and the paper library's door.
 * Rows are built in code, each a label over its current answer; a tap asks with a sheet.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SettingsPrefs
    private var installed: List<Recognizer> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        prefs = SettingsPrefs(this)
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        // Read again at every showing: an extension can be installed or removed meanwhile.
        lifecycleScope.launch {
            installed = withContext(Dispatchers.IO) { Recognizers.installed(this@SettingsActivity) }
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
    }

    /** Every installed recogniser in each of its languages, and None. A missing recogniser is said, not offered. */
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
}
