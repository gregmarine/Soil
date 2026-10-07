package com.symmetricalpalmtree.soil.calsprout

import android.app.Application
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine
import com.symmetricalpalmtree.soil.seam.ISeamClient
import com.symmetricalpalmtree.soil.seam.ISeamStore
import com.symmetricalpalmtree.soil.seamkit.SeamConnection
import com.symmetricalpalmtree.soil.seamkit.SeamStoreRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CalsproutApp : Application() {

    /** The one way to Soil, for the process. */
    lateinit var soil: SeamConnection
        private set

    private val main = Handler(Looper.getMainLooper())

    /**
     * What a paper screen of this app answers Soil while it is in front: the calendar page, and
     * the event editor's note. Each attaches on resume and detaches on pause, so Soil's shell
     * knows paper is in front (its key filter is off over paper) for as long as either shows.
     * Notesprout's shape exactly.
     */
    interface FrontPaper {
        fun penIsActive(): Boolean
        fun letPanelGo()
        fun letPipelineGo()
    }

    @Volatile
    private var frontScreen: FrontPaper? = null

    /** The attach and detach calls in the order they were made: a screen resuming right after
     *  another paused must end attached. */
    private val seamSerial = Dispatchers.IO.limitedParallelism(1)

    private val client = object : ISeamClient.Stub() {
        override fun penActive(): Boolean {
            var active = false
            onMain { active = it.penIsActive() }
            return active
        }

        override fun releasePanel() = onMain { it.letPanelGo() }
        override fun releaseForHandoff() = onMain { it.letPipelineGo() }

        private fun onMain(action: (FrontPaper) -> Unit) {
            val screen = frontScreen ?: return
            val done = CountDownLatch(1)
            main.post {
                try { action(screen) } finally { done.countDown() }
            }
            done.await(400, TimeUnit.MILLISECONDS)
        }
    }

    // ── The store ──────

    /** The one lease on the app store for this process; see [calendar]. */
    private var lease: ISeamStore? = null
    private var store: CalendarStore? = null
    private var eventStore: EventStore? = null
    private val storeMutex = Mutex()

    /** Soil's hold on the lease: the lease dies with this binder, which lives as long as the process. */
    private val owner: IBinder = Binder()

    override fun onCreate() {
        super.onCreate()
        RattaEngine.register()
        soil = SeamConnection(this, BuildConfig.SOIL_PACKAGE)
        // Whatever Soil lent is dead with it: the next ask opens again.
        soil.onLost = { appScope.launch(Dispatchers.IO) { storeMutex.withLock { lease = null; store = null; eventStore = null } } }
    }

    /**
     * **The calendar, through one call** (`design.md` §3): the app store Soil lends this
     * package, opened once per process at [CalendarSchema]'s steps and shared by every screen
     * and the render service, with the one calendar's row minted on the first open. Blocking, IO
     * only; throws when Soil will not lend it (the library locked, Soil gone), and the caller says
     * so and leaves. A lease Soil has let go is opened again on the next ask.
     */
    suspend fun calendar(): CalendarStore = storeMutex.withLock {
        store?.let { return it }
        val seam = soil.seam()
        val opened = seam.openAppStore(CalendarSchema.SCHEMA, owner)
        val rows = SeamStoreRows(opened)
        val fresh = CalendarStore(rows)
        try {
            fresh.ensureCalendar()
        } catch (e: Exception) {
            runCatching { opened.close() }
            throw e
        }
        lease = opened
        store = fresh
        eventStore = EventStore(rows, fresh.calendarId)
        Log.i(TAG, "the calendar's store is open")
        fresh
    }

    /** The events half of the same store, for the same calendar — opened by [calendar] if it is
     *  not yet. Blocking, IO only; throws as [calendar] does. */
    suspend fun events(): EventStore {
        calendar()
        return storeMutex.withLock { checkNotNull(eventStore) { "the store closed" } }
    }

    /** A paper screen has come to the front: Soil may ask it for the panel. */
    fun front(screen: FrontPaper) {
        frontScreen = screen
        appScope.launch(seamSerial) { runCatching { soil.seam().attachClient(client) } }
    }

    fun left(screen: FrontPaper) {
        if (frontScreen === screen) frontScreen = null
        appScope.launch(seamSerial) { runCatching { soil.seam().detachClient(client) } }
    }

    /** A side-bar key a paper screen of this app received: Soil's shell reads the swipe from it. */
    fun barKey(event: android.view.KeyEvent) {
        val keyCode = event.keyCode; val action = event.action; val eventTime = event.eventTime; val repeatCount = event.repeatCount
        appScope.launch(Dispatchers.IO) { runCatching { soil.seam().barKey(keyCode, action, eventTime, repeatCount) } }
    }

    companion object {
        private const val TAG = "CalsproutApp"

        /** Outlives every screen, so work that must finish always does. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
