package com.symmetricalpalmtree.soil.biblesprout

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * What Biblesprout's own icon, and its row in Soil's side menu, does: open the reader where it
 * was left. It shows nothing itself.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(Intent(this, BibleActivity::class.java)) }
        finish()
    }
}
