package com.symmetricalpalmtree.soil.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.bootstrap.KeyGate
import com.symmetricalpalmtree.soil.bootstrap.Library
import com.symmetricalpalmtree.soil.bootstrap.RecoveryKeyActivity
import com.symmetricalpalmtree.soil.bootstrap.UnlockActivity
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityHomeBinding
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.shell.AppList
import kotlinx.coroutines.launch

/**
 * **The home screen, which is the library.** Until a Sprout app can make an item the library is
 * empty, so below it is a grid of the installed apps — the same list the side menu shows.
 *
 * It renders [Library.status] rather than waiting for it. Soil is the device's home screen: it
 * cannot forward to a bootstrap and finish, and it must never trap the person. The grid needs no
 * key, so it works while the library is locked, being prepared, or out of reach.
 *
 * It does not touch the side bars or the firmware's menu at all — that is the bar service's work,
 * and Soil runs as an ordinary app without it.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var grid: AppGrid

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        grid = AppGrid(
            container = binding.appGrid,
            onOpen = { app ->
                if (!AppList.launch(this, app)) {
                    Dialogs.problem(this, getString(R.string.app_open_failed_title), getString(R.string.app_open_failed_body, app.label))
                }
            },
            onPaged = { page, pages ->
                // A pager over one page is two buttons that do nothing: it is not shown.
                binding.appPager.visibility = if (pages > 1) View.VISIBLE else View.GONE
                binding.appPageText.text = getString(R.string.page_of, page + 1, pages)
            },
        )
        binding.btnAppsPrev.setOnClickListener { grid.previous() }
        binding.btnAppsNext.setOnClickListener { grid.next() }

        binding.btnRecoveryKey.setOnClickListener { startActivity(Intent(this, RecoveryKeyActivity::class.java)) }
        binding.btnUnlock.setOnClickListener { startActivity(Intent(this, UnlockActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Library.status.collect(::render) }
                launch { AppList.apps.collect(grid::show) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // An app may have been installed or removed while Soil was away.
        lifecycleScope.launch { AppList.refresh(this@HomeActivity) }
    }

    /** One status, one appearance: the message, and which of the top bar's buttons exist. A
     *  button that cannot act is absent, never greyed — a disabled control is invisible on e-ink. */
    private fun render(status: Library.Status) {
        val route = status.route
        binding.libraryMessage.setText(
            when (route) {
                KeyGate.Route.OPEN -> R.string.library_empty
                KeyGate.Route.PREPARING -> R.string.library_preparing
                KeyGate.Route.RECOVERY_KEY -> R.string.library_save_key
                KeyGate.Route.UNLOCK -> R.string.library_locked
                KeyGate.Route.RESUME_ROTATION -> R.string.library_rotating
                KeyGate.Route.BLOCKED -> when (status.index) {
                    SoilIndex.State.FOREIGN_FILE -> R.string.library_foreign
                    SoilIndex.State.DAMAGED_FILE -> R.string.library_damaged
                    else -> R.string.library_unavailable
                }
            },
        )
        binding.btnRecoveryKey.visibility = if (route == KeyGate.Route.RECOVERY_KEY) View.VISIBLE else View.GONE
        binding.btnUnlock.visibility = if (route == KeyGate.Route.UNLOCK) View.VISIBLE else View.GONE
    }

    /** A home screen has nowhere to go back to. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit
}
