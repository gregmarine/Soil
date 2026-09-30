package com.symmetricalpalmtree.soil.notesprout

import android.app.Application
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine
import com.symmetricalpalmtree.soil.seamkit.SeamConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NotesproutApp : Application() {

    /** The one way to Soil, for the process. */
    lateinit var soil: SeamConnection
        private set

    override fun onCreate() {
        super.onCreate()
        RattaEngine.register()
        soil = SeamConnection(this, BuildConfig.SOIL_PACKAGE)
    }

    companion object {
        /** Outlives every screen, so work that must finish — a flush, a close — always does. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
