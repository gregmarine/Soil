package com.symmetricalpalmtree.soil.shell

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.bootstrap.Screen
import com.symmetricalpalmtree.soil.bootstrap.Screens
import com.symmetricalpalmtree.soil.databinding.OverlayMenuBinding
import com.symmetricalpalmtree.soil.databinding.RowMenuBinding
import com.symmetricalpalmtree.soil.library.ItemApps
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.seam.SeamClients

/**
 * **Soil's side menu**: a panel on the right edge, drawn over whatever app is in front.
 *
 * It holds what Soil itself offers from anywhere, **Home** and the **Scratch Pad**, and then a
 * row for each Sprout app installed. The other apps are not listed here; they are in the home
 * screen's app drawer.
 *
 * A paper screen holds the panel, so before the panel is drawn over, whatever is in front is
 * asked to let it go: Soil's own paper through [MenuSignals], a Sprout app's through the seam
 * ([SeamClients]). The ask across the seam waits on another thread, and the menu is added once
 * it is answered.
 *
 * A tap outside the panel closes it. The panel carries no title and no close button, so that
 * every row of it is something to open.
 *
 * It is an accessibility overlay, which is what lets it sit over any app without a permission of
 * its own, and it never takes the keyboard's focus from the app underneath.
 */
class MenuOverlay(private val service: Context) {

    private val themed = ContextThemeWrapper(service, com.symmetricalpalmtree.soil.paper.R.style.Theme_Soil)
    private val windows = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val main = Handler(Looper.getMainLooper())
    private var binding: OverlayMenuBinding? = null
    private var preparing = false

    val isShowing: Boolean get() = binding != null

    fun show() {
        if (binding != null || preparing) return
        preparing = true
        // Soil's own paper, if it is in front, lets the panel go first.
        runCatching { MenuSignals.beforeMenuShows?.invoke() }
        // Then an app's, across the seam, off this thread. The rows are read there too.
        Thread {
            SeamClients.releasePanel()
            val apps = ItemApps.sproutApps(service)
            main.post {
                preparing = false
                if (binding == null) add(apps)
            }
        }.start()
    }

    private fun add(apps: List<ItemApps.SproutApp>) {
        val b = OverlayMenuBinding.inflate(LayoutInflater.from(themed))
        b.scrim.setOnClickListener { hide() }
        // Every row is in place before the window is added: nothing is built after the first
        // frame, so nothing can be left undrawn.
        b.ownRows.addView(row(b.ownRows, icon(com.symmetricalpalmtree.soil.paper.R.drawable.ic_home), themed.getString(R.string.menu_home)) {
            Screens.open(service, Screen.HOME)
        })
        b.ownRows.addView(row(b.ownRows, icon(com.symmetricalpalmtree.soil.paper.R.drawable.ic_sketching), themed.getString(R.string.scratch_title)) {
            // The pad is a paper screen of Soil's: the app in front releases the pipeline first.
            afterHandoff { Screens.open(service, Screen.PAD) }
        })
        for (app in apps) {
            val launch = app.launch ?: continue
            b.ownRows.addView(row(b.ownRows, app.icon, app.label) {
                runCatching { service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Log.w(TAG, "an app could not be started: ${it.javaClass.simpleName}") }
            })
        }

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windows.addView(b.root, params)
            binding = b
            Slog.d(TAG) { "menu shown" }
        } catch (e: Exception) {
            Log.w(TAG, "the menu could not be shown: ${e.javaClass.simpleName}")
        }
    }

    /** Ask the app in front to release for a paper screen of Soil's, then run [then] on Main. */
    private fun afterHandoff(then: () -> Unit) {
        Thread {
            SeamClients.releaseForHandoff()
            main.post(then)
        }.start()
    }

    fun hide() {
        val b = binding ?: return
        binding = null
        runCatching { windows.removeView(b.root) }
        Slog.d(TAG) { "menu hidden" }
    }

    /** One row: an icon, a name, and what a tap does once the menu has closed. */
    private fun row(parent: LinearLayout, icon: Drawable?, label: String, onTap: () -> Unit): View {
        val r = RowMenuBinding.inflate(LayoutInflater.from(themed), parent, false)
        r.icon.setImageDrawable(icon)
        r.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        r.label.text = label
        r.root.contentDescription = label
        r.root.setOnClickListener {
            hide()
            runCatching { onTap() }.onFailure { Log.w(TAG, "a menu row failed: ${it.javaClass.simpleName}") }
        }
        return r.root
    }

    private fun icon(res: Int): Drawable? = ContextCompat.getDrawable(themed, res)

    private companion object { const val TAG = "MenuOverlay" }
}
