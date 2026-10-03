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

    /**
     * Instead of an id: the name of a **new** item. The app makes it through the seam, since
     * only the app knows the shape of its own files, and opens it.
     */
    const val EXTRA_NEW_NAME = "com.symmetricalpalmtree.soil.extra.NEW_NAME"

    // ── The paper library's two screens, which Soil shows on an app's behalf ──────

    /**
     * The action of Soil's template picker, started for a result by an app that needs paper for
     * a page. Explicit to Soil's package. [EXTRA_CURRENT_TOKEN] names the paper in force so its
     * card is ticked; the answer is [EXTRA_PICK], a `TemplatePick` as encoded, never pixels.
     */
    const val ACTION_PICK_TEMPLATE = "com.symmetricalpalmtree.soil.action.PICK_TEMPLATE"
    const val EXTRA_CURRENT_TOKEN = "com.symmetricalpalmtree.soil.extra.CURRENT_TOKEN"
    const val EXTRA_PICK = "com.symmetricalpalmtree.soil.extra.PICK"

    /**
     * The action of Soil's save-template screen: a picture an app parked with `stageTemplate`
     * gets a name and a folder here. [EXTRA_STAGED_ID] is the parking id; [EXTRA_SEED_NAME] the
     * name to offer. `RESULT_OK` means saved.
     */
    const val ACTION_SAVE_TEMPLATE = "com.symmetricalpalmtree.soil.action.SAVE_TEMPLATE"
    const val EXTRA_STAGED_ID = "com.symmetricalpalmtree.soil.extra.STAGED_ID"
    const val EXTRA_SEED_NAME = "com.symmetricalpalmtree.soil.extra.SEED_NAME"

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
