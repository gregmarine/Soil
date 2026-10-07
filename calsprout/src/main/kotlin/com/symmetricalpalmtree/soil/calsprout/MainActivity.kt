package com.symmetricalpalmtree.soil.calsprout

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * What Calsprout's own icon, and its row in Soil's side menu, does: open the calendar where it
 * was left. It shows nothing itself.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startActivity(Intent(this, CalendarActivity::class.java)) }
        finish()
    }
}
