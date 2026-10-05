package com.symmetricalpalmtree.soil.docsprout

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.symmetricalpalmtree.soil.docsprout.data.DocsproutPrefs
import com.symmetricalpalmtree.soil.docsprout.editor.DocumentActivity
import com.symmetricalpalmtree.soil.seam.Seam

/**
 * What Docsprout's own icon, and its row in Soil's side menu, does: reopen the document last
 * open; with none, go to the library, which is Soil's home. It shows nothing itself.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val last = DocsproutPrefs(this).lastDocumentId
        val intent = if (last != null) {
            Intent(this, DocumentActivity::class.java).setAction(Seam.ACTION_OPEN_ITEM).putExtra(Seam.EXTRA_ITEM_ID, last)
        } else {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(BuildConfig.SOIL_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
        finish()
    }
}
