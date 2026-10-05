package com.symmetricalpalmtree.soil.docsprout

import android.app.Application
import android.os.Binder
import com.symmetricalpalmtree.soil.docsprout.data.DocumentSchema
import kotlinx.coroutines.launch
import com.symmetricalpalmtree.soil.seamkit.SeamConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DocsproutApp : Application() {

    /** The one way to Soil, for the process. */
    lateinit var soil: SeamConnection
        private set

    override fun onCreate() {
        super.onCreate()
        soil = SeamConnection(this, BuildConfig.SOIL_PACKAGE)
    }

    /** What Soil holds the pad-taker against: this process's death is a detach. */
    private val padOwner = Binder()

    /** The attach and detach calls in the order they were made: a screen starting right after
     *  another stopped must end attached. */
    private val seamSerial = Dispatchers.IO.limitedParallelism(1)

    /**
     * A document screen is showing ([showing] true) or has left: while one shows, the Scratch
     * Pad opened over it offers Send, and what it sends comes here as ink to be read. The
     * screen is not paper, so this is all Soil is told: no client is attached, and the side
     * bars go on working as over any app.
     */
    fun takesPadSends(showing: Boolean) {
        appScope.launch(seamSerial) {
            runCatching { if (showing) soil.seam().attachPadTaker(padOwner, DocumentSchema.KIND) else soil.seam().detachPadTaker(padOwner) }
        }
    }

    companion object {
        /** Outlives every screen, so a save that must finish always does. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
