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
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.bootstrap.Screen
import com.symmetricalpalmtree.soil.bootstrap.Screens
import com.symmetricalpalmtree.soil.databinding.OverlayMenuBinding
import com.symmetricalpalmtree.soil.databinding.RowMenuBinding
import com.symmetricalpalmtree.soil.paper.core.Slog

/**
 * **Soil's side menu**: a panel on the right edge, drawn over whatever app is in front.
 *
 * Home and the Scratch Pad first, then every installed app with a launcher entry, each with its
 * own icon, in **fixed pages** turned with previous and next. Nothing scrolls. A tap outside the
 * panel closes it; the panel carries no title and no close button, so that every row of it is
 * something to open.
 *
 * It is an accessibility overlay, which is what lets it sit over any app without a permission of
 * its own, and it never takes the keyboard's focus from the app underneath.
 */
class MenuOverlay(private val service: Context) {

    private val themed = ContextThemeWrapper(service, com.symmetricalpalmtree.soil.paper.R.style.Theme_Soil)
    private val windows = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var binding: OverlayMenuBinding? = null
    private var apps: List<AppEntry> = emptyList()
    private var page = 0

    val isShowing: Boolean get() = binding != null

    fun show(apps: List<AppEntry>) {
        if (binding != null) return
        this.apps = apps
        page = 0
        // Soil's own paper, if it is in front, lets the panel go first.
        runCatching { MenuSignals.beforeMenuShows?.invoke() }

        val b = OverlayMenuBinding.inflate(LayoutInflater.from(themed))
        b.scrim.setOnClickListener { hide() }
        b.btnPrev.setOnClickListener { turnTo(page - 1) }
        b.btnNext.setOnClickListener { turnTo(page + 1) }
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
            // The rows that fit are known only once the panel has a height. Posted, never run
            // inside the layout pass itself: a view added during a pass is not laid out by it.
            b.appRows.doOnLayout { rows -> rows.post { render() } }
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

    private fun perPage(b: OverlayMenuBinding): Int =
        Paging.fit(b.appRows.height, themed.resources.getDimensionPixelSize(R.dimen.menu_row_height))

    private fun turnTo(wanted: Int) {
        val b = binding ?: return
        val to = Paging.clamp(wanted, apps.size, perPage(b))
        if (to == page) return
        page = to
        render()
    }

    private fun render() {
        val b = binding ?: return
        val perPage = perPage(b)
        page = Paging.clamp(page, apps.size, perPage)
        b.appRows.removeAllViews()
        for (app in Paging.slice(apps, page, perPage)) {
            b.appRows.addView(row(b.appRows, app.icon, app.label) { AppList.launch(service, app) })
        }
        val pages = Paging.pageCount(apps.size, perPage)
        // A pager over one page is two buttons that do nothing: it is not shown.
        b.pager.visibility = if (pages > 1) View.VISIBLE else View.GONE
        b.pageText.text = themed.getString(R.string.page_of, page + 1, pages)
    }

    /** One row: an icon, a name, and what a tap does once the menu has closed. */
    private fun row(parent: LinearLayout, icon: Drawable?, label: String, onTap: () -> Unit): View {
        val r = RowMenuBinding.inflate(LayoutInflater.from(themed), parent, false)
        r.icon.setImageDrawable(icon)
        r.icon.scaleType = ImageView.ScaleType.FIT_CENTER
        (r.label as TextView).text = label
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
