package com.symmetricalpalmtree.soil.paper.recognition

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether the active network carries validated internet. A model download offered offline would hang, not fail. */
object Connectivity {
    fun isOnline(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
