package com.symmetricalpalmtree.soil.seam

/** The interface between Soil and the Sprout apps. */
object Seam {
    /**
     * The one version number of the seam. It does NOT change during development: it is frozen
     * at the first release build that is actually put to use, and goes up from there.
     */
    const val VERSION = 1

    /** The release Soil; a debug Soil is [HUB_PACKAGE] + [DEV_SUFFIX]. */
    const val HUB_PACKAGE = "com.symmetricalpalmtree.soil"
    const val DEV_SUFFIX = ".dev"

    const val SERVICE_CLASS = "com.symmetricalpalmtree.soil.seam.SoilSeamService"

    /**
     * The signature permission guarding the seam of the Soil installed as [hubPackage]. It is
     * named after the install, so a debug Soil and a release Soil never declare the same name.
     */
    fun permissionFor(hubPackage: String): String = "$hubPackage.permission.SEAM"
}
