package com.symmetricalpalmtree.soil.seam;

import com.symmetricalpalmtree.soil.seam.ISeamClient;
import com.symmetricalpalmtree.soil.seam.ISeamItem;
import com.symmetricalpalmtree.soil.seam.SeamBacklink;
import com.symmetricalpalmtree.soil.seam.SeamBytes;
import com.symmetricalpalmtree.soil.seam.SeamClip;
import com.symmetricalpalmtree.soil.seam.SeamTemplate;
import com.symmetricalpalmtree.soil.seam.SeamHello;
import com.symmetricalpalmtree.soil.seam.SeamItem;
import com.symmetricalpalmtree.soil.seam.SeamSchema;

/**
 * The seam: what a Sprout app may ask of Soil.
 *
 * It is guarded twice. Android refuses the bind to any app that does not hold Soil's signature
 * permission, which only an app signed with Soil's key can hold; and Soil checks the caller's
 * signing certificate again on every call.
 *
 * An app never touches a file and never sees a key. It asks for rows and hands rows back.
 *
 * Every call but hello() is refused with IllegalStateException while the library is not open.
 * Soil never prompts on an app's behalf: the person opens the library in Soil.
 */
interface ISoilSeam {

    /** The seam's version, and whether the library is unlocked. Never prompts. */
    SeamHello hello();

    /**
     * The app's paper screen is in front: Soil asks it to let the panel go before drawing over
     * it. One client at a time; a later attach replaces an earlier one, and an app's death
     * detaches it.
     */
    void attachClient(ISeamClient client);

    void detachClient(ISeamClient client);

    /** Make a new item of the schema's kind, with its file, and answer it. */
    SeamItem createItem(String name, in SeamSchema schema);

    /** Every item of a kind that has not been deleted, newest first. */
    List<SeamItem> listItems(String kind);

    /** The items of a kind opened most recently, latest first, at most limit of them. */
    List<SeamItem> recentItems(String kind, int limit);

    /** One item, or null when there is none alive by that id. */
    SeamItem item(String itemId);

    void renameItem(String itemId, String name);

    /** How many pages the item has, for the library. The app says; Soil does not read the file. */
    void setPageCount(String itemId, int count);

    /** A delete is soft: the row is marked and the file is kept. Refused while the item is open. */
    void deleteItem(String itemId);

    /**
     * The item's cover for the library: a small picture of its last-shown page, which the app
     * writes when it puts the item down. Lossy WEBP, at most 512 px on the long edge.
     */
    void setCover(String itemId, in SeamBytes cover);

    /**
     * Every link into an item, from the library's link index: what an app shows as "links to
     * here". Links from items that have been deleted are left out. The app narrows the list to
     * a page itself.
     */
    List<SeamBacklink> backlinks(String itemId);

    /** A template of the paper library, or null when there is none alive by that id. */
    SeamTemplate template(String templateId);

    /** The template's stored picture, the original bytes: what an app renders at a page's size. */
    SeamBytes templateImage(String templateId);

    /** The app applied the paper named by this card id to a page: Soil's Recents remember it. */
    void templateUsed(String cardId);

    /**
     * Park a picture an app made of a page, to be named and filed on Soil's save-template screen,
     * which the app starts next with the id answered here. Held in memory, one at a time; a
     * parking never taken up is dropped.
     */
    String stageTemplate(in SeamBytes image);

    /**
     * Open an item for rows. The item must be of the schema's kind. The file is brought to the
     * schema first; one written by a later build is refused and left as it was.
     *
     * The owner is any binder of the app's own. Soil watches it: when the app's process dies the
     * session is ended and the file is closed and tidied.
     */
    ISeamItem openItem(String itemId, in SeamSchema schema, IBinder owner);

    // ── The clipboard: one slot per kind of item, in the index, so a copy outlives the app ──────

    /** What the clipboard of [kind] holds, without the payload; null when it is empty. */
    @nullable SeamClip clipHeader(String kind);

    /**
     * Put a payload on the clipboard of [kind], replacing whatever was there. The bytes are the
     * app's own; Soil keeps them whole and never reads them. At most `SeamLimits.MAX_VALUE_BYTES`.
     */
    void putClip(String kind, in SeamClip header, in SeamBytes payload);

    /** The payload on the clipboard of [kind], whole; null when it is empty. */
    @nullable SeamBytes clip(String kind);

    void clearClip(String kind);

    // ── Pages and tags ──────

    /**
     * The item's pages in order, by id: what the library needs to name a page ("Page 3") without
     * opening the file. The app says whenever its page list changes; the page count follows.
     */
    void setPages(String itemId, in List<String> pageIds);

    /**
     * Put a tag on an item, or on one of its pages ([pageId] empty for the item itself): the text
     * is normalised, the tag made if the library has never seen it, and attached. Idempotent.
     * Answers the tag's stored spelling. Refused with `SeamLimits.TAGS_FULL` when a cap is reached.
     */
    String assignTag(String itemId, String pageId, String text);

    /**
     * Park a short text for a screen of Soil's that is started next, so it never rides an
     * Intent: the tag screen's prefill. Held in memory, one at a time.
     */
    String stageText(String text);
}
