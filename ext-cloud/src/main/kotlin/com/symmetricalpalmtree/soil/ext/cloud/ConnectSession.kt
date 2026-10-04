package com.symmetricalpalmtree.soil.ext.cloud

import com.symmetricalpalmtree.soil.ext.IExtStore

/** The store Soil parked for the connect screen, between beginConnect and endConnect. */
object ConnectSession {
    @Volatile
    var store: IExtStore? = null

    @Synchronized
    fun clear() { store = null }
}
