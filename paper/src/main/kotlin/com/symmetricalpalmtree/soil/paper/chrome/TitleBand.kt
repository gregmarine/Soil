package com.symmetricalpalmtree.soil.paper.chrome

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The item's name on a bottom bar: at the start, ellipsised before it reaches the pager that
 * holds the screen's centre (Greg, 2026-10-10). The bar is a FrameLayout so the pager is centred
 * on the SCREEN whatever flanks it; the name's end margin is set from the pager's laid-out width,
 * so the two never meet. Call after each layout of the bar; unchanged is silent.
 */
object TitleBand {
    fun keepClearOfPager(bar: FrameLayout, title: TextView, pager: View) {
        if (bar.width == 0 || pager.width == 0) return
        val lp = title.layoutParams as FrameLayout.LayoutParams
        val gap = (8 * bar.resources.displayMetrics.density).toInt()
        val end = (bar.width + pager.width) / 2 + gap - bar.paddingStart
        if (lp.marginEnd == end && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) return
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        lp.marginEnd = end
        title.layoutParams = lp
    }
}
