package com.symmetricalpalmtree.soil.importing

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import com.symmetricalpalmtree.soil.R

/**
 * A full-screen click-eater over a screen while something long runs, naming its stage. Work
 * that can be stopped between its steps offers Cancel under the line ([onCancel], cleanup,
 * 2026-10-10): one tap, said once, the work told and left to stop at its next step; the overlay
 * stays up until it has.
 */
object ImportOverlay {

    private const val TAG_KEY = "soil.importOverlay"

    fun show(activity: Activity, @StringRes textRes: Int, onCancel: (() -> Unit)? = null) {
        val overlay = obtain(activity) ?: return
        overlay.findViewById<TextView>(R.id.importStage)?.setText(textRes)
        overlay.findViewById<View>(R.id.importCancel)?.apply {
            visibility = if (onCancel == null) View.GONE else View.VISIBLE
            setOnClickListener(if (onCancel == null) null else View.OnClickListener { visibility = View.GONE; onCancel() })
        }
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
        overlay.invalidate()
    }

    fun stage(activity: Activity, @StringRes textRes: Int) = stage(activity, activity.getString(textRes))

    fun stage(activity: Activity, text: CharSequence) {
        val overlay = find(activity) ?: return
        if (overlay.visibility != View.VISIBLE) return
        overlay.findViewById<TextView>(R.id.importStage)?.text = text
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
