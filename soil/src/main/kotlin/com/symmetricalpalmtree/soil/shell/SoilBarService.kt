package com.symmetricalpalmtree.soil.shell

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.symmetricalpalmtree.soil.bootstrap.Screen
import com.symmetricalpalmtree.soil.bootstrap.Screens
import com.symmetricalpalmtree.soil.paper.core.Slog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * **The shell**: Soil's hold on the side bars, in every app.
 *
 * An accessibility service that filters keys sees every key before the app in front does, and the
 * bars are keys. They are **observed, never consumed**: consuming them would also stop the
 * firmware from seeing the swipe, and its refresh on a swipe up is the only sign of direction
 * there is ([BarGesture]).
 *
 * The firmware's own side menu is held shut ([FirmwareMenu]), and a swipe down of the right bar
 * opens Soil's ([MenuOverlay]) over whatever is in front.
 *
 * What stays the firmware's: the refresh on a swipe up and its flash, the pull-down status bar,
 * and the unlock screen at boot.
 *
 * **Soil works without this.** It is turned on over adb, and a firmware update can break the lock
 * it depends on. With it off, or broken, Soil is an ordinary app beside the firmware's menu:
 * nothing else in Soil asks whether the shell is there.
 */
class SoilBarService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var firmwareMenu: FirmwareMenu
    private lateinit var menu: MenuOverlay

    private var rightDownAt = 0L
    private var refreshHeard = false
    private var front: CharSequence? = null

    private val firmware = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                FirmwareMenu.ACTION_REFRESH -> refreshHeard = true
                // Sent for the side menu and for the pull-down status bar alike. Only a recent
                // touch of the right bar makes it the side menu; the status bar is left alone.
                FirmwareMenu.ACTION_MENU_STATE -> if (intent.getBooleanExtra(FirmwareMenu.EXTRA_SHOW, false)) {
                    if (BarGesture.isSideMenuLeak(SystemClock.uptimeMillis() - rightDownAt)) {
                        Slog.d(TAG) { "the firmware's side menu slipped through; locking and taking over" }
                        firmwareMenu.lock()
                        main.postDelayed({ firmwareMenu.lock() }, 400)
                        showMenu()
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        _running.value = true
        firmwareMenu = FirmwareMenu(this)
        menu = MenuOverlay(this)
        registerReceiver(
            firmware,
            IntentFilter().apply {
                addAction(FirmwareMenu.ACTION_REFRESH)
                addAction(FirmwareMenu.ACTION_MENU_STATE)
            },
        )
        firmwareMenu.connect()
        Slog.d(TAG) { "the shell is on" }
    }

    override fun onDestroy() {
        _running.value = false
        if (::menu.isInitialized) menu.hide()
        if (::firmwareMenu.isInitialized) firmwareMenu.disconnect()
        runCatching { unregisterReceiver(firmware) }
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        Slog.d(TAG) { "the shell is off" }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** Every change of the app in front clears the firmware's lock; two re-locks are landed
     *  after its own handler has run. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName ?: return
        if (pkg == FirmwareMenu.LAUNCHER) return
        // Our own overlay arriving is not a change of the app in front.
        if (pkg == packageName && menu.isShowing) return
        if (pkg == front) return
        front = pkg
        Slog.d(TAG) { "in front: $pkg" }

        if (BootTakeBack.isPush(pkg.toString(), BootTakeBack.personAskedForNotes, SystemClock.elapsedRealtime())) {
            Slog.d(TAG) { "the firmware pushed its Notes over the home screen after boot; taking it back" }
            main.postDelayed({ Screens.open(this, Screen.HOME) }, 300)
        }
        main.postDelayed({ firmwareMenu.lock() }, 300)
        main.postDelayed({ firmwareMenu.lock() }, 1200)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!BarGesture.isBarKey(event.keyCode)) return false
        if (event.keyCode == BarGesture.RIGHT_FIRST) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    rightDownAt = event.eventTime
                    refreshHeard = false
                    firmwareMenu.lock()
                }
                KeyEvent.ACTION_UP -> {
                    val held = event.eventTime - rightDownAt
                    main.postDelayed({ act(held) }, BarGesture.SETTLE_MS)
                }
            }
        }
        // Observed only: the firmware must still see the swipe, so that its refresh says "up".
        return false
    }

    private fun act(heldMs: Long) {
        when (BarGesture.read(heldMs, refreshHeard)) {
            BarGesture.Read.TAP -> Unit
            BarGesture.Read.SWIPE_UP -> Unit   // the firmware's refresh
            BarGesture.Read.SWIPE_DOWN -> showMenu()
        }
    }

    private fun showMenu() = menu.show()

    companion object {
        private const val TAG = "SoilBars"

        private val _running = MutableStateFlow(false)

        /** Whether the shell is on. Followed only to say so on the home screen. */
        val running: StateFlow<Boolean> get() = _running
    }
}
