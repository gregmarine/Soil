package com.symmetricalpalmtree.soil.notesprout

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.symmetricalpalmtree.soil.notesprout.data.NotebookPrefs
import com.symmetricalpalmtree.soil.notesprout.notebook.NotebookActivity
import com.symmetricalpalmtree.soil.seam.Seam

/**
 * What Notesprout's own icon does: reopen the notebook last open, at the page it was left on;
 * with none, go to the library, which is Soil's home. It shows nothing itself.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val last = NotebookPrefs(this).lastNotebookId
        val intent = if (last != null) {
            Intent(this, NotebookActivity::class.java).setAction(Seam.ACTION_OPEN_ITEM).putExtra(Seam.EXTRA_ITEM_ID, last)
        } else {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(BuildConfig.SOIL_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
        finish()
    }
}
