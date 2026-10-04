package com.symmetricalpalmtree.soil.ext

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process

/**
 * The extension's own gate: every stub method calls [enforce] first. The caller's uid must be
 * [hostPackage], Soil, and share this extension's signature. Anything else is refused with a
 * `SecurityException`, the one marshalable refusal.
 */
object HostCallerCheck {
    /** A screen started for a result: the caller must be Soil, by package and signature. A screen
     *  that fails is finished here; the answer says whether to go on. */
    fun enforceActivity(activity: android.app.Activity, hostPackage: String): Boolean {
        val caller = activity.callingPackage
        val ok = caller == hostPackage && activity.packageManager.checkSignatures(caller, activity.packageName) == PackageManager.SIGNATURE_MATCH
        if (!ok) activity.finish()
        return ok
    }

    fun enforce(context: Context, hostPackage: String) {
        val pm = context.packageManager
        val uid = Binder.getCallingUid()
        val callerPackages = pm.getPackagesForUid(uid) ?: emptyArray()
        if (hostPackage !in callerPackages) throw SecurityException("caller is not Soil")
        if (pm.checkSignatures(uid, Process.myUid()) != PackageManager.SIGNATURE_MATCH) throw SecurityException("caller is not Soil")
    }
}
