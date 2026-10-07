package com.symmetricalpalmtree.soil.sketchsprout

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.symmetricalpalmtree.soil.seam.Seam
import com.symmetricalpalmtree.soil.sketchsprout.sketch.SketchActivity

/**
 * What Sketchsprout's own icon, and its row in Soil's side menu, does: reopen the sketchbook last
 * open, at the page it was left on; with none, go to the library, which is Soil's home. It shows
 * nothing itself.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val last = SketchPrefs(this).lastSketchbookId
        val intent = if (last != null) {
            Intent(this, SketchActivity::class.java).setAction(Seam.ACTION_OPEN_ITEM).putExtra(Seam.EXTRA_ITEM_ID, last)
        } else {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(BuildConfig.SOIL_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
        finish()
    }
}
