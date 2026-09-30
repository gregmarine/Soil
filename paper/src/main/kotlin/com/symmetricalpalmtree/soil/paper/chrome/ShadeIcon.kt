package com.symmetricalpalmtree.soil.paper.chrome

import android.content.Context
import android.graphics.drawable.LayerDrawable
import android.widget.ImageButton
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.symmetricalpalmtree.soil.paper.R

/**
 * **How a pen button reports its shade**: Tabler's outline, solid black as every glyph is, over
 * its own body filled with the shade the pen is set to. Black reads as a black glyph; any other
 * level as a black outline with that grey inside it.
 *
 * Greys are ink, and the one opening for ink in chrome is the pen button's fill beside the
 * swatches that choose it. The outline stays black so a pale pen never goes missing on the
 * panel: the fill path is the outline's own body closed, and the outline's stroke is centred on
 * it, so no shade bleeds past the glyph. The ARGB **is** the token a repaint compares.
 */
object ShadeIcon {

    private const val FILL = 0

    /** The pen glyph with its barrel in [ink]. Fresh per call: a drawable in two views fights
     *  over its bounds. */
    fun pen(ctx: Context, ink: Int): LayerDrawable =
        filled(ctx, outlineRes = R.drawable.ic_pen, fillRes = R.drawable.ic_pen_fill, ink = ink)

    fun filled(ctx: Context, @DrawableRes outlineRes: Int, @DrawableRes fillRes: Int, ink: Int): LayerDrawable {
        // Mutated before it is tinted, so the tint reaches no other instance of the resource.
        val fill = checkNotNull(AppCompatResources.getDrawable(ctx, fillRes)).mutate()
        val outline = checkNotNull(AppCompatResources.getDrawable(ctx, outlineRes))
        return LayerDrawable(arrayOf(fill, outline)).also { tint(it, ink) }
    }

    /** Re-ink a glyph [filled] made. */
    fun tint(icon: LayerDrawable, ink: Int) {
        DrawableCompat.setTint(icon.getDrawable(FILL), ink)
    }
}

/**
 * A pen button wearing its shade: [ShadeIcon.pen] swapped onto the button once, and re-inked on
 * every change. **Silent on a repeat**: a sync that passes the same tone again repaints nothing.
 */
class PenShadeGlyph(button: ImageButton, ink: Int) {

    private val icon: LayerDrawable = ShadeIcon.pen(button.context, ink).also { button.setImageDrawable(it) }

    /** The ARGB the button is wearing: the token every repaint compares. */
    var ink: Int = ink
        private set

    fun report(ink: Int) {
        if (ink == this.ink) return
        this.ink = ink
        ShadeIcon.tint(icon, ink)
    }
}
