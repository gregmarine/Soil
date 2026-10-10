package com.symmetricalpalmtree.soil.notesprout

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine
import com.symmetricalpalmtree.soil.seam.ISeamClient
import com.symmetricalpalmtree.soil.seamkit.SeamConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NotesproutApp : Application() {

    /** The one way to Soil, for the process. */
    lateinit var soil: SeamConnection
        private set

    private val main = Handler(Looper.getMainLooper())

    /**
     * What a paper screen of this app answers Soil while it is in front: the notebook and the
     * sticky editor alike. Each attaches on resume and detaches on pause, so Soil's shell knows
     * paper is in front (its key filter is off over paper) for as long as either shows.
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

    /**
     * What Soil asks of the app in front. Each ask is run on the main thread, where the paper is,
     * and answered once it has run; Soil waits only so long, so nothing here waits longer.
     */
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

    override fun onCreate() {
        super.onCreate()
        RattaEngine.register()
        soil = SeamConnection(this, BuildConfig.SOIL_PACKAGE)
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
        // On the serial seam dispatcher: a down and its up cross the seam in the order they were made.
        appScope.launch(seamSerial) { runCatching { soil.seam().barKey(keyCode, action, eventTime, repeatCount) } }
    }

    /**
     * The chrome's hidden state, Soil's one flag for every paper screen (`SharedChrome`), or null
     * when Soil cannot say. On the serial seam dispatcher, behind any flip this process sent.
     */
    suspend fun sharedChromeHidden(): Boolean? =
        withContext(seamSerial) { runCatching { soil.seam().chromeHidden() }.getOrNull() }

    /** The person flipped the chrome on a paper screen of this app: Soil's flag follows. */
    fun putSharedChromeHidden(hidden: Boolean) {
        appScope.launch(seamSerial) { runCatching { soil.seam().setChromeHidden(hidden) } }
    }

    companion object {
        /** Outlives every screen, so work that must finish always does. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
