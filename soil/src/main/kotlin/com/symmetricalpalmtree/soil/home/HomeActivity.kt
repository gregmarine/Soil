package com.symmetricalpalmtree.soil.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.bootstrap.KeyGate
import com.symmetricalpalmtree.soil.bootstrap.Library
import com.symmetricalpalmtree.soil.bootstrap.RecoveryKeyActivity
import com.symmetricalpalmtree.soil.bootstrap.Screen
import com.symmetricalpalmtree.soil.bootstrap.Screens
import com.symmetricalpalmtree.soil.bootstrap.UnlockActivity
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.databinding.ActivityHomeBinding
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.shell.AppEntry
import com.symmetricalpalmtree.soil.shell.AppList
import com.symmetricalpalmtree.soil.shell.HiddenApps
import com.symmetricalpalmtree.soil.shell.SoilBarService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * **The home screen**: two views under one top bar.
 *
 *  - **The library**, which it opens on. Empty until a Sprout app can make an item.
 *  - **The app drawer**: the installed apps the person has not hidden, each with its own icon,
 *    in fixed pages. A long press on an app asks whether to hide it; the hidden apps screen
 *    brings it back.
 *
 * It renders [Library.status] rather than waiting for it. Soil is the device's home screen: it
 * cannot forward to a bootstrap and finish, and it must never trap the person. The drawer needs
 * no key, so it works while the library is locked, being prepared, or out of reach.
 *
 * It does not touch the side bars or the firmware's menu at all — that is the bar service's work,
 * and Soil runs as an ordinary app without it.
 */
class HomeActivity : AppCompatActivity() {

    private enum class Showing { LIBRARY, APPS }

    private lateinit var binding: ActivityHomeBinding
    private lateinit var grid: AppGrid
    private var showing = Showing.LIBRARY
    private var appPages = 1

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
            onHold = ::askToHide,
            onPaged = { page, pages ->
                appPages = pages
                binding.pageText.text = getString(R.string.page_of, page + 1, pages)
                renderPager()
            },
        )
        binding.btnPrev.setOnClickListener { grid.previous() }
        binding.btnNext.setOnClickListener { grid.next() }

        binding.btnLibrary.setOnClickListener { show(Showing.LIBRARY) }
        binding.btnApps.setOnClickListener { show(Showing.APPS) }
        binding.btnHiddenApps.setOnClickListener { startActivity(Intent(this, HiddenAppsActivity::class.java)) }
        // Through the gate: while the key is unsaved or the library locked, these lead to the
        // screen that opens it.
        binding.btnScratchPad.setOnClickListener { Screens.open(this, Screen.PAD) }
        binding.btnEncryption.setOnClickListener { Screens.open(this, Screen.ENCRYPTION) }
        binding.btnRecoveryKey.setOnClickListener { startActivity(Intent(this, RecoveryKeyActivity::class.java)) }
        binding.btnUnlock.setOnClickListener { startActivity(Intent(this, UnlockActivity::class.java)) }
        // Every icon button names itself on a long press.
        listOf(binding.btnLibrary, binding.btnApps, binding.btnHiddenApps, binding.btnScratchPad, binding.btnEncryption)
            .forEach { TooltipCompat.setTooltipText(it, it.contentDescription) }

        show(savedInstanceState?.getString(KEY_SHOWING)?.let { name -> Showing.values().firstOrNull { it.name == name } } ?: Showing.LIBRARY)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Library.status.collect(::render) }
                launch {
                    SoilBarService.running.collect { on ->
                        binding.shellNote.visibility = if (on) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    combine(AppList.apps, HiddenApps.hidden, HiddenApps::visible).collect { apps ->
                        binding.appsEmpty.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
                        grid.show(apps)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // An app may have been installed or removed while Soil was away.
        lifecycleScope.launch { AppList.refresh(this@HomeActivity) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SHOWING, showing.name)
    }

    /** One of the two views, and what of the bars belongs to it. */
    private fun show(view: Showing) {
        showing = view
        val apps = view == Showing.APPS
        binding.libraryView.visibility = if (apps) View.GONE else View.VISIBLE
        binding.appsView.visibility = if (apps) View.VISIBLE else View.GONE
        binding.btnHiddenApps.visibility = if (apps) View.VISIBLE else View.GONE
        // The view that is showing wears a border; nothing is ever greyed.
        binding.btnLibrary.isSelected = !apps
        binding.btnApps.isSelected = apps
        renderPager()
    }

    /** The pager is the drawer's for now; the library has nothing to page. A pager over one page
     *  is two buttons that do nothing, so it is not shown. */
    private fun renderPager() {
        binding.bottomBar.visibility = if (showing == Showing.APPS && appPages > 1) View.VISIBLE else View.GONE
    }

    /** A long press asks; it never acts. */
    private fun askToHide(app: AppEntry) {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.hide_app_title, app.label))
                .setMessage(R.string.hide_app_body)
                .setPositiveButton(R.string.hide_app_confirm) { _, _ -> HiddenApps.hide(this, app) }
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create()
        ).show()
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

    private companion object { const val KEY_SHOWING = "showing" }
}
