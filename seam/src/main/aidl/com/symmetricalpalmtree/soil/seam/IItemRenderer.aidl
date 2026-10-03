// IItemRenderer.aidl — what a Sprout app offers Soil for the one thing Soil cannot do with an
// item file: draw its pages. Soil's export screen binds the app's `<service>` that answers
// Seam.ACTION_RENDER for the item's kind, and the service opens the item through the seam like
// any screen of the app. Guarded by Soil's permission, and the service checks the caller again.
package com.symmetricalpalmtree.soil.seam;

import com.symmetricalpalmtree.soil.seam.SeamPageNames;

interface IItemRenderer {
    /** The item's pages in order: ids, 1-based numbers and titles (a page's first heading, or
     *  ""). What the export screen names a page by. */
    SeamPageNames pages(String itemId);

    /**
     * Render [pageIds] (every page when empty) into a Soil page bundle on [destination], a lossless
     * image per page at the page's own pixel size, [template] saying whether the paper goes
     * under the ink; [bundleVersion] the highest the taker reads, so endnotes and links are
     * added only for a taker that knows them. Answers the bundle's pages as named (numbers,
     * titles, and a count that includes any endnote pages). Throws IllegalStateException with
     * one of Seam.RENDER_* messages when it cannot.
     */
    SeamPageNames render(String itemId, in List<String> pageIds, boolean template, int bundleVersion, in ParcelFileDescriptor destination);

    /**
     * The statements that give an item file of this kind another id — every row and payload of
     * the app's that names the item. Soil runs them on an imported file before it enters the
     * library under a fresh id, through the seam's own statement checker. Pure.
     */
    List<String> relabelStatements(String oldId, String newId);
}
