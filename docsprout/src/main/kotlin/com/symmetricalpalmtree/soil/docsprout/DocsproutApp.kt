package com.symmetricalpalmtree.soil.docsprout

import android.app.Application
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

    companion object {
        /** Outlives every screen, so a save that must finish always does. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
