package com.symmetricalpalmtree.soil.paper.chrome

import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.R

/**
 * The collapsed chrome (arc 36) — what a paper screen shows while its bars are hidden: one
 * floating **corner button** at the top-right wearing the armed tool's glyph, and, hung under it
 * on a tap, a **mini toolbar** as a **column** (2026-10-10): [leading] — Back — at the top, then
 * the screen's [tools], [CollapsedTools.ORDER] by default, the armed one bordered, then the
 * screen's [commands], then its [overflow] — the screen's doors and actions — top to bottom. The column holds as
 * many buttons as the band under the corner button has room for ([AnchoredBar.columnCapacity],
 * [CollapsedTools.firstColumn]); what is left goes to a **second column** beside it, to its left
 * and level with its top, shown on the same tap. There is no `…`: the corner button reveals the
 * whole toolbar. On the Nomad every screen's buttons fit one column, so the second is a rule, not
 * a sight.
 *
 * One copy for the four screens, here in `:sn-screen`, for the reason [EraserBar] and
 * [ChromeToggle] live here: a second copy would be the `RattaNotebookView` sibling-copy trap one
 * file at a time. The screen says only what differs — its commands, its overflow entries, and
 * when the button may open — and owns the rest of the chrome plumbing exactly as it does for its
 * other floating bars: the corner button and both rows go into its exclusion rects and its
 * `overChrome` test ([rects] / [contains]), its outside-contact dismissal calls
 * [dismissOnContact], and every place its other floating bars go down calls [dismiss].
 *
 * **The corner button is honest by construction.** Nothing here caches the armed tool: every
 * repaint ([sync]) reads `paper.tool`, so a caller only ever says "repaint", never "repaint as
 * X" — a stale copy has no way to exist. The one call site is the bar's own `sync` funnel
 * (`PaperToolbar` / `NotebookToolbar`'s `onSynced`), which every tool change already passes
 * through: a bar tap, `arm`, every by-hand sync, and `onToolChanged`. The glyph is
 * [CollapsedTools.iconFor], swapped only on a change (frame silence). The button itself is
 * `GONE` while the bars show — [ChromeToggle]'s `whileHidden` list flips it with the bars.
 *
 * **Entries mirror bar buttons** ([Entry.mirrors]). At every open an entry copies its
 * bar button's visibility (a door absent from the bar is absent here), its selected look (the
 * calendar's view latches) and, only when it actually differs, its glyph (the calendar's
 * Send-or-Export, decided once at that bar's construction). A tap on a mirrored entry with no
 * [Entry.onTap] closes the rows and performs the bar button's own click — one handler, never a
 * copy of it. An entry with [Entry.onTap] is handed its own button as an anchor and owns
 * dismissal: the notebook's Insert hangs its sub-bar beside the column, level with the button
 * ([AnchoredBar.show]'s column rule), and leaves the columns up. The second column is taken down
 * first when the anchor is in the first ([hideOverflow]): the sub-bar goes where it would stand.
 *
 * **The pen may be two kinds** since arc 44 / T3 ([PenKinds]) — the sketch face's graphite pencil
 * and its gel pen, which are both [Tool.PEN] to the engine and so cannot be two entries of [tools].
 * The second button is built right after the primary one, the glyph is what says which is armed
 * (here and on the corner button), and a pick of the already-armed primary kind is a re-tap the
 * screen answers by hanging its own bar under this row's button.
 *
 * **…and the primary kind's glyph may be the screen's own** ([PenKinds.primaryIcon]): the sketch
 * face paints its pencil with the armed shade in its body, so the row and the corner button report
 * what a stroke will look like. Nothing here knows what the picture means — it is asked for a
 * [PenIcon] at every repaint and swaps only when the token changes (frame silence), exactly as the
 * resource glyphs do.
 *
 * **A screen with one pen may paint it and answer its re-tap too** (arc 49 / P4 — the writing
 * faces' shade panel): [penIcon] is the one pen's painted glyph, on the row's button always and on
 * the corner button while the pen is armed, and [onPenReTap] is what a pick of the already-armed
 * pen does — handed this row's own button as an anchor, the rows staying up beneath it, exactly
 * [PenKinds.onReTap]'s contract for one kind. Both null on a screen that has neither, which is
 * every screen before P4 and every one that never grows a shade.
 *
 * **Sub-bars hung off the rows go with them.** [onClose] fires before either row is taken down by
 * anything — the corner button's re-tap, the `…` re-tap, a mirrored entry, the screen's own
 * [dismiss] — so the notebook's Insert bar and tags popup never outlive the row they hang under.
 * It fires raw, before the one exclusion push ([onChanged]), so a close is one binder call.
 *
 * **Render release**: opening a row is one chrome frame at a deliberate tap with the pen still
 * hovering — an ungated `releaseRender()`, the Insert bar's rule; a tool pick releases through
 * the screen's own `toolbar.arm` ([onArmed]), pen-gated, exactly once. Nothing here is
 * `whenPenIdle`-gated.
 */
class CollapsedChrome(
    root: ViewGroup,
    /** The corner button — declared in the screen's layout at `top|end`, `GONE` until collapsed. */
    private val knob: ImageButton,
    private val miniBar: LinearLayout,
    private val overflowBar: LinearLayout,
    private val paper: PaperView,
    /** The free band's bottom edge in root coordinates ([ChromeBand]); null before layout. */
    bandBottom: () -> Int?,
    /** Whether a tap on the corner button may open the mini toolbar — the screen's `opened &&
     *  !closing` (and the notebook's `canvasShown`). */
    private val canOpen: () -> Boolean,
    /** Buttons after the tools on the mini toolbar (the notebook's Insert). */
    commands: List<Entry> = emptyList(),
    /** Buttons **before** the tools — Back, at the top of the column (Greg, 2026-10-10). */
    leading: List<Entry> = emptyList(),
    /** The entries after the commands, in order — Back and the screen's doors and actions. */
    overflow: List<Entry> = emptyList(),
    /** Fires before a row opens — the screen takes down its other floating popups. */
    private val onOpen: () -> Unit = {},
    /** Fires before a row closes, by any path — the screen takes down the sub-bars it hung off
     *  the rows, **without** pushing exclusions: [onChanged] follows. */
    private val onClose: () -> Unit = {},
    /** Fires after a pick — the screen arms [Tool] on its own toolbar (`toolbar.arm`), which
     *  releases the render pen-gated, syncs the bar and, through the bar's `onSynced`, this. */
    private val onArmed: (Tool) -> Unit,
    /** Fires after every open / close — the screen re-pushes its exclusion rects. */
    private val onChanged: () -> Unit,
    /**
     * Which tool buttons the mini toolbar carries, in order (arc 43 / K2). The default is
     * [CollapsedTools.ORDER] — the four every writing screen has: pen, the two erasers, lasso — and it is the whole
     * of the parameter's reason: a screen with **two** tools (the sketch surface's pencil and its
     * rubbing eraser; there is no lasso on a raster page and nothing to select) would otherwise
     * show two buttons that arm tools its surface does not have. Last in the list and defaulted, so
     * every existing caller compiles unchanged.
     */
    tools: List<Tool> = CollapsedTools.ORDER,
    /**
     * The PEN slot's **two kinds** (arc 44 / T3), or null on every screen with one pen. It is one
     * parameter rather than "a second tool" because the two are not two tools: the sketch face's
     * pencil and its gel pen are both [Tool.PEN], so they cannot be two entries of a `List<Tool>`
     * (the buttons are keyed by tool, and a second `PEN` would simply replace the first). The alt
     * button is built immediately after the primary one, which makes the sketch face's row read
     * Pencil · Pen · Eraser — the top bar's own order.
     */
    private val penKinds: PenKinds? = null,
    /**
     * The one pen's painted glyph on a screen with **one** pen (arc 49 / P4), or null to wear the
     * plain resource one. [PenKinds.primaryIcon]'s rule and reason for a screen that has no second
     * kind: the writing faces' ballpen wears its shade as a fill under a solid outline. Ignored
     * when [penKinds] is given — that screen's glyphs are the kinds'.
     */
    private val penIcon: (() -> PenIcon)? = null,
    /**
     * A pick of the **already-armed** one pen on a screen with one pen (arc 49 / P4), handed this
     * row's own button as an anchor — [PenKinds.onReTap]'s contract exactly: the caller hangs its
     * shade panel under the button the person actually tapped, the rows stay up beneath it, and
     * the caller owns that bar's dismissal (`onClose` and `keep`). Absent, a re-pick simply arms
     * again and closes the rows like any other tool tap, as it always did. Ignored when
     * [penKinds] is given.
     */
    private val onPenReTap: ((anchor: View) -> Unit)? = null,
) {

    /**
     * What the mini toolbar needs to offer **several kinds of one tool** (arc 44 / T3; N kinds
     * since Sketchsprout's marker, 2026-10-08): each kind's glyph and hint, which kind is armed,
     * how to arm one, and what a pick of the already-armed kind does. A kind is an **index** into
     * [kinds]: 0 is the primary pen button, the rest follow it in bar order.
     *
     * [armed] is read at every repaint and never cached here, for the reason nothing else here
     * is cached either — the screen owns the answer and a copy of it is a copy that can be stale.
     */
    class PenKinds(
        /** Every kind in bar order, the primary first. At least two. */
        val kinds: List<Kind>,
        /** Which kind is the armed one. Read, never stored. */
        val armed: () -> Int,
        /** Arm a kind — the screen applies it and then arms [Tool.PEN] on its own toolbar
         *  (`toolbar.arm`), which does the one pen-gated render release and the syncs. */
        val onPick: (kind: Int) -> Unit,
        /**
         * A pick of the **already-armed** kind, handed which kind and this row's own button as an
         * anchor — [Entry.onTap]'s contract, and the notebook Insert bar's precedent: the caller
         * hangs its sub-bar under the button the person actually tapped and **the rows stay up
         * beneath it**, which also means the caller owns that bar's dismissal (`onClose` brings it
         * down with the rows, and the screen's outside-contact rule keeps it alive under a contact
         * of its own). Absent, a re-pick simply arms again and closes the rows like any other tool
         * tap.
         */
        val onReTap: ((kind: Int, anchor: View) -> Unit)? = null,
    ) {
        /**
         * One kind: its resource glyph and hint (the screen's words — `R.string.tool_pen` says
         * "Pen", and on the sketch face the primary kind is the *pencil*), and, optionally, a
         * glyph **painted by the screen** at every repaint (arc 44 / T3, arc 46 "Palette"): the
         * sketch face's kinds report the armed shade by **filling the glyph's body with it** under
         * an outline that stays solid black. That is the colour rule's one standing opening said
         * in its fill form — the root `CLAUDE.md`'s "the pen button's icon tinted with the armed
         * ink": greys and colours are **ink**, and a control may carry the armed ink only where
         * the ink itself is what is being chosen or reported. Nothing else on any bar may take a
         * colour, and this row is not a precedent for one. Asked at every repaint and never
         * cached, like [armed] and for the same reason.
         */
        class Kind(val iconRes: Int, val hint: String, val icon: (() -> PenIcon)? = null)
    }

    /**
     * One painted glyph and the number that says which one it is (arc 44 / T3).
     *
     * The two halves are separate because they are wanted at different moments: [token] is read at
     * **every** repaint to decide whether anything changed (one integer compare, no allocation —
     * the frame-silence rule this class keeps for its resource glyphs), and [newDrawable] is called
     * only when it did. It mints a fresh instance per call on purpose: the corner button and the
     * row's own button are two views, and one `Drawable` in both would have them fighting over its
     * bounds and its callback.
     *
     * Two glyphs that look the same must carry the same token, and two that differ must not — the
     * sketch face passes the ARGB of the shade it is reporting, which is both by construction.
     */
    class PenIcon(val token: Int, val newDrawable: () -> Drawable)

    /**
     * One mini-toolbar command or overflow-row entry. [mirrors] is the bar button it stands for,
     * read at every open of its row (visibility, selected look, glyph). [onTap] receives the
     * entry's own button as an anchor and owns dismissal; absent, a tap dismisses the rows and
     * performs [mirrors]' own click.
     */
    class Entry(
        val iconRes: Int,
        val hint: String,
        val mirrors: View? = null,
        val onTap: ((anchor: View) -> Unit)? = null,
    ) {
        companion object {
            /** An entry standing for [button], its hint the button's own content description —
             *  the row's long press says what the bar's long press says, and a bar whose wording
             *  is decided at runtime (the calendar's out-door) is read, never copied. */
            fun mirroring(iconRes: Int, button: View, onTap: ((anchor: View) -> Unit)? = null): Entry =
                Entry(iconRes, button.contentDescription?.toString().orEmpty(), button, onTap)
        }
    }

    private val mini = AnchoredBar(root, miniBar, knob, bandBottom)
    private val more = AnchoredBar(root, overflowBar, knob, bandBottom)

    private val toolButtons = LinkedHashMap<Tool, AppCompatImageButton>()
    /** The PEN slot's further kinds, when the screen offers them (kind 1 first) — kept apart from
     *  [toolButtons] because they are keyed by nothing: each is the same [Tool.PEN] the primary
     *  button arms. */
    private val extraPenButtons = ArrayList<AppCompatImageButton>()
    /** Every mirrored entry — both columns open on one tap, so one open refreshes them all. */
    private val mirrored = ArrayList<Pair<AppCompatImageButton, Entry>>()
    /** Every button in column order, tools first; cut between the two columns at every open. */
    private val buttons = ArrayList<AppCompatImageButton>()
    /** How many of [buttons] the first column held at the last open; the cut is redone only when
     *  it changes (frame silence — a re-add of the same views is a layout for nothing). */
    private var lastCut = -1
    private val buttonSize = root.resources.getDimensionPixelSize(R.dimen.toolbar_button_size)

    private var knobIcon = 0
    /** The painted glyphs' tokens, last swapped in — null while the button wears a plain resource
     *  one, which is every screen but the sketch face and every tool but the primary pen. */
    private var knobToken: Int? = null
    private var primaryPenToken: Int? = null
    private val extraPenTokens = ArrayList<Int?>()
    private var clipboardLoaded = false

    val isShowing: Boolean get() = mini.isShowing

    init {
        val ctx = root.context
        knob.contentDescription = ctx.getString(R.string.collapsed_tools)
        TooltipCompat.setTooltipText(knob, knob.contentDescription)
        knob.setOnClickListener { if (isShowing) dismiss() else open() }
        // Columns (2026-10-10): the layouts say so too, but this is where the order of the
        // buttons is decided, so it is where the direction is decided.
        miniBar.orientation = LinearLayout.VERTICAL
        overflowBar.orientation = LinearLayout.VERTICAL

        // The hints are this module's: the four screens say the same four words.
        val hints = mapOf(
            Tool.PEN to ctx.getString(R.string.tool_pen),
            Tool.ERASER to ctx.getString(R.string.eraser_point),
            Tool.SMUDGE to ctx.getString(R.string.tool_smudge),
            Tool.LASSO_ERASER to ctx.getString(R.string.eraser_lasso),
            Tool.LASSO to ctx.getString(R.string.tool_lasso),
        )
        leading.forEach { add(it) }
        tools.forEach { tool ->
            val kinds = penKinds.takeIf { tool == Tool.PEN }
            // The button is its own click's anchor (the `add` helper's pattern): a re-pick of the
            // armed primary pen hangs the screen's sub-bar under the button that was tapped, not
            // under a bar button that is `GONE` and keeps stale edges.
            lateinit var button: AppCompatImageButton
            button = mini.addButton(
                CollapsedTools.iconFor(tool),
                kinds?.kinds?.get(0)?.hint ?: hints.getValue(tool),
            ) {
                when {
                    kinds != null -> pickPen(0, anchor = button)
                    tool == Tool.PEN -> pickOnePen(anchor = button)
                    else -> pick(tool)
                }
            }
            buttons += button
            toolButtons[tool] = button
            // Immediately after the primary one — the sketch face's row reads Pencil · Pen ·
            // Marker · Eraser · Smudge.
            if (kinds != null) {
                for (i in 1 until kinds.kinds.size) {
                    val kind = kinds.kinds[i]
                    // Its own click's anchor too (arc 46): a re-pick of an armed kind hangs the
                    // screen's shade panel under this button.
                    lateinit var extra: AppCompatImageButton
                    extra = mini.addButton(kind.iconRes, kind.hint) { pickPen(i, anchor = extra) }
                    buttons += extra
                    extraPenButtons += extra
                    extraPenTokens += null
                }
            }
        }
        commands.forEach { add(it) }
        // Everything in one column until an open says how much fits ([open] cuts it).
        overflow.forEach { add(it) }
        sync()
    }

    private fun add(entry: Entry) {
        lateinit var button: AppCompatImageButton
        button = mini.addButton(entry.iconRes, entry.hint) {
            val onTap = entry.onTap
            if (onTap != null) {
                // The sub-bar hangs beside the column the anchor is in; a second column there
                // would be under it. From the second column itself there is nothing to the left.
                if (button.parent === miniBar) hideOverflow()
                onTap(button)
            } else {
                dismiss()
                entry.mirrors?.performClick()
            }
        }
        buttons += button
        if (entry.mirrors != null) mirrored += button to entry
    }

    /**
     * Deal the buttons between the two columns for this open: the first takes what fits under
     * the corner button, the second the rest. Only the views move — every listener, hint and
     * glyph rides with its button, so nothing can drift between the columns.
     */
    private fun cut(capacity: Int) {
        val first = CollapsedTools.firstColumn(buttons.size, capacity)
        if (first == lastCut) return
        lastCut = first
        miniBar.removeAllViews()
        overflowBar.removeAllViews()
        buttons.forEachIndexed { i, b -> (if (i < first) miniBar else overflowBar).addView(b) }
    }

    /**
     * Copy every mirrored bar button's state for one row — read at its open, never cached.
     * The glyph is copied only when it differs (a constant-state identity check): nothing on any
     * screen swaps a mirrored button's glyph after construction, so in the steady state this is
     * flag compares and no allocation.
     */
    private fun refresh(mirrored: List<Pair<AppCompatImageButton, Entry>>) {
        mirrored.forEach { (button, entry) ->
            val source = entry.mirrors ?: return@forEach
            button.visibility = if (source.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            button.isSelected = source.isSelected
            if (source is ImageView) {
                val state = source.drawable?.constantState ?: return@forEach
                if (button.drawable?.constantState === state) return@forEach
                // A mutated copy: a Drawable carries bounds and a callback, and the bar's button
                // still owns its own.
                button.setImageDrawable(state.newDrawable(button.resources).mutate())
            }
        }
    }

    private fun open() {
        if (!canOpen()) return
        // Null before layout, when `show` would show nothing either.
        val capacity = mini.columnCapacity(buttonSize) ?: return
        onOpen()
        paper.releaseRender()
        cut(capacity)
        refresh(mirrored)
        sync()
        if (mini.show()) {
            val second = overflowBar.childCount > 0 && more.showBeside(mini)
            Slog.d(TAG) { "mini toolbar open (armed ${paper.tool}; ${miniBar.childCount} in the column${if (second) ", ${overflowBar.childCount} beside" else ""})" }
            onChanged()
        }
    }

    /**
     * Arm one of the four. The screen's `toolbar.arm` ([onArmed]) does the assignment (skipped
     * when already armed, so picking the armed one is honestly a no-op on the surface), the one
     * pen-gated render release, the bar's sync and — through the bar's funnel — this one's; then
     * the rows come down with one exclusion push for the whole tap. The rows still close on a
     * re-pick, because a tap on a tool is an answer.
     */
    private fun pick(tool: Tool) {
        onArmed(tool)
        dismiss()
        Slog.d(TAG) { "armed $tool from the mini toolbar" }
    }

    /**
     * Arm one of the pen's two **kinds** from the mini toolbar (arc 44 / T3) — [pick]'s body where
     * the tool is the same either way, and [PaperToolbar.selectPen]'s rule said once more for this
     * row.
     *
     * A pick of the already-armed kind is the row's own re-tap: the screen is handed the kind and
     * this button as an anchor and **the rows stay up**, so its bar hangs under the button that was
     * tapped rather than under a bar button that is `GONE` behind hidden chrome. Everything else —
     * the other kind, a first arming, a re-pick with no re-tap handler — arms and closes the rows,
     * because a tap on a tool is an answer.
     */
    private fun pickPen(kind: Int, anchor: AppCompatImageButton?) {
        val kinds = penKinds ?: return
        if (paper.tool == Tool.PEN && kinds.armed() == kind) {
            val reTap = kinds.onReTap
            if (reTap != null && anchor != null) {
                reTap(kind, anchor)
                return
            }
        }
        kinds.onPick(kind)
        dismiss()
        Slog.d(TAG) { "armed pen kind $kind from the mini toolbar" }
    }

    /**
     * Arm the one pen from the mini toolbar on a screen with one pen (arc 49 / P4) — [pick]'s body,
     * plus [pickPen]'s re-tap rule said once more for one kind: a pick of the already-armed pen
     * with an [onPenReTap] is the row's own re-tap, the screen is handed this button as an anchor
     * and **the rows stay up**, so its shade panel hangs under the button that was tapped. With no
     * re-tap handler the pick arms and closes the rows, as every tool tap did before P4.
     */
    private fun pickOnePen(anchor: AppCompatImageButton) {
        val reTap = onPenReTap
        if (reTap != null && paper.tool == Tool.PEN) {
            reTap(anchor)
            return
        }
        pick(Tool.PEN)
    }

    /**
     * Make the corner button and the mini toolbar honest about `paper.tool`. Wired once, into the
     * bar's `onSynced`, so every path a tool can change by repaints it. Idempotent and cheap: the
     * glyph swaps only on a change, `isSelected` is change-checked by the framework.
     */
    fun sync() {
        val armed = paper.tool
        syncKnob(armed)
        syncPrimaryPen()
        syncExtraPens()
        // [CollapsedTools.selectedFor] answers any armed tool, so on a shortened bar it can
        // name a tool that has no button here — the walk is over the buttons this bar actually
        // built, so that reads as "nothing bordered", which is exactly right.
        val selected = CollapsedTools.selectedFor(armed)
        // Arc 44 / T3: the PEN slot's buttons follow the KIND as well as the tool, on the one rule
        // the top bar uses ([CollapsedTools.penButtonSelected]). With no further kind the armed
        // kind is 0 and the primary button reads `armed == PEN`, as it always did.
        val armedKind = penKinds?.armed?.invoke() ?: 0
        toolButtons.forEach { (tool, button) ->
            button.isSelected =
                if (tool == Tool.PEN && penKinds != null) CollapsedTools.penButtonSelected(armed, armedKind, 0)
                else tool == selected
        }
        extraPenButtons.forEachIndexed { i, button -> button.isSelected = CollapsedTools.penButtonSelected(armed, armedKind, i + 1) }
    }

    /**
     * The lasso wears the clipboard mark while [loaded] objects are on the clipboard (arc 8) —
     * the corner button when the lasso is armed, and the mini toolbar's lasso always. Idempotent.
     */
    fun showClipboardLoaded(loaded: Boolean) {
        if (clipboardLoaded == loaded) return
        clipboardLoaded = loaded
        toolButtons[Tool.LASSO]?.setImageResource(CollapsedTools.iconFor(Tool.LASSO, loaded))
        syncKnob(paper.tool)
    }

    private fun syncKnob(armed: Tool) {
        // Swapped only on a change: every sync lands here, and re-setting the same drawable would
        // invalidate the button for nothing (frame silence). Arc 44 / T3: under two pen kinds the
        // glyph is what says which one is armed — the tool is [Tool.PEN] for both.
        val kinds = penKinds
        val armedKind = kinds?.armed?.invoke() ?: 0
        // The screen's own painted glyph (arc 44 / T3) wears the corner button exactly when that
        // kind's pen button reads as armed — [CollapsedTools.penButtonSelected]'s rule again
        // rather than a second spelling of "is the pencil what is on the paper?". Under any other
        // tool the button wears that tool's glyph, untouched.
        // (Arc 49 / P4) …and on a screen with ONE pen, that pen's own painted glyph while it is
        // armed — the same rule with no second kind, so `penButtonSelected` reads `armed == PEN`.
        val reported = kinds?.kinds?.indices
            ?.firstOrNull { CollapsedTools.penButtonSelected(armed, armedKind, it) }
            ?.let { kinds.kinds[it].icon?.invoke() }
            ?: penIcon
                ?.takeIf { kinds == null && armed == Tool.PEN }
                ?.invoke()
        if (reported != null) {
            // `knobIcon` 0 is "wearing a painted glyph" — no resource id is ever 0, so the pair
            // (0, token) cannot be confused with any resource the button could be showing.
            if (knobIcon == PAINTED && knobToken == reported.token) return
            knobIcon = PAINTED
            knobToken = reported.token
            knob.setImageDrawable(reported.newDrawable())
            return
        }
        val icon = CollapsedTools.iconFor(armed, clipboardLoaded)
        if (icon == knobIcon) return
        knobIcon = icon
        knobToken = null
        knob.setImageResource(icon)
    }

    /**
     * The row's own primary-pen button wears the screen's painted glyph **always** (arc 44 / T3),
     * armed or not: it is the button that says what a tap will bring back, so a pencil shown in
     * the shade it would draw with is the honest one whatever is on the paper at the moment. A
     * screen that paints nothing ([PenKinds.primaryIcon] and [penIcon] both null) keeps the
     * resource glyph its button was built with.
     */
    private fun syncPrimaryPen() {
        // The kinds' primary glyph, or (arc 49 / P4) the one pen's on a screen with one pen.
        val icon = (penKinds?.kinds?.get(0)?.icon ?: penIcon.takeIf { penKinds == null })?.invoke() ?: return
        val button = toolButtons[Tool.PEN] ?: return
        if (icon.token == primaryPenToken) return
        primaryPenToken = icon.token
        button.setImageDrawable(icon.newDrawable())
    }

    /** The row's further pen buttons wear the screen's painted glyphs always (arc 46 "Palette") —
     *  [syncPrimaryPen]'s rule for every kind past the first. None on any screen but the sketch
     *  face. */
    private fun syncExtraPens() {
        val kinds = penKinds ?: return
        extraPenButtons.forEachIndexed { i, button ->
            val icon = kinds.kinds.getOrNull(i + 1)?.icon?.invoke() ?: return@forEachIndexed
            if (icon.token == extraPenTokens[i]) return@forEachIndexed
            extraPenTokens[i] = icon.token
            button.setImageDrawable(icon.newDrawable())
        }
    }

    /** The second column alone down — before a sub-bar is hung beside the first, where the two
     *  would otherwise share one edge. Idempotent. */
    fun hideOverflow() {
        if (!more.isShowing) return
        onClose()
        more.hide()
        onChanged()
    }

    /** Both columns down. Idempotent — every dismiss path calls it without checking. */
    fun dismiss() {
        if (!mini.isShowing && !more.isShowing) return
        onClose()
        more.hide()
        mini.hide()
        onChanged()
    }

    /**
     * The outside-contact dismissal ([CollapsedTools.outsideTapDismisses]): a contact anywhere but
     * the corner button, the rows, or a sub-bar the screen has hung off them ([keep]) takes the
     * rows down. The screen calls it for every pointer going down, before anything consumes it;
     * the answer says whether this contact was spent on a dismissal (the notebook's paste latch).
     * Nothing is computed while nothing is showing — the idle path is one flag read.
     */
    fun dismissOnContact(x: Int, y: Int, keep: (Int, Int) -> Boolean = { _, _ -> false }): Boolean {
        if (!isShowing) return false
        if (!CollapsedTools.outsideTapDismisses(showing = true, onChrome = contains(x, y), keep = keep(x, y))) return false
        dismiss()
        return true
    }

    /** The visible corner button's and rows' rects in **window** coordinates — for exclusions. */
    fun rects(): List<Rect> = listOfNotNull(PaperToolbar.rectOf(knob)) + mini.rects() + more.rects()

    /**
     * The matching hit test — the corner button included, so the button that toggles the rows is
     * never also the contact that dismisses them (the lasso popup's close-then-reopen trap).
     * Window coordinates, which on these full-bleed immersive screens are the root's.
     */
    fun contains(x: Int, y: Int): Boolean =
        PaperToolbar.rectOf(knob)?.contains(x, y) == true || mini.contains(x, y) || more.contains(x, y)

    private companion object {
        const val TAG = "CollapsedChrome"

        /** [knobIcon]'s stand-in for "wearing a painted glyph, not a resource one" — 0 is never a
         *  resource id, so it cannot collide with one the button might actually be showing. */
        const val PAINTED = 0
    }
}
