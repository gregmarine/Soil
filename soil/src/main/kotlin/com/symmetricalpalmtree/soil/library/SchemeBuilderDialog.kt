package com.symmetricalpalmtree.soil.library

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.R
import com.symmetricalpalmtree.soil.data.index.LibraryStore
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.templates.TemplateNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **The scheme builder**: a folder's default notebook name, put together without typing a
 * token. A row of chips adds a part (Date, Time, Year, Month, Day, Month name, Mon, Weekday,
 * Wkd, Number); a field adds text; the scheme stands as a row of parts, each removable by a
 * tap; and the next name is previewed as it would be made now. Save writes the scheme as
 * [SchemeEngine]'s text, which is the one form stored; Clear takes the folder's scheme away.
 *
 * The refusals are the engine's, worded here: two Numbers, text outside the charset, a name
 * too long. A rejected part leaves the scheme as it was.
 */
object SchemeBuilderDialog {

    private const val TAG = "SchemeBuilder"

    private val TOKENS: List<Pair<Int, SchemeEngine.Part>> = listOf(
        R.string.scheme_token_date to SchemeEngine.Part.Date,
        R.string.scheme_token_time to SchemeEngine.Part.Time,
        R.string.scheme_token_year to SchemeEngine.Part.Year,
        R.string.scheme_token_month to SchemeEngine.Part.Month,
        R.string.scheme_token_day to SchemeEngine.Part.Day,
        R.string.scheme_token_monthname to SchemeEngine.Part.MonthName,
        R.string.scheme_token_mon to SchemeEngine.Part.Mon,
        R.string.scheme_token_weekday to SchemeEngine.Part.Weekday,
        R.string.scheme_token_wd to SchemeEngine.Part.Wd,
    )

    /** The scheme text a list of parts is stored as. */
    fun encode(parts: List<SchemeEngine.Part>): String = parts.joinToString("") { p ->
        when (p) {
            is SchemeEngine.Part.Literal -> p.text
            SchemeEngine.Part.Date -> "{date}"
            SchemeEngine.Part.Time -> "{time}"
            SchemeEngine.Part.Year -> "{year}"
            SchemeEngine.Part.Month -> "{month}"
            SchemeEngine.Part.Day -> "{day}"
            SchemeEngine.Part.MonthName -> "{monthname}"
            SchemeEngine.Part.Weekday -> "{weekday}"
            SchemeEngine.Part.Mon -> "{mon}"
            SchemeEngine.Part.Wd -> "{wd}"
            is SchemeEngine.Part.Counter -> if (p.width == 1) "{n}" else "{n:${p.width}}"
        }
    }

    /** What a part is called on its chip. */
    fun label(context: Context, p: SchemeEngine.Part): String = when (p) {
        is SchemeEngine.Part.Literal -> "“${p.text}”"
        is SchemeEngine.Part.Counter -> context.getString(R.string.scheme_token_number) + if (p.width > 1) " ×${p.width}" else ""
        else -> context.getString(TOKENS.first { it.second == p }.first)
    }

    /** Read the folder's scheme, then show. A read that fails explains itself and opens nothing. */
    fun open(activity: AppCompatActivity, store: LibraryStore, folderId: String, folderName: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.lifecycleScope.launch {
            val current = try {
                withContext(Dispatchers.IO) { store.folderPrefs(folderId).scheme }
            } catch (e: Exception) {
                Log.w(TAG, "scheme read failed: ${e.javaClass.simpleName}")
                Dialogs.problem(activity, R.string.scheme_problem_title, R.string.scheme_save_failed)
                return@launch
            }
            val parts = current?.let { runCatching { SchemeEngine.parse(it) }.getOrNull() }.orEmpty()
            if (!activity.isFinishing && !activity.isDestroyed) show(activity, store, folderId, folderName, parts.toMutableList())
        }
    }

    private fun show(activity: AppCompatActivity, store: LibraryStore, folderId: String, folderName: String, parts: MutableList<SchemeEngine.Part>) {
        val d = activity.resources.displayMetrics.density
        val ink = ContextCompat.getColor(activity, com.symmetricalpalmtree.soil.paper.R.color.inkBlack)
        val side = (24 * d).toInt()

        val preview = AppCompatTextView(activity).apply { textSize = 15f; setTextColor(ink); setPadding(0, (8 * d).toInt(), 0, (8 * d).toInt()) }
        val schemeRow = FlowRow(activity)
        val chips = FlowRow(activity)
        val literal = AppCompatEditText(activity).apply {
            setHint(R.string.scheme_literal_hint)
            textSize = 16f
            setTextColor(ink)
            background = ContextCompat.getDrawable(activity, com.symmetricalpalmtree.soil.paper.R.drawable.shape_bordered)
            val pad = (10 * d).toInt()
            setPadding(pad, pad, pad, pad)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSingleLine()
        }

        fun chip(text: String, onTap: () -> Unit): View = AppCompatButton(activity).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setTextColor(ink)
            background = ContextCompat.getDrawable(activity, com.symmetricalpalmtree.soil.paper.R.drawable.shape_bordered)
            stateListAnimator = null
            minHeight = 0; minimumHeight = 0
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            setOnClickListener { onTap() }
        }

        fun render() {
            schemeRow.removeAllViews()
            parts.forEachIndexed { i, p ->
                schemeRow.addView(chip(label(activity, p) + "  ×") { parts.removeAt(i); render() })
            }
            val scheme = encode(parts)
            preview.text = if (parts.isEmpty()) activity.getString(R.string.scheme_preview_none) else {
                val problem = SchemeEngine.validate(scheme)
                if (problem != null) message(activity, problem)
                else activity.getString(R.string.scheme_preview, SchemeEngine.expand(scheme, System.currentTimeMillis(), emptyList()))
            }
        }

        /** Add [p] if the engine takes the result; else say why and leave the scheme as it was. */
        fun tryAdd(p: SchemeEngine.Part) {
            val next = parts + p
            val problem = SchemeEngine.validate(encode(next))
            if (problem != null) { Dialogs.problem(activity, R.string.scheme_problem_title, message(activity, problem)); return }
            parts.add(p)
            render()
        }

        for ((labelRes, part) in TOKENS) chips.addView(chip(activity.getString(labelRes)) { tryAdd(part) })
        chips.addView(chip(activity.getString(R.string.scheme_token_number)) { askWidth(activity) { w -> tryAdd(SchemeEngine.Part.Counter(w)) } })
        val addText = chip(activity.getString(R.string.scheme_add_text)) {
            val text = literal.text?.toString().orEmpty()
            if (text.isEmpty()) return@chip
            if (!TemplateNames.CHARSET.matches(text)) { Dialogs.problem(activity, R.string.scheme_problem_title, R.string.err_scheme_illegal_char); return@chip }
            tryAdd(SchemeEngine.Part.Literal(text))
            literal.setText("")
        }
        val literalRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(literal, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(addText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * d).toInt() })
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, (8 * d).toInt(), side, 0)
            addView(preview)
            addView(schemeRow)
            addView(chips, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
            addView(literalRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
        render()

        val dialog = Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.scheme_folder_title, folderName))
                .setView(body)
                .setPositiveButton(R.string.scheme_save, null)
                .setNeutralButton(R.string.scheme_clear, null)
                .setNegativeButton(com.symmetricalpalmtree.soil.paper.R.string.cancel, null)
                .create(),
        )
        dialog.show()
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            val scheme = encode(parts)
            if (parts.isNotEmpty()) {
                val problem = SchemeEngine.validate(scheme)
                if (problem != null) { Dialogs.problem(activity, R.string.scheme_problem_title, message(activity, problem)); return@setOnClickListener }
            }
            save.isClickable = false
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { store.setScheme(folderId, scheme.ifEmpty { null }) }
                    Slog.d(TAG) { "scheme ${if (scheme.isEmpty()) "cleared" else "saved"}" }
                    dialog.dismiss()
                } catch (e: Exception) {
                    Log.w(TAG, "scheme save failed: ${e.javaClass.simpleName}")
                    Dialogs.problem(activity, R.string.scheme_problem_title, R.string.scheme_save_failed)
                } finally {
                    save.isClickable = true
                }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            activity.lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { store.setScheme(folderId, null) } }
                dialog.dismiss()
            }
        }
    }

    /** How many digits a Number is padded to: 1 to 4, the widths anyone counts in. */
    private fun askWidth(activity: AppCompatActivity, onPick: (Int) -> Unit) {
        val sheet = com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog(activity).title(activity.getString(R.string.scheme_number_width))
        for (w in 1..4) sheet.addAction(null, "1".padStart(w, '0')) { onPick(w) }
        sheet.show()
    }

    fun message(context: Context, e: SchemeEngine.SchemeException): String = when (e.error) {
        SchemeEngine.Error.COUNTER_TWICE -> context.getString(R.string.err_scheme_counter_twice)
        SchemeEngine.Error.ILLEGAL_CHAR -> context.getString(R.string.err_scheme_illegal_char)
        SchemeEngine.Error.EMPTY -> context.getString(R.string.err_scheme_empty)
        SchemeEngine.Error.TOO_LONG -> context.getString(R.string.err_scheme_too_long, SchemeEngine.MAX_SCHEME_CHARS)
        else -> context.getString(R.string.err_scheme_unknown)
    }

    /** Chips in rows that wrap: a row of parts should never scroll. */
    private class FlowRow(context: Context) : ViewGroup(context) {
        private val gap = (6 * context.resources.displayMetrics.density).toInt()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x + c.measuredWidth > width && x > 0) { x = 0; y += rowH + gap; rowH = 0 }
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
            setMeasuredDimension(width, if (childCount == 0) 0 else y + rowH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val width = r - l
            var x = 0
            var y = 0
            var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x + c.measuredWidth > width && x > 0) { x = 0; y += rowH + gap; rowH = 0 }
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth + gap
                rowH = maxOf(rowH, c.measuredHeight)
            }
        }
    }
}
