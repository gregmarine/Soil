package com.symmetricalpalmtree.soil.seam;

import com.symmetricalpalmtree.soil.seam.SeamBytes;

/**
 * One open item, as the app that opened it holds it. It answers that app and no other.
 *
 * Statements and rows cross whole, in shared memory. Every statement is checked before it runs.
 */
interface ISeamItem {

    /**
     * Run the statements of the batch in order, in ONE transaction: all of them land or none do.
     * Answers the rows each statement changed.
     */
    long[] exec(in SeamBytes batch);

    /** Run one SELECT to completion. The whole result, in one piece. */
    SeamBytes query(in SeamBytes statement);

    /**
     * Let go of the file without ending the session: what is written is folded into the file and
     * the connection is released. Nothing is purged, so what the app could undo it still can.
     */
    void park();

    /** Take the file up again after park(). */
    void resume();

    /**
     * End the session. When tidy is true, and no other session holds the item, what the app has
     * soft-deleted is purged and the space given back.
     */
    void close(boolean tidy);
}
