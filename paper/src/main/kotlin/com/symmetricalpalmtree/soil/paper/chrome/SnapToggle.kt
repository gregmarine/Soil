package com.symmetricalpalmtree.soil.paper.chrome

import android.content.Context
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.soil.paper.R

/**
 * Snap to guides, SN's arc 9: a dragged selection is pulled onto the page's own structure, and a
 * dashed rule is drawn edge to edge wherever it caught. **The guides are g-paper's**
 * (`PaperView.snapToGuides`, `SnapEngine`): the engine owns every drag sample and the drag
 * layer's drawing, so the host's whole share is this — the remembered flag, the margin's seed,
 * and the button on the selection bar.
 *
 * Off by default. One flag for the app, remembered on the device: snapping is a way of working,
 * not a property of a page, and on e-ink a process kill is routine, so a session-only memory
 * would read as the setting forgetting itself. [toggle] writes the live flag and the durable one
 * together so they can never disagree. Nothing on the page moves when it flips: it governs the
 * next drag.
 *
 * The margin is **one toolbar thick**: seeded here from the bar's dimen, then set from the top
 * bar's real laid-out height on every [PaperChrome.pushExclusions] ([SnapMargin]), so content
 * snapped to a page margin lands exactly where the chrome ends.
 */
class SnapToggle(context: Context, private val paper: PaperView) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        paper.snapMarginPx = context.resources.getDimensionPixelSize(R.dimen.toolbar_bar_thickness).toFloat()
        paper.snapToGuides = prefs.getBoolean(KEY_ENABLED, false)
    }

    val isOn: Boolean get() = paper.snapToGuides

    fun toggle() {
        val next = !paper.snapToGuides
        paper.snapToGuides = next
        prefs.edit().putBoolean(KEY_ENABLED, next).apply()
    }

    /** The selection bar's Snap button, built to the floating-bar recipe: a tap flips the flag and
     *  re-draws the button. No toast: the border is the confirmation. */
    fun button(ctx: Context, releaseRender: () -> Unit): AppCompatImageButton {
        lateinit var b: AppCompatImageButton
        b = AnchoredBar.button(ctx, R.drawable.ic_snap, ctx.getString(R.string.snap_action_off)) {
            releaseRender(); toggle(); sync(b)
        }
        sync(b)
        return b
    }

    /** Draw the state: the selected border is how an armed tool shows, and the hint says it in
     *  words, since a border alone is a thing you have to have been told about. */
    fun sync(button: AppCompatImageButton) {
        val on = isOn
        button.isSelected = on
        val hint = button.context.getString(if (on) R.string.snap_action_on else R.string.snap_action_off)
        button.contentDescription = hint
        TooltipCompat.setTooltipText(button, hint)
    }

    private companion object {
        const val FILE = "snap"
        const val KEY_ENABLED = "enabled"
    }
}

/**
 * The snap margin from the top bar, pure. The top bar is the button row **plus** its border, so
 * its laid-out height, not the row's dimen, is "one toolbar": snapped to the dimen alone, an object
 * would sit behind the bar's black rule.
 *
 * Deliberately not visibility-aware: while the chrome is hidden the bar keeps its last height, and
 * that stale height is the wanted value. An object snapped then still clears the bar when it comes
 * back; the margin is page space, not chrome state. Null before the bar's first layout, when the
 * seed stands.
 */
object SnapMargin {
    fun fromTopBar(heightPx: Int): Float? = heightPx.takeIf { it > 0 }?.toFloat()
}
