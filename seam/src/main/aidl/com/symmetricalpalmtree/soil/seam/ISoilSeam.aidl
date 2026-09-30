package com.symmetricalpalmtree.soil.seam;

import com.symmetricalpalmtree.soil.seam.ISeamItem;
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

    /** Make a new item of the schema's kind, with its file, and answer it. */
    SeamItem createItem(String name, in SeamSchema schema);

    /** Every item of a kind that has not been deleted, newest first. */
    List<SeamItem> listItems(String kind);

    /** One item, or null when there is none alive by that id. */
    SeamItem item(String itemId);

    void renameItem(String itemId, String name);

    /** A delete is soft: the row is marked and the file is kept. Refused while the item is open. */
    void deleteItem(String itemId);

    /**
     * Open an item for rows. The item must be of the schema's kind. The file is brought to the
     * schema first; one written by a later build is refused and left as it was.
     *
     * The owner is any binder of the app's own. Soil watches it: when the app's process dies the
     * session is ended and the file is closed and tidied.
     */
    ISeamItem openItem(String itemId, in SeamSchema schema, IBinder owner);
}
