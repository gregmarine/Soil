package com.symmetricalpalmtree.soil.seam;

import com.symmetricalpalmtree.soil.seam.ISeamClient;
import com.symmetricalpalmtree.soil.seam.ISeamItem;
import com.symmetricalpalmtree.soil.seam.ISeamStore;
import com.symmetricalpalmtree.soil.seam.SeamBacklink;
import com.symmetricalpalmtree.soil.seam.SeamBibleBacklink;
import com.symmetricalpalmtree.soil.seam.SeamBytes;
import com.symmetricalpalmtree.soil.seam.SeamClip;
import com.symmetricalpalmtree.soil.seam.SeamRecognizer;
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

    /**
     * Every link into the verses startKey..endKey (the codec's verse keys), from items that are
     * alive, one row per range of each link, in reading order: what the Bible's Notes panel
     * shows. At most a few hundred.
     */
    List<SeamBibleBacklink> bibleBacklinks(int startKey, int endKey);

    /**
     * The verses of a passage as Markdown, from the Bible's own app: a bold label line, then the
     * verses as prose with plain verse numbers, one paragraph per chapter run. At most a chapter.
     * Refused with Seam.BIBLE_NO_APP when Biblesprout is not installed, Seam.BIBLE_TOO_LONG for
     * more than a chapter, Seam.BIBLE_UNREADABLE for a wire it cannot read, and
     * Seam.BIBLE_FAILED when it did not answer. Slow on a first call: the reader may be copying
     * its Bible out of its APK.
     */
    String passageText(String wire);

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

    /**
     * The app's own store, for an app that keeps its data in Soil and not in item files
     * (Biblesprout's position and recents): one encrypted database per app, named after the
     * calling package, made on first use and brought to [schema] (its steps are the only DDL it
     * ever sees; `schema.kind` names it). Under the global key, re-keyed and backed up with
     * everything else. The lease answers the caller's uid alone and ends when [owner] dies or
     * `close()` is called. Refused while the library is not open.
     */
    ISeamStore openAppStore(in SeamSchema schema, IBinder owner);

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

    // ── Ink between a notebook and the Scratch Pad ──────

    /**
     * Park ink for the Scratch Pad, an `InkWire` document: the pad places it on a new page
     * ([Seam.PAD_PLACEMENT_NEW_PAGE]) or its current page when it next shows. The app then opens
     * the pad with [Seam.ACTION_SCRATCH_PAD]. Held in memory, one at a time; a copy, never a move.
     */
    void sendInkToPad(in SeamBytes ink, int placement);

    // ── Recognition, relayed to the recogniser chosen in Soil's Settings ──────

    /** The chosen recogniser and language, or null when none is installed or chosen. */
    @nullable SeamRecognizer recognizer();

    /** One of `SeamRecognizer.STATUS_*`. Refused with `SeamLimits.NO_RECOGNIZER` when there is none. */
    int recognizerStatus();

    /** Start acquiring the model. The person has been asked by the app; nothing else may start it. */
    void prepareRecognizer();

    /**
     * Recognise one writing area: [ink] an `InkWire` document (only the geometry is read),
     * [areaWidth]/[areaHeight] > 0, [preContext] the text just before it. The text, lines joined
     * by '\n', or "". Refused with `SeamLimits.RECOGNIZER_NOT_READY` when the model is not there
     * yet, `SeamLimits.INK_TOO_LARGE` over the caps, and `SeamLimits.RECOGNITION_FAILED` otherwise.
     */
    String recognizeInk(in SeamBytes ink, float areaWidth, float areaHeight, String preContext);

    /** Recognise a whole page: the recogniser finds lines and paragraphs itself. Same refusals. */
    String recognizePage(in SeamBytes ink, float pageWidth, float pageHeight);

    // ── The side bars ──────

    /**
     * A side-bar key the app's paper screen received in its window, as it came: Soil's shell
     * reads the swipe from it and opens its menu over the app. The shell's own system-wide key
     * filter is off while an app's paper is in front (it let a resting palm cut the pen's
     * stream), so this is how the bars reach Soil there. Observed only; the app consumes nothing.
     */
    void barKey(int keyCode, int action, long eventTime, int repeatCount);

    // ── One kind's content made into another kind's item ──────

    /**
     * A new item made from a file an app wrote: [file] is handed, with [fileExtension], to the
     * app that takes such files in (`IItemRenderer.ingest`), as an import from a picked file is.
     * It lands in the folder [besideItemId] is in, called [name], or "[name] Copy" when an item
     * of its own kind there has the name. This is how a notebook's words become a document: the
     * notebook's app writes Markdown, and the document's app makes the document. Refused with
     * `Seam.MAKE_NO_APP` when nothing takes the file, and one of `Seam.INGEST_*` when the app
     * would not; nothing is left behind either way.
     */
    SeamItem makeItemFromFile(String besideItemId, String name, String fileExtension, in SeamBytes file);
}
