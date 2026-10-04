package com.symmetricalpalmtree.soil.docsprout.export

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.symmetricalpalmtree.soil.seam.IItemRenderer
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.seam.SeamCallerCheck
import com.symmetricalpalmtree.soil.seam.SeamPageNames

/**
 * **The document for Soil's export and import.** Soil binds this, guarded by its own permission.
 * Today it answers only what an import needs, the statements that give a file another id; a
 * document has no fixed pages, and laying it out as pages for an export is not built yet, so a
 * render is refused as empty.
 */
class RenderService : Service() {

    private val binder = object : IItemRenderer.Stub() {

        override fun pages(itemId: String): SeamPageNames {
            SeamCallerCheck.enforce(this@RenderService)
            return SeamPageNames(emptyList(), emptyList(), emptyList())
        }

        override fun render(itemId: String, pageIds: List<String>?, template: Boolean, bundleVersion: Int, destination: ParcelFileDescriptor?): SeamPageNames {
            SeamCallerCheck.enforce(this@RenderService)
            runCatching { destination?.close() }
            throw IllegalStateException(Seam.RENDER_EMPTY)
        }

        override fun relabelStatements(oldId: String, newId: String): List<String> {
            SeamCallerCheck.enforce(this@RenderService)
            return Relabel.statements(oldId, newId)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
