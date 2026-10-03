package com.symmetricalpalmtree.soil.importing

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import com.symmetricalpalmtree.soil.R

/** A full-screen click-eater over the home screen while an import runs, naming its stage. */
object ImportOverlay {

    private const val TAG_KEY = "soil.importOverlay"

    fun show(activity: Activity, @StringRes textRes: Int) {
        val overlay = obtain(activity) ?: return
        overlay.findViewById<TextView>(R.id.importStage)?.setText(textRes)
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        overlay.invalidate()
    }

    fun stage(activity: Activity, @StringRes textRes: Int) {
        val overlay = find(activity) ?: return
        if (overlay.visibility != View.VISIBLE) return
        overlay.findViewById<TextView>(R.id.importStage)?.setText(textRes)
    }

    fun hide(activity: Activity) { find(activity)?.visibility = View.GONE }

    private fun find(activity: Activity): View? = activity.findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag(TAG_KEY)

    private fun obtain(activity: Activity): View? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        find(activity)?.let { return it }
        val overlay = LayoutInflater.from(activity).inflate(R.layout.overlay_import, content, false)
        overlay.tag = TAG_KEY
        content.addView(overlay)
        return overlay
    }
}
