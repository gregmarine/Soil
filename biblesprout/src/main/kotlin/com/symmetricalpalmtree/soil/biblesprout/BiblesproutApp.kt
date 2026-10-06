package com.symmetricalpalmtree.soil.biblesprout

import android.app.Application
import com.symmetricalpalmtree.soil.seamkit.SeamConnection

class BiblesproutApp : Application() {

    /** The one way to Soil, for the process. */
    lateinit var soil: SeamConnection
        private set

    override fun onCreate() {
        super.onCreate()
        soil = SeamConnection(this, BuildConfig.SOIL_PACKAGE)
    }
}
