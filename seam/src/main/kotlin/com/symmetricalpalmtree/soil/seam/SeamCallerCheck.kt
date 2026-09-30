package com.symmetricalpalmtree.soil.seam

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import android.util.Log

/**
 * **The second guard of the seam**, run first in every call Soil answers: the caller must be
 * signed with Soil's own certificate.
 *
 * The first guard is the signature permission on the service, which stops a stranger at the bind,
 * before any of Soil's code runs. This one does not depend on that: a permission is a declaration
 * in a manifest, and this is a check made at the moment of the call, against the caller Android
 * itself names.
 *
 * Trust rests on one signing key. Soil is built for one person, and there is no per-app
 * permission model: an app is either signed with the key or it is a stranger.
 */
object SeamCallerCheck {

    private const val TAG = "SeamCallerCheck"

    /** Pure: what the comparison of the two certificates has to have answered. */
    fun trusted(sameUid: Boolean, signatures: Int): Boolean =
        sameUid || signatures == PackageManager.SIGNATURE_MATCH

    /**
     * Throws [SecurityException] unless the app making this call is Soil itself or is signed with
     * Soil's certificate. Call it on the binder thread, before anything else.
     */
    fun enforce(context: Context) {
        val caller = Binder.getCallingUid()
        val mine = Process.myUid()
        val signatures = if (caller == mine) PackageManager.SIGNATURE_MATCH
        else context.packageManager.checkSignatures(mine, caller)
        if (!trusted(sameUid = caller == mine, signatures = signatures)) {
            Log.w(TAG, "refused uid $caller: not signed with Soil's certificate")
            throw SecurityException("the caller is not signed with Soil's certificate")
        }
    }
}
