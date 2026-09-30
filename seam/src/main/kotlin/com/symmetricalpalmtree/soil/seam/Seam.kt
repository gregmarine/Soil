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
     * The action of the activity an app opens an item in. Soil starts it, and only Soil may: the
     * activity is guarded by [permissionFor]. What rides the Intent is an id, never a key.
     */
    const val ACTION_OPEN_ITEM = "com.symmetricalpalmtree.soil.action.OPEN_ITEM"

    /** On that activity, as meta-data: the kind of item it opens. */
    const val META_KIND = "com.symmetricalpalmtree.soil.kind"

    /** The id of the item to open. */
    const val EXTRA_ITEM_ID = "com.symmetricalpalmtree.soil.extra.ITEM_ID"

    /** Whether [appPackage] and [hubPackage] are of the same build: a debug Soil opens debug apps
     *  and a release Soil release apps, so the two installs never cross. */
    fun sameBuild(hubPackage: String, appPackage: String): Boolean =
        hubPackage.endsWith(DEV_SUFFIX) == appPackage.endsWith(DEV_SUFFIX)

    /**
     * The signature permission guarding the seam of the Soil installed as [hubPackage]. It is
     * named after the install, so a debug Soil and a release Soil never declare the same name.
     */
    fun permissionFor(hubPackage: String): String = "$hubPackage.permission.SEAM"
}
