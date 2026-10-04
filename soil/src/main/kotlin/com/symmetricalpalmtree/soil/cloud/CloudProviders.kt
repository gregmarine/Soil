package com.symmetricalpalmtree.soil.cloud

import android.content.Context
import com.symmetricalpalmtree.soil.ext.CloudContract
import com.symmetricalpalmtree.soil.ext.Extension
import com.symmetricalpalmtree.soil.ext.Extensions

/** The one installed cloud provider, or null. Looked for every time it is about to be offered. */
object CloudProviders {
    fun installed(context: Context): Extension? = Extensions.find(context, CloudContract.ACTION_CLOUD_STORAGE).firstOrNull()
}
