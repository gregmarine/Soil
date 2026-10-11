package com.symmetricalpalmtree.soil.templates

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityTemplatesBinding
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.templates.TemplatePick
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck

/**
 * **Templates**: the paper library, as the browser and nothing else. Two modes, one browser:
 * **browse**, where a tap on a paper card means nothing; and **pick**, started for a result by a
 * Sprout app ([Seam.ACTION_PICK_TEMPLATE], guarded by the seam permission), where a tap on a paper
 * card is the answer, a [TemplatePick] as encoded, and the screen closes. The app passes the
 * page's token so the paper in force is ticked. Nothing here opens an item file: the app does
 * the write.
 */
class TemplatesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTemplatesBinding
    private lateinit var browser: TemplateBrowser
    private var picking = false
    private var selectedToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        picking = intent.action == Seam.ACTION_PICK_TEMPLATE
        if (picking && !callerAllowed()) { finish(); return }
        // The library is opened in Soil, never from here: a shut one is a closed screen.
        if (!SoilIndex.isReady()) { finish(); return }
        binding = ActivityTemplatesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        selectedToken = intent.getStringExtra(Seam.EXTRA_CURRENT_TOKEN)
        browser = TemplateBrowser(
            activity = this,
            binding = binding.browser,
            onPick = { pick -> if (picking) finishWith(pick) },
            selection = { TemplateBrowser.Selection(token = selectedToken.takeIf { picking }) },
        )
        browser.restoreState(savedInstanceState)
        browser.showCloseButton { finish() }
    }

    /** The pick is for a Sprout app: the caller holds the seam permission and Soil's key. */
    private fun callerAllowed(): Boolean = runCatching { SeamCallerCheck.enforceCaller(this, callingPackage) }.isSuccess

    private fun finishWith(pick: TemplatePick) {
        setResult(Activity.RESULT_OK, Intent().putExtra(Seam.EXTRA_PICK, pick.encode()))
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::browser.isInitialized) browser.saveState(outState)
    }

    override fun onDestroy() {
        if (::browser.isInitialized) browser.close()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::browser.isInitialized && browser.onBackPressed()) return
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, TemplatesActivity::class.java)
    }
}
