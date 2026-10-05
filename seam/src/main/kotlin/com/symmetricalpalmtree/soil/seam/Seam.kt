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

    // ── Export: Soil's screen, and the app's renderer behind it ──────

    /**
     * The action of a Sprout app's `<service>` that renders its kind's pages for an export
     * (`IItemRenderer`), guarded by [permissionFor]; [META_KIND] on it names the kind.
     */
    const val ACTION_RENDER = "com.symmetricalpalmtree.soil.action.RENDER"

    /**
     * The action of Soil's export screen, started by an app for one of its items: [EXTRA_ITEM_ID],
     * [EXTRA_PAGE_ID] to offer that page as a scope, and [EXTRA_RETURN_TO_APP] when the app closed
     * the item to export it and wants it reopened after. Explicit to Soil's package.
     */
    const val ACTION_EXPORT = "com.symmetricalpalmtree.soil.action.EXPORT"
    const val EXTRA_RETURN_TO_APP = "com.symmetricalpalmtree.soil.extra.RETURN_TO_APP"

    /** What a renderer says when it cannot: the exact messages of its IllegalStateException. */
    const val RENDER_EMPTY = "render: no pages"
    const val RENDER_TOO_LONG = "render: too many pages"
    const val RENDER_DAMAGED = "render: a page has no size"
    const val RENDER_FAILED = "render: failed"

    /** Why an app would not take a file in (`IItemRenderer.ingest`). */
    const val INGEST_NOT_TEXT = "ingest: not text"
    const val INGEST_TOO_LARGE = "ingest: too large"
    const val INGEST_FAILED = "ingest: failed"

    /** The page sizes an item that flows can be laid out at, chosen on the export screen. */
    const val PAGE_LETTER = "letter"
    const val PAGE_A4 = "a4"
    const val PAGE_SCREEN = "screen"

    /** The pixels an inch a paper-size page picture is drawn at. A PDF made of such pictures
     *  puts each pixel at `72 / PAPER_DPI` points, so its pages are the paper's size. */
    const val PAPER_DPI = 200

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

    /**
     * With [EXTRA_ITEM_ID] on an open: the paper Soil's New Notebook screen chose for the item's
     * first page, a `TemplatePick` as encoded. The app resolves it as it resolves any pick.
     */
    const val EXTRA_TEMPLATE_PICK = "com.symmetricalpalmtree.soil.extra.TEMPLATE_PICK"

    /**
     * The action of Soil's item picker, started for a result by an app that needs an item of the
     * library: the link picker's notebook shelves. [EXTRA_KIND] narrows it to one kind;
     * [EXTRA_EXCLUDE_ITEM_ID] hides one item (the one the asking app has open). The answer is
     * [EXTRA_ITEM_ID].
     */
    const val ACTION_PICK_ITEM = "com.symmetricalpalmtree.soil.action.PICK_ITEM"
    const val EXTRA_KIND = "com.symmetricalpalmtree.soil.extra.KIND"
    const val EXTRA_EXCLUDE_ITEM_ID = "com.symmetricalpalmtree.soil.extra.EXCLUDE_ITEM_ID"

    /**
     * With [ACTION_PICK_ITEM]: true asks, once an item with pages is chosen, whether the whole
     * item is meant or one of its pages. The answer then carries [EXTRA_PAGE_ID] for a page, and
     * [EXTRA_ITEM_NAME] always: what the library calls the item, for the words of a link.
     */
    const val EXTRA_PICK_PAGE = "com.symmetricalpalmtree.soil.extra.PICK_PAGE"
    const val EXTRA_ITEM_NAME = "com.symmetricalpalmtree.soil.extra.ITEM_NAME"

    /**
     * The action that follows a link to an item of any kind: Soil opens [EXTRA_ITEM_ID] in the
     * app for its kind, at [EXTRA_PAGE_ID] when one rides along. An app follows a link into its
     * own kind itself; this is for a link that leaves it (a notebook's to a document, a
     * document's to anything), since only Soil knows which app opens what. A target that is
     * gone, or has no app, is explained by Soil. Guarded by the seam permission.
     */
    const val ACTION_FOLLOW = "com.symmetricalpalmtree.soil.action.FOLLOW"

    /**
     * With [EXTRA_ITEM_ID] on an open: the page to land on, for an open from the library's search
     * (a tagged page). Consumed once by the app.
     */
    const val EXTRA_PAGE_ID = "com.symmetricalpalmtree.soil.extra.PAGE_ID"

    /**
     * The action of Soil's tag screen, started for a result by an app for one of its items:
     * [EXTRA_ITEM_ID], [EXTRA_PAGE_ID] for a page of it (absent for the item itself), and
     * [EXTRA_TAG_MODE]. A prefill for the field is parked with `stageText` and named by
     * [EXTRA_STAGED_ID]: what a person wrote never rides an Intent.
     */
    const val ACTION_TAGS = "com.symmetricalpalmtree.soil.action.TAGS"
    const val EXTRA_TAG_MODE = "com.symmetricalpalmtree.soil.extra.TAG_MODE"

    /** The target's tags, for reading and editing. */
    const val TAG_MODE_BROWSE = 0

    /** The same, with the field focused and the keyboard up. */
    const val TAG_MODE_ADD = 1

    /** The item and every page of it, an overview to drill into. */
    const val TAG_MODE_MANAGE = 2

    /**
     * The action of Soil's Scratch Pad, started for a result by an app over its own paper: the pad
     * opens with its Send buttons, and what it sends is taken with `takeIncomingInk` when the pad
     * has closed. Ink parked with `sendInkToPad` just before lands on it as it opens.
     */
    const val ACTION_SCRATCH_PAD = "com.symmetricalpalmtree.soil.action.SCRATCH_PAD"

    /** Where ink sent to the pad lands: a new page after the current one, or the current page. */
    const val PAD_PLACEMENT_NEW_PAGE = 0
    const val PAD_PLACEMENT_CURRENT_PAGE = 1

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
