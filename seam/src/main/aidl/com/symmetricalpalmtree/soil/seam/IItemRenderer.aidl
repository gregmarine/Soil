// IItemRenderer.aidl — what a Sprout app offers Soil for the one thing Soil cannot do with an
// item file: draw its pages. Soil's export screen binds the app's `<service>` that answers
// Seam.ACTION_RENDER for the item's kind, and the service opens the item through the seam like
// any screen of the app. Guarded by Soil's permission, and the service checks the caller again.
package com.symmetricalpalmtree.soil.seam;

import com.symmetricalpalmtree.soil.seam.SeamPageNames;
import com.symmetricalpalmtree.soil.seam.SeamRenderInfo;

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

    // ── Added for kinds that flow (documents). New methods go at the end, so an app built
    //    before them still answers the ones above; Soil treats one that cannot answer `describe`
    //    as SeamRenderInfo.PAGES_ONLY. ──

    /** What this app's kind is to an export: whether it flows, and the formats the app writes
     *  itself. */
    SeamRenderInfo describe();

    /**
     * As `render`, for an item that flows: the whole item laid out on pages of [pageSize] (one
     * of Seam.PAGE_*), on a white ground. A paper size is drawn at Seam.PAPER_DPI pixels an
     * inch; the screen size at the screen's own pixels.
     */
    SeamPageNames renderFlow(String itemId, String pageSize, int bundleVersion, in ParcelFileDescriptor destination);

    /**
     * Write the item in one of the formats `describe` named, finished, onto [destination].
     * [pageSize] is one of Seam.PAGE_* and is read only by a paged format. Throws
     * IllegalStateException with one of the Seam.RENDER_* messages when it cannot.
     */
    void produce(String itemId, String formatId, String pageSize, in ParcelFileDescriptor destination);

    /**
     * Take a file in as the content of [itemId]: an item of this kind that Soil has just made,
     * empty, for it. [fileExtension] is the picked file's, one of those `describe` named, and
     * [source] its bytes, as untrusted as any file's. The app opens the item through the seam
     * and writes it. Throws IllegalStateException with one of the Seam.INGEST_* messages when
     * the file is not something it can take; Soil then takes the empty item away again.
     */
    void ingest(String itemId, String fileExtension, in ParcelFileDescriptor source);
}
