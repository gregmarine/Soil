package com.symmetricalpalmtree.soil.notesprout

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine
import com.symmetricalpalmtree.soil.notesprout.notebook.NotebookActivity
import com.symmetricalpalmtree.soil.seam.ISeamClient
import com.symmetricalpalmtree.soil.seamkit.SeamConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NotesproutApp : Application() {

    /** The one way to Soil, for the process. */
    lateinit var soil: SeamConnection
        private set

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var frontScreen: NotebookActivity? = null

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

        private fun onMain(action: (NotebookActivity) -> Unit) {
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

    /** A notebook screen has come to the front: Soil may ask it for the panel. */
    fun front(screen: NotebookActivity) {
        frontScreen = screen
        appScope.launch(Dispatchers.IO) { runCatching { soil.seam().attachClient(client) } }
    }

    fun left(screen: NotebookActivity) {
        if (frontScreen === screen) frontScreen = null
        appScope.launch(Dispatchers.IO) { runCatching { soil.seam().detachClient(client) } }
    }

    /** A side-bar key a paper screen of this app received: Soil's shell reads the swipe from it. */
    fun barKey(event: android.view.KeyEvent) {
        val keyCode = event.keyCode; val action = event.action; val eventTime = event.eventTime; val repeatCount = event.repeatCount
        appScope.launch(Dispatchers.IO) { runCatching { soil.seam().barKey(keyCode, action, eventTime, repeatCount) } }
    }

    companion object {
        /** Outlives every screen, so work that must finish always does. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
