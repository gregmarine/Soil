package com.symmetricalpalmtree.soil

import android.app.Application
import com.symmetricalpalmtree.gpaper.ratta.RattaEngine
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SoilApp : Application() {

    override fun onCreate() {
        super.onCreate()
        RattaEngine.register()
        // The index is opened here, off the main thread, and never waited for: on a first launch
        // it spends seconds deriving keys, and Soil is the home screen — it must be usable
        // meanwhile. Every screen reads `SoilIndex.state`.
        appScope.launch(Dispatchers.IO) { SoilIndex.ensureReady(this@SoilApp) }
    }

    companion object {
        /** Outlives every screen, so work that must finish — a flush, an open — always does. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
