package com.symmetricalpalmtree.soil.paper.recognition

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.symmetricalpalmtree.soil.paper.R

/**
 * The "Recognising…" box shown while ink is out at the recogniser: a box, not a dialog, so only
 * its region repaints and the page under it stays. The root is transparent, clickable and
 * focusable: the second tap a slow refresh invites lands on the shield. Both calls are
 * idempotent; the view is found by tag in the activity's own tree, never held in a map.
 */
object RecognizingOverlay {

    private const val TAG_KEY = "soil.recognizingOverlay"

    fun show(activity: Activity, messageRes: Int = R.string.recognize_recognizing) {
        if (activity.isFinishing || activity.isDestroyed) return
        val overlay = obtain(activity) ?: return
        overlay.findViewById<TextView>(R.id.message)?.setText(messageRes)
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
    }

    /** The same box with words of the caller's own: "Reading page 3 of 12…". */
    fun show(activity: Activity, message: CharSequence) {
        if (activity.isFinishing || activity.isDestroyed) return
        val overlay = obtain(activity) ?: return
        overlay.findViewById<TextView>(R.id.message)?.text = message
        overlay.visibility = View.VISIBLE
        overlay.bringToFront()
    }

    fun hide(activity: Activity) {
        activity.findViewById<ViewGroup>(android.R.id.content)?.findViewWithTag<View>(TAG_KEY)?.visibility = View.GONE
    }

    private fun obtain(activity: Activity): View? {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        content.findViewWithTag<View>(TAG_KEY)?.let { return it }
        val overlay = LayoutInflater.from(activity).inflate(R.layout.overlay_recognizing, content, false)
        overlay.tag = TAG_KEY
        content.addView(overlay)
        return overlay
    }
}
