package com.symmetricalpalmtree.soil.home

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.databinding.ActivityHiddenAppsBinding
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.shell.AppEntry
import com.symmetricalpalmtree.soil.shell.AppList
import com.symmetricalpalmtree.soil.shell.HiddenApps
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * **The hidden apps**, as a drawer of their own. It is the app drawer's twin, so that the hand
 * learns one thing: the same tiles, the same pages turned by a swipe or the pager, and the same
 * long press — which here asks whether to **show** the app again.
 *
 * It needs no key, like the drawer it serves.
 */
class HiddenAppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHiddenAppsBinding
    private lateinit var grid: AppGrid

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHiddenAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)

        grid = AppGrid(
            container = binding.appGrid,
            // Hidden is out of sight, not out of reach: a tap opens it, as in the drawer.
            onOpen = { app ->
                if (!AppList.launch(this, app)) {
                    Dialogs.problem(this, getString(R.string.app_open_failed_title), getString(R.string.app_open_failed_body, app.label))
                }
            },
            onHold = ::askToShow,
            onPaged = { page, pages ->
                // A pager over one page is two buttons that do nothing: it is not shown.
                binding.bottomBar.visibility = if (pages > 1) View.VISIBLE else View.GONE
                binding.pageText.text = getString(R.string.page_of, page + 1, pages)
            },
        )
        binding.btnPrev.setOnClickListener { grid.previous() }
        binding.btnNext.setOnClickListener { grid.next() }
        binding.appGrid.onPrevious = { grid.previous() }
        binding.appGrid.onNext = { grid.next() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(AppList.apps, HiddenApps.hidden, HiddenApps::hiddenOf).collect { apps ->
                    binding.emptyMessage.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                    grid.show(apps)
                }
            }
        }
    }

    /** A long press asks; it never acts. */
    private fun askToShow(app: AppEntry) {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.show_app_title, app.label))
                .setMessage(R.string.show_app_body)
                .setPositiveButton(R.string.show_app_confirm) { _, _ -> HiddenApps.show(this, app) }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create()
        ).show()
    }
}
