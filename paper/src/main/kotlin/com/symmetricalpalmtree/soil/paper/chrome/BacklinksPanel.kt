package com.symmetricalpalmtree.soil.paper.chrome

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.soil.paper.R
import com.symmetricalpalmtree.soil.paper.core.ListSwipe
import com.symmetricalpalmtree.soil.paper.core.Slog

/**
 * "What links into this?": the places in the library that link to what is on screen, as a
 * **side panel** — the Bible reader's Notes panel, shared with the calendar's Links (Calsprout,
 * 2026-10-06). A full-window `Dialog` over the screen; one layout, two forms: below 480 dp the
 * panel fills the screen behind a back arrow, at or above it is a **right** sidebar
 * ([BacklinksModel.SIDEBAR_WIDTH_FRACTION]) over a transparent scrim whose tap dismisses.
 *
 * **Rows** are two lines, where it was written ("Study · Page 4", 20 sp) and what it links to
 * there (13 sp), both inkBlack: secondary text is *smaller*, never grey. The rows are the
 * consumer's, already grouped and worded ([BacklinksModel.Row]). The list **paginates, it never
 * scrolls**: one row is measured at the real panel width after the first layout and
 * `itemsPerPage` follows; the pager footer is `INVISIBLE` at one page. A one-finger horizontal
 * swipe over the body flips pages too.
 *
 * A modal snapshot of what the screen gathered before opening it. A row tap dismisses and hands
 * its key up; the empty line ("Nothing links into Genesis 1") is a real answer, shown in the body.
 *
 * **Never logged:** a name is the person's own word.
 */
class BacklinksPanel<K>(
    private val activity: Activity,
    /** The header's word: "Notes", "Links". */
    private val title: String,
    /** What the body says when nothing links: the consumer's sentence, scope and all. */
    private val emptyText: String,
    private val rows: List<BacklinksModel.Row<K>>,
    private val onDismissed: () -> Unit,
    /** The chosen row's key; the panel has dismissed itself by the time this runs. */
    private val onPicked: (K) -> Unit,
) {
    private val listSwipe = ListSwipe(
        region = { body },
        onFlipNext = { goToListPage(listPage + 1) },
        onFlipPrevious = { goToListPage(listPage - 1) },
    )

    private val dialog = object : Dialog(activity, R.style.Theme_Soil) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            listSwipe.onTouchEvent(ev)
            return super.dispatchTouchEvent(ev)
        }
    }

    /** The body band the rows paginate inside — the region the flip arms on; null until shown. */
    private var body: View? = null
    private var listPage = 0
    private var itemsPerPage = 1

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var pager: View
    private lateinit var pageLabel: TextView

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) { onDismissed(); return }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_backlinks)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onDismissed() }
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }

        val root = dialog.findViewById<FrameLayout>(R.id.backlinksRoot)
        val panel = dialog.findViewById<LinearLayout>(R.id.backlinksPanel)
        body = dialog.findViewById(R.id.backlinksBody)
        val btnBack = dialog.findViewById<AppCompatImageButton>(R.id.btnBacklinksBack)
        dialog.findViewById<TextView>(R.id.backlinksTitle).text = title
        list = dialog.findViewById(R.id.backlinksRows)
        empty = dialog.findViewById(R.id.backlinksEmpty)
        pager = dialog.findViewById(R.id.backlinksPager)
        pageLabel = dialog.findViewById(R.id.backlinksPageLabel)
        val btnFirst = dialog.findViewById<AppCompatImageButton>(R.id.btnBacklinksFirst)
        val btnPrev = dialog.findViewById<AppCompatImageButton>(R.id.btnBacklinksPrev)
        val btnNext = dialog.findViewById<AppCompatImageButton>(R.id.btnBacklinksNext)
        val btnLast = dialog.findViewById<AppCompatImageButton>(R.id.btnBacklinksLast)
        listOf(btnBack, btnFirst, btnPrev, btnNext, btnLast).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)   // every icon button names itself
        }

        empty.text = emptyText

        val metrics = activity.resources.displayMetrics
        val widthDp = activity.resources.configuration.screenWidthDp
        val fullScreen = BacklinksModel.fullScreen(widthDp)
        if (fullScreen) {
            root.setBackgroundColor(ContextCompat.getColor(activity, R.color.paperWhite))
            panel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            // Plain paper, not the sidebar shape — its left border would be a stray line down the
            // screen edge.
            panel.setBackgroundColor(ContextCompat.getColor(activity, R.color.paperWhite))
            btnBack.visibility = View.VISIBLE
            btnBack.setOnClickListener { dialog.dismiss() }
        } else {
            root.setBackgroundColor(Color.TRANSPARENT)
            panel.layoutParams = FrameLayout.LayoutParams(
                BacklinksModel.sidebarWidthPx(metrics.widthPixels), FrameLayout.LayoutParams.MATCH_PARENT,
            ).also { it.gravity = Gravity.END }
            btnBack.visibility = View.GONE
            root.setOnClickListener { dialog.dismiss() }   // the scrim; the panel is clickable in XML and eats its own taps
        }

        btnFirst.setOnClickListener { goToListPage(0) }
        btnPrev.setOnClickListener { goToListPage(listPage - 1) }
        btnNext.setOnClickListener { goToListPage(listPage + 1) }
        btnLast.setOnClickListener { goToListPage(BacklinksModel.pageCount(rows.size, itemsPerPage) - 1) }

        // itemsPerPage from the real body height and a really-measured row, once after the first
        // layout — a two-line row's height depends on the font scale, so nothing is estimated.
        list.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val bodyPx = list.height - list.paddingTop - list.paddingBottom
                itemsPerPage = BacklinksModel.itemsPerPage(bodyPx, measureRowHeightPx())
                listPage = 0
                render()
                Slog.d(TAG) { "shown: fullScreen=$fullScreen widthDp=$widthDp panel=${panel.width}px rows/page=$itemsPerPage entries=${rows.size}" }
            }
        })

        dialog.show()
    }

    /** Safe when nothing is showing — a screen's close hygiene calls it unconditionally. */
    fun dismiss() { if (dialog.isShowing) dialog.dismiss() }

    /** Inflate one row, measure it at the list's real width, and return its full height in px. */
    private fun measureRowHeightPx(): Int {
        val sample = LayoutInflater.from(activity).inflate(R.layout.item_backlink_entry, list, false)
        sample.measure(
            View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return sample.measuredHeight
    }

    private fun goToListPage(page: Int) {
        val clamped = BacklinksModel.clampPage(page, BacklinksModel.pageCount(rows.size, itemsPerPage))
        if (clamped == listPage) return   // a tap at a bound is a no-op (never a disabled look on e-ink)
        listPage = clamped
        render()
    }

    private fun render() {
        list.removeAllViews()
        if (rows.isEmpty()) {
            empty.visibility = View.VISIBLE
            pager.visibility = View.INVISIBLE
            pageLabel.text = ""
            return
        }
        empty.visibility = View.GONE
        val pageCount = BacklinksModel.pageCount(rows.size, itemsPerPage)
        listPage = BacklinksModel.clampPage(listPage, pageCount)
        val start = listPage * itemsPerPage
        val end = minOf(start + itemsPerPage, rows.size)
        val inflater = LayoutInflater.from(activity)
        for (row in rows.subList(start, end)) {
            val view = inflater.inflate(R.layout.item_backlink_entry, list, false)
            view.findViewById<TextView>(R.id.backlinkTitle).text = row.title
            view.findViewById<TextView>(R.id.backlinkDetail).text = row.detail
            view.setOnClickListener {
                dialog.dismiss()
                onPicked(row.key)
            }
            list.addView(view)
        }
        pageLabel.text = activity.getString(R.string.backlinks_page_indicator, listPage + 1, pageCount)
        pager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
    }

    private companion object {
        const val TAG = "BacklinksPanel"
    }
}
