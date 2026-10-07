package com.symmetricalpalmtree.soil.calsprout

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.calsprout.databinding.ActivityEventsBinding
import com.symmetricalpalmtree.soil.paper.chrome.DayPickerDialog
import com.symmetricalpalmtree.soil.paper.core.ActionSheetDialog
import com.symmetricalpalmtree.soil.paper.core.CalendarDates
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.ListSwipe
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.paper.ink.StoreUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * One day's **events** — the calendar's second screen, and the door to the editor. Notesprout
 * SN's, with the store reached through the app's one lease.
 *
 * **Not exported.** It is launched only by [CalendarActivity], in this process, with an
 * `ActivityResultLauncher`: nothing outside this APK can start it.
 *
 * **No paper, and therefore no EPD handoff.** It is a plain [AppCompatActivity] over an ordinary
 * view stack.
 *
 * **Delete lives here, not in the editor** (Greg's call): each row carries a trash icon. It asks
 * the scope sheet for a recurring event, then a confirm that names what is about to go, and on
 * success it simply re-reads, because a list is what a delete changes.
 *
 * **Two sections, one day** ([EventsPaging]): the day's own events, then the reminder look-ahead.
 * The "Today" label appears only when Upcoming follows it, because a label exists to tell two
 * lists apart. Rows are measured against the **real band** and paged greedily by height.
 *
 * **The bottom bar is the calendar's own**, verbatim: prev day · the day's name, itself the tap
 * target that opens the shared [DayPickerDialog] · next day. A finger swipe over the band steps
 * the **day**, not the in-band page.
 *
 * **The day it ends on goes back to the calendar** ([EXTRA_ENDED_ON]) on every leave, arrow and
 * system Back alike, because the calendar follows it. It is set before the failure dialog too.
 *
 * `onResume` re-reads. The editor's result is deliberately **ignored** — a save, a delete and a
 * plain Back all come back through the same re-read.
 */
class EventsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventsBinding

    private var day: LocalDate = LocalDate.now()
    private var rows: List<EventsRow> = emptyList()
    private var page = 0

    /** Whether a read has answered yet. The empty line is a **result**, not a starting state. */
    private var answered = false

    private var rowHeightPx = 1
    private var headerHeightPx = 1

    /** The band height the showing page was computed against — a re-layout at the same height
     *  must not redraw the list under the user's finger. */
    private var bandHeightPx = -1

    /** One read at a time. E-ink gives a tap no feedback for hundreds of ms, so a second tap is
     *  taken as read rather than queued behind a store call. */
    private var busy = false

    /** The one launcher both doors take. The result is ignored on purpose — see the class note. */
    private val editorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { load() }

    /** A finger swipe over the band steps the DAY. Observer only: it consumes nothing. */
    private val swipe = ListSwipe(
        region = { if (::binding.isInitialized) binding.listBand else null },
        onFlipNext = { setDay(day.plusDays(1)) },
        onFlipPrevious = { setDay(day.minusDays(1)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)
        rowHeightPx = EventRowView.rowHeightPx(this)
        headerHeightPx = EventRowView.headerHeightPx(this)

        day = CalendarDates.parse(intent.getStringExtra(EXTRA_DAY).orEmpty()) ?: LocalDate.now()

        // Every icon button names itself: the tooltip is its content description, and the long
        // press says it out loud — words read better than glyphs on e-ink.
        listOf(binding.btnBack, binding.btnAdd, binding.btnPrevPage, binding.btnNextPage,
            binding.btnPrevDay, binding.btnNextDay, binding.dayTitle).forEach {
            TooltipCompat.setTooltipText(it, it.contentDescription)
        }
        binding.btnBack.setOnClickListener { finishWithDay() }
        binding.btnBack.setOnLongClickListener { hint(R.string.cd_events_back) }
        binding.btnAdd.setOnClickListener { openEditor(null, day) }
        binding.btnAdd.setOnLongClickListener { hint(R.string.cd_events_add) }
        binding.btnPrevPage.setOnClickListener { turnPage(-1) }
        binding.btnPrevPage.setOnLongClickListener { hint(R.string.cd_events_prev_page) }
        binding.btnNextPage.setOnClickListener { turnPage(1) }
        binding.btnNextPage.setOnLongClickListener { hint(R.string.cd_events_next_page) }
        binding.btnPrevDay.setOnClickListener { setDay(day.minusDays(1)) }
        binding.btnPrevDay.setOnLongClickListener { hint(R.string.cd_events_prev_day) }
        binding.btnNextDay.setOnClickListener { setDay(day.plusDays(1)) }
        binding.btnNextDay.setOnLongClickListener { hint(R.string.cd_events_next_day) }
        binding.dayTitle.setOnClickListener { showPicker() }
        binding.dayTitle.setOnLongClickListener { hint(R.string.cd_events_pick_day) }

        // Back is the same door as the arrow: the calendar is owed the day either way.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithDay()
        })

        // The page size is what actually FITS. Re-render only when the height really moved, and
        // **posted**: this fires from inside a layout pass and the render adds and removes the
        // band's own children.
        binding.listBand.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop || bandHeightPx < 0) binding.listBand.post { render() }
        }

        renderDay()
    }

    /** A read on every showing: an editor may have just saved or deleted under us. */
    override fun onResume() {
        super.onResume()
        load()
    }

    /** The swipe detector is an observer fed from here — it consumes nothing, so dispatch always
     *  continues to the views. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::binding.isInitialized) swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ── The day ──────────────────────────────────────────────────────────────

    private fun setDay(next: LocalDate) {
        if (busy || next == day) return
        day = next
        // A new day is a new list: the page it is standing on means nothing here, and nothing has
        // been read about it yet.
        page = 0
        rows = emptyList()
        answered = false
        renderDay()
        load()
    }

    private fun showPicker() {
        DayPickerDialog.show(this, day) { picked -> setDay(picked) }
    }

    private fun renderDay() {
        binding.dayTitle.text = EventWording.dayHeading(day)
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    private fun load() {
        if (busy) return
        busy = true
        val asked = day
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { read(asked) }
            busy = false
            if (isFinishing || isDestroyed) return@launch
            // The day moved while the read was in flight (a fast double tap on the pager): this
            // answer is about a day nobody is looking at any more. The newer read is already coming.
            if (asked != day) return@launch
            when (outcome) {
                is Read.Ok -> {
                    rows = EventsPaging.rows(outcome.today, outcome.upcoming)
                    answered = true
                    Slog.d(TAG) { "loaded $asked: ${outcome.today.size} today, ${outcome.upcoming.size} upcoming" }
                    render()
                }
                is Read.Failed -> failAndClose()
            }
        }
    }

    private sealed class Read {
        class Ok(val today: List<Event>, val upcoming: List<UpcomingEvent>) : Read()
        object Failed : Read()
    }

    /** Blocking, IO only. The store is the app's one lease. */
    private suspend fun read(on: LocalDate): Read = try {
        // One pass for both answers: the whole recurring set and its three child sets serve the
        // day list and the look-ahead alike.
        val both = (application as CalsproutApp).events().dayAndUpcoming(on)
        Read.Ok(both.today, both.upcoming)
    } catch (e: StoreUnavailable) {
        Slog.d(TAG) { "store unavailable: ${e.javaClass.simpleName}" }
        Read.Failed
    } catch (e: Exception) {
        Slog.d(TAG) { "read failed: ${e.javaClass.simpleName}" }
        Read.Failed
    }

    /**
     * Nothing can be shown and nothing can be fixed from here: explain, then leave — on the
     * dialog's **dismiss**, never beside it. The day result is set first, so the calendar still
     * follows the day this screen was asked for rather than being moved by a failure.
     */
    private fun failAndClose() {
        setDayResult()
        Dialogs.confirm(this, R.string.events_unavailable_title, R.string.events_unavailable_body) { finish() }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun render() {
        bandHeightPx = binding.listBand.height
        page = EventsPaging.clampPage(page, rows, bandHeightPx, headerHeightPx, rowHeightPx)
        val pageCount = EventsPaging.pageCount(rows, bandHeightPx, headerHeightPx, rowHeightPx)

        binding.listBand.removeAllViews()
        for (row in EventsPaging.pageOf(rows, page, bandHeightPx, headerHeightPx, rowHeightPx)) {
            binding.listBand.addView(viewFor(row))
        }
        binding.listEmpty.visibility = if (answered && rows.isEmpty()) View.VISIBLE else View.GONE

        // INVISIBLE, never GONE: the band must not grow and shift every row under the finger the
        // moment the count crosses a page boundary. The arrows never disable — a disabled control
        // is invisible on e-ink; at the ends they simply have nothing to do.
        binding.listPager.visibility = if (pageCount > 1) View.VISIBLE else View.INVISIBLE
        binding.pageIndicator.text = getString(R.string.events_page_indicator, page + 1, pageCount)
    }

    private fun viewFor(row: EventsRow): View = when (row) {
        is EventsRow.Header -> EventRowView.buildHeader(
            this,
            getString(
                when (row.section) {
                    EventsPaging.Section.TODAY -> R.string.events_section_today
                    EventsPaging.Section.UPCOMING -> R.string.events_section_upcoming
                },
            ),
        )

        is EventsRow.Today -> EventRowView.buildEvent(
            context = this,
            badge = EventWording.timeBadge(row.event),
            title = row.event.title,
            meta = EventWording.meta(row.event),
            // A row of this day's list is edited — and deleted — as it is seen: from this day.
            onClick = { openEditor(row.event.id, day) },
            onDelete = { confirmDelete(row.event, day) },
        )

        is EventsRow.Upcoming -> EventRowView.buildEvent(
            context = this,
            badge = EventWording.upcomingBadge(row.upcoming.daysUntil),
            title = row.upcoming.event.title,
            meta = EventWording.upcomingMeta(row.upcoming),
            // An Upcoming row is about a day that is not this one: the editor is handed the
            // **occurrence start**, so "this occurrence" means the occurrence being looked ahead
            // to and not whatever the series happens to do today.
            onClick = { openEditor(row.upcoming.event.id, row.upcoming.occurrenceStart) },
            onDelete = { confirmDelete(row.upcoming.event, row.upcoming.occurrenceStart) },
        )
    }

    private fun turnPage(delta: Int) {
        val next = EventsPaging.clampPage(page + delta, rows, binding.listBand.height, headerHeightPx, rowHeightPx)
        if (next == page) return
        page = next
        render()
    }

    // ── The editor ───────────────────────────────────────────────────────────

    /** [id] null opens a new event on [viewedDay]; otherwise that event, as seen on that day. */
    private fun openEditor(id: String?, viewedDay: LocalDate) {
        if (busy) return
        editorLauncher.launch(
            Intent(this, EventEditorActivity::class.java)
                .putExtra(EventEditorActivity.EXTRA_EVENT_ID, id)
                .putExtra(EventEditorActivity.EXTRA_DAY, CalendarDates.format(viewedDay)),
        )
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    /**
     * The row's trash icon. A recurring event is asked which occurrences first; everything else
     * goes straight to the confirm. [viewedDay] is the day the row itself is about — `day` for a
     * Today row, the occurrence's own start for an Upcoming one, exactly as its edit door does.
     */
    private fun confirmDelete(event: Event, viewedDay: LocalDate) {
        if (busy) return
        if (event.recurrence != null) {
            scopeSheet(R.string.scope_title_delete) { scope -> confirmDelete(event, viewedDay, scope) }
            return
        }
        confirmDelete(event, viewedDay, Scope.ALL)
    }

    /** The three scopes. */
    private fun scopeSheet(titleRes: Int, onPicked: (Scope) -> Unit) {
        ActionSheetDialog(this)
            .title(getString(titleRes))
            .addAction(null, getString(R.string.scope_this)) { onPicked(Scope.THIS) }
            .addAction(null, getString(R.string.scope_following)) { onPicked(Scope.FOLLOWING) }
            .addAction(null, getString(R.string.scope_all)) { onPicked(Scope.ALL) }
            .show()
    }

    /** The confirm names what is about to go — "every occurrence" only when that is what ALL on
     *  a recurring event means, because a delete of one occurrence and a delete of a series are
     *  not the same act and must not read the same. */
    private fun confirmDelete(event: Event, viewedDay: LocalDate, scope: Scope) {
        val wholeSeries = event.recurrence != null && scope == Scope.ALL
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.events_delete_title, event.title))
                .setMessage(
                    if (wholeSeries) getString(R.string.events_delete_body_series, event.title)
                    else getString(R.string.events_delete_body_once),
                )
                .setPositiveButton(R.string.events_delete_confirm) { _, _ -> delete(event, viewedDay, scope) }
                .setNegativeButton(R.string.cancel, null)
                .create(),
        ).show()
    }

    private fun delete(event: Event, viewedDay: LocalDate, scope: Scope) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    // False means the viewed day maps to no occurrence: nothing to do, and never a
                    // whole-series delete by accident.
                    (application as CalsproutApp).events().delete(scope, event, viewedDay)
                } catch (e: StoreUnavailable) {
                    Slog.d(TAG) { "delete failed: store unavailable" }
                    false
                } catch (e: Exception) {
                    Slog.d(TAG) { "delete failed: ${e.javaClass.simpleName}" }
                    false
                }
            }
            busy = false
            if (isFinishing || isDestroyed) return@launch
            if (!ok) {
                Dialogs.problem(this@EventsActivity, R.string.events_delete_failed_title, R.string.events_delete_failed)
                return@launch
            }
            Slog.d(TAG) { "deleted ${event.id} at $scope" }
            // A delete changes the list, so the list is what is re-read.
            load()
        }
    }

    // ── Leaving ──────────────────────────────────────────────────────────────

    private fun setDayResult() {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ENDED_ON, CalendarDates.format(day)))
    }

    private fun finishWithDay() {
        setDayResult()
        finish()
    }

    // ── Small things ─────────────────────────────────────────────────────────

    /** A toast only ever confirms something that has already happened, or names a control that
     *  was long-pressed. */
    private fun hint(res: Int): Boolean {
        Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
        return true
    }

    companion object {
        private const val TAG = "EventsActivity"

        /** ISO `yyyy-MM-dd` — the day to open on. */
        const val EXTRA_DAY = "com.symmetricalpalmtree.soil.calsprout.DAY"

        /** ISO `yyyy-MM-dd` — the day the screen was left on, which the calendar follows. */
        const val EXTRA_ENDED_ON = "com.symmetricalpalmtree.soil.calsprout.ENDED_ON"
    }
}
