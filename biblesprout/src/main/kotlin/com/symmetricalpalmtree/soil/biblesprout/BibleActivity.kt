package com.symmetricalpalmtree.soil.biblesprout

import android.content.Intent
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.soil.bibleref.*
import com.symmetricalpalmtree.soil.biblesprout.databinding.ActivityBibleBinding
import com.symmetricalpalmtree.soil.biblesprout.reader.ChapterPaginator
import com.symmetricalpalmtree.soil.biblesprout.reader.PageMark
import com.symmetricalpalmtree.soil.biblesprout.reader.ReaderView
import com.symmetricalpalmtree.soil.paper.core.Dialogs
import com.symmetricalpalmtree.soil.paper.core.ListSwipe
import com.symmetricalpalmtree.soil.paper.core.Slog
import com.symmetricalpalmtree.soil.paper.core.TopGuard
import com.symmetricalpalmtree.soil.seam.BibleAddress
import com.symmetricalpalmtree.soil.seam.ISeamStore
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seamkit.SeamStoreRows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **The reader's screen**: Notesprout SN's `BibleActivity`, whole, without SN's host handshake.
 * The one screen is the reader's whole surface: the chapter flow, the three panels' doors
 * (Contents, Recents, Search), the passage mode with its Full chapter door, cross-references and
 * footnotes. The doors live here, the models and the panels in their own files, and every door
 * shares the one `loading` latch, the one mode pair and the one position writer.
 *
 * There is no paper on it: no `PaperView`, no g-paper call and no EPD handoff. Do not add one.
 *
 * The work all belongs to [ChapterLoader] and happens on `Dispatchers.IO`: installing the bundled
 * source, opening it, turning a chapter's blocks into atoms and paginating them against the
 * band's real size. Main only draws the finished page. The band must be laid out before any of
 * it starts: pagination is against `readingWidth()`/`readingHeight()`, which are zero until then.
 *
 * **Where the user was.** The stored [Position] lives in Biblesprout's app store, opened over the
 * seam as the screen opens ([storeReady]); the chapter opens on the page carrying that verse, and
 * every committed turn writes the new one back, fire-and-forget. A store that will not answer
 * (Soil locked, Soil gone) costs the bookmark and the recents and nothing else: never a dialog.
 *
 * **The chapter flows.** A turn past the last page opens the next chapter at its first page, a
 * turn back off page 0 the previous chapter at its last; across books, by [ChapterCursor].
 * Genesis 1 page 1 and Revelation 22's last page are silent no-ops. While a chapter is loading,
 * further turns are ignored: a latch, never a queue.
 *
 * **The hand turns the page.** One [ListSwipe] over the reader, fed from `dispatchTouchEvent` as
 * an observer, so the pager buttons keep working, and it drops stylus sequences itself. A
 * one-finger swipe down opens the Contents, a two-finger swipe down the Recents, a one-finger
 * swipe up walks back along a trail of hops.
 *
 * **The passage view.** The same screen in a second mode: opened by Soil on a wire
 * ([Seam.EXTRA_BIBLE_WIRE], a link followed), the reader shows exactly the verses of that
 * reference under its canonical label, with a Full chapter door at the bar's end. A turn past
 * either end is a silent no-op and no position is written. Picking a chapter from the Contents
 * or the Recents switches this screen into chapter mode in place. Full chapter and a
 * cross-reference tap each launch a SECOND instance of this Activity over this one, so Back
 * comes back to where the reader was.
 *
 * **Nothing about what is read is ever logged**: book and chapter numbers, page counts and
 * durations only ("where, not what").
 */
class BibleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBibleBinding
    private lateinit var readerView: ReaderView
    private lateinit var loader: ChapterLoader
    private lateinit var passages: PassageLoader

    /** The app store, once Soil has lent it; null when it would not. See [remember]. */
    private val storeReady = CompletableDeferred<BibleStore?>()
    private var lease: ISeamStore? = null

    /** What the lease is bound to: this screen's life. */
    private val owner: IBinder = Binder()

    private var chapter: ChapterPages? = null

    /** The passage on screen. **Non-null is passage mode**, the one flag the screen branches on;
     *  [chapter] is null while it stands, and vice versa. */
    private var passage: PassagePages? = null

    private var pageIndex = 0

    /** A chapter-mode landing from our own Full chapter button, read from the Intent in
     *  [onCreate]; null for every other launch. Its presence wins over a wire. */
    private var landing: Landing? = null

    /** The wire this showing opens on, read once in [onCreate]; null for the reader where it was left. */
    private var openingReference: String? = null

    /** True when THIS process launched this instance, one screen further along a trail, which a
     *  swipe up walks back. The instance Soil or the launcher opened is the trail's origin. */
    private var ownLaunch = false

    /** The footnote popup while it is up; null otherwise. One at a time, dismissed on the way out. */
    private var footnotePopup: FootnotePopup? = null

    /** True while a chapter is being built. The latch that makes a fast flip drop, not queue. */
    private var loading = false

    /** The one-finger flip, and the swipes, over the reading band. Built in [onCreate]. */
    private var swipe: ListSwipe? = null

    private var contentsPanel: ContentsPanel? = null
    private var recentsPanel: RecentsPanel? = null
    private var gatheringRecents = false
    private var searchPanel: SearchPanel? = null
    private var lastSearch: SearchResults? = null
    private var searching = false

    /** Position writes: the one in flight, and the latest one that arrived while it was. */
    private var writing = false
    private var pendingWrite: String? = null

    /** The "Loading…" line, shown only if the work outlasts [LOADING_DELAY_MS]. */
    private val showLoading = Runnable { binding.loading.visibility = View.VISIBLE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        landing = landingFromIntent()
        openingReference = if (landing != null) null else wireFromIntent(intent)
        ownLaunch = landing != null || intent?.hasExtra(EXTRA_OWN) == true
        binding = ActivityBibleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        loader = ChapterLoader(this)
        passages = PassageLoader(loader)
        openStore()

        readerView = ReaderView(this)
        binding.readerBand.addView(
            readerView,
            0, // under the loading line, which the band keeps on top
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        // Armed on the reading band only: a drag across the chrome is not a page turn.
        swipe = ListSwipe(
            region = { readerView },
            onFlipNext = { turnTo(pageIndex + 1) },
            onFlipPrevious = { turnTo(pageIndex - 1) },
            onSwipeDown = { openContents() },
            onSwipeUp = { walkBack() },
            onTwoFingerSwipeDown = { openRecents() },
            onTap = { x, y -> onPageTap(x, y) },
        )

        binding.title.setText(R.string.bible_title)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack.setOnLongClickListener { hint(R.string.cd_bible_back) }
        // Two doors to the same panel: the button, and the title that names where you are.
        binding.btnContents.setOnClickListener { openContents() }
        binding.btnContents.setOnLongClickListener { hint(R.string.cd_bible_contents) }
        binding.title.setOnClickListener { openContents() }
        binding.title.setOnLongClickListener { hint(R.string.cd_bible_contents) }
        binding.btnRecents.setOnClickListener { openRecents() }
        binding.btnRecents.setOnLongClickListener { hint(R.string.cd_bible_recents) }
        binding.btnSearch.setOnClickListener { openSearch() }
        binding.btnSearch.setOnLongClickListener { hint(R.string.cd_bible_search) }
        binding.btnFullChapter.setOnClickListener { openFullChapter() }
        binding.btnFullChapter.setOnLongClickListener { hint(R.string.bible_full_chapter) }
        binding.btnPrevPage.setOnClickListener { turnTo(pageIndex - 1) }
        binding.btnPrevPage.setOnLongClickListener { hint(R.string.cd_bible_prev_page) }
        binding.btnNextPage.setOnClickListener { turnTo(pageIndex + 1) }
        binding.btnNextPage.setOnLongClickListener { hint(R.string.cd_bible_next_page) }
        for (button in listOf<View>(
            binding.btnBack, binding.btnContents, binding.btnSearch, binding.btnRecents,
            binding.btnFullChapter, binding.btnCopy, binding.btnPrevPage, binding.btnNextPage, binding.btnNotes,
        )) {
            TooltipCompat.setTooltipText(button, button.contentDescription)
        }
        // The title is centred on the SCREEN, so its margins must clear the WIDER of the two
        // groups, and the end one grows by a word when Full chapter shows.
        binding.topBarRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> balanceTitle() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        // Pagination needs the band's real size, so nothing starts before its first layout.
        binding.readerBand.doOnLayout { openFirst() }
    }

    /** A link followed while this instance is up: the passage opens in place, a deliberate move. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val wire = wireFromIntent(intent) ?: return
        if (readerView.readingWidth() <= 0) { openingReference = wire; return }
        openPassage(wire, stamp = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        // A Dialog outliving its finishing Activity is a window leak.
        contentsPanel?.dismiss()
        recentsPanel?.dismiss()
        searchPanel?.dismiss()
        footnotePopup?.dismiss()
        binding.root.removeCallbacks(showLoading)
        loader.close()
        val held = lease
        lease = null
        if (held != null) Thread { runCatching { held.close() } }.start()
    }

    /** The swipe detector is an observer fed from here: it consumes nothing, so dispatch always
     *  continues to the views and every button keeps its tap. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipe?.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // --- the store ----------------------------------------------------------

    /**
     * The app store, lent by Soil over the seam, opened as the screen opens and never waited for
     * on Main. Everything that needs it ([openFirst], the recents, the position) takes
     * [storeReady]; a store Soil would not lend is null there, logged by class only.
     */
    private fun openStore() {
        lifecycleScope.launch(Dispatchers.IO) {
            val store = runCatching {
                val seam = (application as BiblesproutApp).soil.seam()
                val opened = seam.openAppStore(BibleSchema.SCHEMA, owner)
                lease = opened
                BibleStore(SeamStoreRows(opened))
            }.onFailure { e ->
                Log.w(TAG, "the store could not be opened: ${e.javaClass.simpleName}")
            }.getOrNull()
            storeReady.complete(store)
        }
    }

    /** The store, when it has answered; null before that and when there is none. Cheap. */
    private fun storeNow(): BibleStore? = if (storeReady.isCompleted) storeReady.getCompleted() else null

    // --- reading ------------------------------------------------------------

    /**
     * The first open, in the mode this instance was launched in: our own Full chapter landing,
     * else the wire Soil handed over, else the stored position, or Genesis 1 when there is none.
     */
    private fun openFirst() {
        landing?.let { at ->
            openChapter(at.ref) { pages -> ChapterPaginator.pageContaining(pages.anchors, at.verse) }
            return
        }
        openingReference?.let { wire ->
            // Followed from a notebook or a document: a deliberate move, and therefore a pick.
            openPassage(wire, stamp = true)
            return
        }
        lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { storeReady.await()?.readPosition() }.getOrNull()
            }
            val at = Position.decode(raw) ?: Position.GENESIS_1
            openChapter(ChapterRef(at.usfm, at.chapter)) { pages ->
                ChapterPaginator.pageContaining(pages.anchors, at.verse)
            }
        }
    }

    /** Loads [ref] and shows the page [pageFor] picks out of it (first, last, or a verse's). */
    private fun openChapter(ref: ChapterRef, pageFor: (ChapterPages) -> Int) {
        if (loading) return
        val width = readerView.readingWidth()
        val height = readerView.readingHeight()
        if (width <= 0 || height <= 0) return
        loading = true
        binding.root.postDelayed(showLoading, LOADING_DELAY_MS)
        lifecycleScope.launch {
            val began = SystemClock.elapsedRealtime()
            val built = withContext(Dispatchers.IO) { runCatching { loader.chapter(ref, width, height) } }
            binding.root.removeCallbacks(showLoading)
            binding.loading.visibility = View.GONE
            loading = false
            built
                .onSuccess { pages ->
                    chapter = pages
                    // A chapter established: whatever passage stood here is over. Done on SUCCESS
                    // only: a failed open must leave the screen as it was.
                    passage = null
                    applyMode()
                    Slog.d(TAG) {
                        "chapter ${pages.usfm} ${pages.chapter}: ${pages.size} page(s) in " +
                            "${SystemClock.elapsedRealtime() - began} ms"
                    }
                    binding.title.text = getString(R.string.bible_chapter_title, pages.bookName, pages.chapter)
                    show(pageFor(pages).coerceIn(0, pages.size - 1))
                    prefetchNeighbours(ref, width, height)
                }
                .onFailure { e ->
                    // The chapter reference, never its text: "where, not what".
                    Log.w(TAG, "could not open ${ref.usfm} ${ref.chapter}", e)
                    Dialogs.problem(this@BibleActivity, R.string.bible_unavailable_title, R.string.bible_unavailable_body)
                }
        }
    }

    /**
     * Opens a passage: [wire] decoded, read, flowed and paginated by [PassageLoader], then page 1.
     * [stamp] records it as a pick: true for the wire Soil opened us on and for a row re-picked
     * from the Recents. The same `loading` latch a chapter takes. A wire the source has nothing
     * for is the problem dialog; **the wire itself is never logged**.
     */
    private fun openPassage(wire: String, stamp: Boolean) {
        if (loading) return
        val width = readerView.readingWidth()
        val height = readerView.readingHeight()
        if (width <= 0 || height <= 0) return
        loading = true
        binding.root.postDelayed(showLoading, LOADING_DELAY_MS)
        lifecycleScope.launch {
            val began = SystemClock.elapsedRealtime()
            val built = withContext(Dispatchers.IO) { runCatching { passages.passage(wire, width, height) } }
            binding.root.removeCallbacks(showLoading)
            binding.loading.visibility = View.GONE
            loading = false
            built
                .onSuccess { pages ->
                    passage = pages
                    chapter = null
                    Slog.d(TAG) { "passage: ${pages.size} page(s) in ${SystemClock.elapsedRealtime() - began} ms" }
                    binding.title.text = pages.label
                    applyMode()
                    show(0)
                    if (stamp) recordRecentReference(pages.wire)
                }
                .onFailure { e ->
                    Log.w(TAG, "could not open a passage", e)
                    Dialogs.problem(this@BibleActivity, R.string.bible_unavailable_title, R.string.bible_passage_unavailable_body)
                }
        }
    }

    /** Builds the chapters on either side into the loader's cache, after the current chapter is
     *  on screen, so a flow across a chapter edge is one `invalidate()`. */
    private fun prefetchNeighbours(ref: ChapterRef, width: Int, height: Int) {
        val cursor = loader.cursorNow() ?: return
        val neighbours = listOfNotNull(cursor.next(ref), cursor.prev(ref)).filter { loader.cached(it) == null }
        if (neighbours.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            for (neighbour in neighbours) {
                if (!isActive) return@launch
                runCatching { loader.chapter(neighbour, width, height) }
                    .onFailure { Slog.d(TAG) { "prefetch ${neighbour.usfm} ${neighbour.chapter} skipped" } }
            }
        }
    }

    /** A turn, from a pager tap or a swipe. Inside the chapter it is a page; off either end it
     *  flows into the neighbouring chapter, and at the ends of the book it is a silent no-op. */
    private fun turnTo(index: Int) {
        if (loading) return
        passage?.let { pages ->
            if (index in 0 until pages.size) show(index)
            return
        }
        val pages = chapter ?: return
        when {
            index in 0 until pages.size -> show(index)
            index >= pages.size -> loader.cursorNow()?.next(pages.ref)?.let { openChapter(it) { 0 } }
            else -> loader.cursorNow()?.prev(pages.ref)?.let { next -> openChapter(next) { it.size - 1 } }
        }
    }

    /** Draws page [index] and remembers it: every committed turn in chapter mode is a written
     *  position; a passage writes none. */
    private fun show(index: Int) {
        passage?.let { pages ->
            pageIndex = index
            readerView.show(pages.rendered[index])
            binding.pageIndicator.text = getString(R.string.bible_page_indicator, index + 1, pages.size)
            return
        }
        val pages = chapter ?: return
        pageIndex = index
        readerView.show(pages.rendered[index])
        binding.pageIndicator.text = getString(R.string.bible_page_indicator, index + 1, pages.size)
        remember(pages, index)
    }

    // --- the Contents -------------------------------------------------------

    /** The Contents panel over the loader's one read of the source's `book` table. Before the
     *  first chapter has shown there are none and the call does nothing; while one is up a second
     *  call is a no-op too. A picked chapter opens at its first page. */
    private fun openContents() {
        if (contentsPanel != null) return
        val books = loader.booksNow()
        val at = currentChapter() ?: return
        if (books.isEmpty()) return
        contentsPanel = ContentsPanel(
            this, books, at,
            onDismissed = { contentsPanel = null },
            onPicked = { picked -> goTo(picked) },
        ).also { it.show() }
    }

    /** A **pick**: a chapter chosen by name, as opposed to a turn. It opens at its first page and
     *  is recorded as a recent. Dropped whole while a load is running. */
    private fun goTo(ref: ChapterRef) {
        if (loading) return
        recordRecent(ref)
        openChapter(ref) { 0 }
    }

    /** A passage pick, from the Recents: opens in place and is re-stamped as the newest recent. */
    private fun goToPassage(wire: String) {
        if (loading) return
        openPassage(wire, stamp = true)
    }

    // --- the Recents --------------------------------------------------------

    /** The Recents panel: the rows read from the store on IO first (a store that will not answer
     *  is an empty list, never a dialog), then selected by [RecentChapters.select] and shown. */
    private fun openRecents() {
        if (recentsPanel != null || gatheringRecents) return
        gatheringRecents = true
        lifecycleScope.launch {
            val began = SystemClock.elapsedRealtime()
            val stored = withContext(Dispatchers.IO) {
                val store = runCatching { storeReady.await() }.getOrNull()
                runCatching { store?.readRecents(RecentChapters.KEEP) }.getOrNull().orEmpty() to
                    runCatching { store?.readRecentRefs(RecentChapters.KEEP) }.getOrNull().orEmpty()
            }
            gatheringRecents = false
            if (recentsPanel != null || isFinishing || isDestroyed) return@launch
            val (storedChapters, storedRefs) = stored
            val rows = RecentChapters.select(storedChapters, storedRefs, chapter?.ref, passage?.wire)
            Slog.d(TAG) {
                "recents: ${rows.size} of ${storedChapters.size}+${storedRefs.size} in ${SystemClock.elapsedRealtime() - began} ms"
            }
            recentsPanel = RecentsPanel(
                this@BibleActivity, rows,
                onDismissed = { recentsPanel = null },
                onPicked = { picked -> goTo(picked) },
                onPickedPassage = { wire -> goToPassage(wire) },
            ).also { it.show() }
        }
    }

    /** Stamps [ref] at the front of the recents, fire-and-forget on IO. A failure is a log line,
     *  never a dialog, and the reference itself is never logged. */
    private fun recordRecent(ref: ChapterRef) {
        val at = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            val store = runCatching { storeReady.await() }.getOrNull() ?: return@launch
            runCatching { store.writeRecent(ref, at, RecentChapters.KEEP) }.onFailure { Slog.d(TAG) { "recent not saved" } }
        }
    }

    /** [recordRecent] for a passage: the wire is the row's key, and it is never logged. */
    private fun recordRecentReference(wire: String) {
        val at = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            val store = runCatching { storeReady.await() }.getOrNull() ?: return@launch
            runCatching { store.writeRecentRef(wire, at, RecentChapters.KEEP) }.onFailure { Slog.d(TAG) { "recent reference not saved" } }
        }
    }

    // --- the Search ---------------------------------------------------------

    /** The Search panel. One showing at a time; it opens on the last results, if any. */
    private fun openSearch() {
        if (searchPanel != null) return
        searchPanel = SearchPanel(
            this, lastSearch,
            onDismissed = { searchPanel = null },
            onQuery = { typed -> search(typed) },
            onPicked = { hit -> goTo(hit.ref, hit.verse) },
        ).also { it.show() }
    }

    /**
     * What was typed, answered. A reference is checked against the source on IO (one the source
     * has no verses for is searched as words) and opened through the doors a pick takes, the
     * panel dismissed first. Words are searched on IO and handed back to the panel. One search at
     * a time. **Nothing typed is ever logged**, and neither is what was found.
     */
    private fun search(typed: String) {
        if (searching) return
        val panel = searchPanel ?: return
        searching = true
        lifecycleScope.launch {
            val began = SystemClock.elapsedRealtime()
            val route = withContext(Dispatchers.IO) {
                runCatching { loader.withDatabase { db -> SearchRoute.classify(typed, db::chapterCount) } }
                    .getOrElse { SearchRoute.classify(typed) }
            }
            val exists = when (route) {
                is SearchRoute.Words -> false
                is SearchRoute.Chapter -> withContext(Dispatchers.IO) {
                    runCatching { loader.withDatabase { db -> route.ref.chapter <= db.chapterCount(route.ref.usfm) } }.getOrDefault(false)
                }
                is SearchRoute.Passage -> withContext(Dispatchers.IO) {
                    runCatching {
                        loader.withDatabase { db -> ReferenceResolver.valid(route.passages, db::chapterCount, db::verseExists) }
                    }.getOrDefault(false)
                }
            }
            if (isFinishing || isDestroyed) { searching = false; return@launch }
            if (exists) {
                searching = false
                panel.dismiss()
                when (route) {
                    is SearchRoute.Chapter -> goTo(route.ref)
                    is SearchRoute.Passage -> goToPassage(route.wire)
                    is SearchRoute.Words -> Unit
                }
                return@launch
            }
            if (panel.isShowing) panel.showSearching()
            val found = withContext(Dispatchers.IO) { runCatching { loader.withDatabase { db -> db.search(typed) } } }
            searching = false
            found
                .onSuccess { results ->
                    lastSearch = results
                    Slog.d(TAG) { "search: ${results.hits.size} of ${results.total} in ${SystemClock.elapsedRealtime() - began} ms" }
                    if (panel.isShowing) panel.showResults(results)
                }
                .onFailure { e ->
                    Log.w(TAG, "search failed", e)
                    if (panel.isShowing) panel.showResults(SearchResults(typed, emptyList(), emptyList(), 0))
                }
        }
    }

    /** A [goTo] that lands on the page carrying [verse]: a search hit's door. */
    private fun goTo(ref: ChapterRef, verse: Int) {
        if (loading) return
        recordRecent(ref)
        openChapter(ref) { pages -> ChapterPaginator.pageContaining(pages.anchors, verse) }
    }

    // --- where the user was -------------------------------------------------

    /** Writes the page's position, fire-and-forget. **Coalesced**: while one write is in flight
     *  the next replaces the one waiting. A failure costs the bookmark and is swallowed with a
     *  log line. A store not yet lent costs this one write and nothing else. */
    private fun remember(pages: ChapterPages, index: Int) {
        val store = storeNow() ?: return
        val verse = pages.anchors.getOrElse(index) { 1 }
        val value = Position(pages.usfm, pages.chapter, verse).encode()
        if (writing) {
            pendingWrite = value
            return
        }
        writing = true
        lifecycleScope.launch {
            var next: String? = value
            while (next != null) {
                val writeMe = next
                withContext(Dispatchers.IO) { runCatching { store.writePosition(writeMe) } }
                    .onFailure { Slog.d(TAG) { "position not saved" } }
                next = pendingWrite
                pendingWrite = null
            }
            writing = false
        }
    }

    // --- cross references -----------------------------------------------------

    /** A finger tap on the page. Only a chapter page has anything to hit: the `\r` lines'
     *  references and the footnote callers. A tap on nothing is a no-op. */
    private fun onPageTap(x: Float, y: Float) {
        if (loading || passage != null) return
        when (val mark = readerView.markAt(x, y) ?: return) {
            is PageMark.Reference -> {
                Slog.d(TAG) { "cross-reference tap" }
                openPassageOver(XrefWire.of(mark.targetStartKey, mark.targetEndKey))
            }
            is PageMark.Caller -> showFootnote(mark)
        }
    }

    /** The referenced passage, one screen further: a second instance in passage mode, so Back
     *  returns to this chapter, on this page. The extras never leave this process. */
    private fun openPassageOver(wire: String) {
        startActivity(Intent(this, BibleActivity::class.java).putExtra(Seam.EXTRA_BIBLE_WIRE, wire).putExtra(EXTRA_OWN, true))
    }

    /**
     * A one-finger swipe up on the page: the notebook's walk-back gesture, in the reader. An
     * instance this process launched finishes back onto the screen it came from; the instance
     * Soil opened on a link leaves to what followed it, as Back does. The reader opened from its
     * own icon is the origin of nothing, so the swipe is silent there.
     */
    private fun walkBack() {
        if (loading) return
        if (ownLaunch || openingReference != null) {
            Slog.d(TAG) { "walk back" }
            finish()
        }
    }

    /** The footnote behind a tapped caller, in a [FootnotePopup] under its line. One at a time. */
    private fun showFootnote(mark: PageMark.Caller) {
        if (footnotePopup != null) return
        val pages = chapter ?: return
        val note = pages.footnotesById[mark.footnoteId] ?: return
        val line = readerView.lineBounds(mark) ?: return
        val at = IntArray(2)
        readerView.getLocationOnScreen(at)
        line.offset(at[0], at[1])
        val where = note.label
            ?: note.verseKey?.let { "${VerseKey.chapterOf(it)}:${VerseKey.verseOf(it)}" }
            ?: pages.chapter.toString()
        val heading = getString(R.string.bible_chapter_title_text, Canon.chapterTitleName(pages.usfm), where)
        Slog.d(TAG) { "footnote tap: ${pages.noteLinks[note.id].orEmpty().size} link(s)" }
        footnotePopup = FootnotePopup(
            activity = this,
            anchor = Rect(line),
            heading = heading,
            text = note.text,
            links = pages.noteLinks[note.id].orEmpty(),
            onDismissed = { footnotePopup = null },
            onNavigate = { startKey, endKey -> openPassageOver(XrefWire.of(startKey, endKey)) },
        ).also { it.show() }
    }

    // --- the passage's doors ------------------------------------------------

    /** **Full chapter**: a second instance of this Activity in chapter mode, opened at the first
     *  range's verse, so Back comes back to the passage. */
    private fun openFullChapter() {
        val pages = passage ?: return
        startActivity(
            Intent(this, BibleActivity::class.java)
                .putExtra(EXTRA_USFM, pages.openAt.usfm)
                .putExtra(EXTRA_CHAPTER, pages.openAt.chapter)
                .putExtra(EXTRA_VERSE, pages.openVerse)
                .putExtra(EXTRA_OWN, true),
        )
    }

    /** Our own chapter launch, or null for every other. Total: an extra set this build cannot
     *  read opens the reader where it was left, never a failure. */
    private fun landingFromIntent(): Landing? {
        val extras = intent ?: return null
        val book = Canon.tryUsfm(extras.getStringExtra(EXTRA_USFM) ?: return null) ?: return null
        val chapter = extras.getIntExtra(EXTRA_CHAPTER, 0)
        if (chapter < 1) return null
        return Landing(ChapterRef(book.usfm, chapter), extras.getIntExtra(EXTRA_VERSE, 1).coerceAtLeast(1))
    }

    /** The wire an Intent carries, when it carries one this build can read. Untrusted input. */
    private fun wireFromIntent(intent: Intent?): String? =
        intent?.getStringExtra(Seam.EXTRA_BIBLE_WIRE)?.takeIf { BibleAddress.isWire(it) && ReferenceCodec.decode(it) != null }

    /** Where the Contents and the Recents think the reader is: the chapter, or the passage's first. */
    private fun currentChapter(): ChapterRef? = chapter?.ref ?: passage?.openAt

    // --- chrome -------------------------------------------------------------

    /** The chrome the mode owns: the Full chapter door, which belongs to a passage and to
     *  nothing else. GONE, never disabled: a disabled button is invisible on e-ink. */
    private fun applyMode() {
        binding.btnFullChapter.visibility = if (passage != null) View.VISIBLE else View.GONE
    }

    /** Keeps the title's centre honest: its two margins must be equal and must clear the wider
     *  of the bar's two groups. Measured, never a dp constant. */
    private fun balanceTitle() {
        val widest = maxOf(binding.startGroup.width, binding.endGroup.width)
        if (widest <= 0) return
        val params = binding.title.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.marginStart == widest && params.marginEnd == widest) return
        params.marginStart = widest
        params.marginEnd = widest
        // Posted: we are inside the bar's own layout pass.
        binding.title.post { binding.title.requestLayout() }
    }

    /** Every icon button names itself on a long press: words read better than glyphs on e-ink. */
    private fun hint(res: Int): Boolean {
        Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
        return true
    }

    /** Where our own Full chapter launch lands: the chapter, and the verse to open on. */
    private class Landing(val ref: ChapterRef, val verse: Int)

    companion object {
        private const val TAG = "BibleScreen"

        /** A load faster than this says nothing; only a slow one gets a word on screen. */
        private const val LOADING_DELAY_MS = 300L

        // Our own launches' extras: this screen launching itself, in-process only, so they are
        // not part of any contract and nothing outside this file writes them.
        private const val EXTRA_USFM = "com.symmetricalpalmtree.soil.biblesprout.USFM"
        private const val EXTRA_CHAPTER = "com.symmetricalpalmtree.soil.biblesprout.CHAPTER"
        private const val EXTRA_VERSE = "com.symmetricalpalmtree.soil.biblesprout.VERSE"

        /** Set on every launch this process makes: one hop along a trail a swipe up walks back. */
        private const val EXTRA_OWN = "com.symmetricalpalmtree.soil.biblesprout.OWN"
    }
}
