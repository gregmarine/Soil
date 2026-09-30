package com.symmetricalpalmtree.soil.seam

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.symmetricalpalmtree.soil.data.index.SoilIndex
import com.symmetricalpalmtree.soil.paper.core.Slog

/**
 * **Soil's end of the seam** — the service the Sprout apps bind to.
 *
 * It is exported, because other apps must reach it, and guarded twice: the manifest gives it
 * Soil's signature permission, so Android refuses the bind to any app not signed with Soil's key,
 * and every call runs [SeamCallerCheck.enforce] before it does anything else.
 *
 * It answers one call for now. It never prompts and never shows anything: when the library is
 * locked it says so, and the person unlocks it in Soil.
 */
class SoilSeamService : Service() {

    private val seam = object : ISoilSeam.Stub() {
        override fun hello(): SeamHello {
            SeamCallerCheck.enforce(this@SoilSeamService)
            return SeamHello(seamVersion = Seam.VERSION, libraryUnlocked = SoilIndex.isReady())
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Slog.d(TAG) { "bound" }
        return seam
    }

    private companion object { const val TAG = "SoilSeam" }
}
