package com.symmetricalpalmtree.soil.biblesprout

import android.app.Application
import com.symmetricalpalmtree.soil.seamkit.SeamConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BiblesproutApp : Application() {

    /** The one way to Soil, for the process. */
    lateinit var soil: SeamConnection
        private set

    override fun onCreate() {
        super.onCreate()
        soil = SeamConnection(this, BuildConfig.SOIL_PACKAGE)
    }

    companion object {
        /** Outlives every screen, so the last position write, and the lease's close after it,
         *  always finish. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
