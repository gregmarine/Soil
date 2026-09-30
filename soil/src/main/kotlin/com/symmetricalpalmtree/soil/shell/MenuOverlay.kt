package com.symmetricalpalmtree.soil.shell

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
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
import com.symmetricalpalmtree.soil.paper.core.Slog

/**
 * **Soil's side menu**: a panel on the right edge, drawn over whatever app is in front.
 *
 * It holds what Soil itself offers from anywhere: **Home** and the **Scratch Pad**. The installed
 * apps are not listed here; they are in the home screen's app drawer. The Sprout apps take their
 * places as they arrive.
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

    private var binding: OverlayMenuBinding? = null

    val isShowing: Boolean get() = binding != null

    fun show() {
        if (binding != null) return
        // Soil's own paper, if it is in front, lets the panel go first.
        runCatching { MenuSignals.beforeMenuShows?.invoke() }

        val b = OverlayMenuBinding.inflate(LayoutInflater.from(themed))
        b.scrim.setOnClickListener { hide() }
        // Every row is in place before the window is added: nothing is built after the first
        // frame, so nothing can be left undrawn.
        b.ownRows.addView(row(b.ownRows, icon(com.symmetricalpalmtree.soil.paper.R.drawable.ic_home), themed.getString(R.string.menu_home)) {
            Screens.open(service, Screen.HOME)
        })
        b.ownRows.addView(row(b.ownRows, icon(com.symmetricalpalmtree.soil.paper.R.drawable.ic_sketching), themed.getString(R.string.scratch_title)) {
            Screens.open(service, Screen.PAD)
        })

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
