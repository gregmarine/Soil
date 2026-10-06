package com.symmetricalpalmtree.soil.seam;

import com.symmetricalpalmtree.soil.seam.SeamBytes;

/**
 * An app's own store, as the app that opened it holds it: Soil's app store for an app that keeps
 * its data in Soil rather than in item files (the reader's position and recents). It answers
 * that app and no other, and ends with the app's process.
 *
 * Statements and rows cross whole, in shared memory. Every statement is checked before it runs;
 * the tables come from the schema the open declared, never from a statement.
 */
interface ISeamStore {

    /** Run the statements of the batch in order, in ONE transaction. Answers the rows each changed. */
    long[] exec(in SeamBytes batch);

    /** Run one SELECT to completion. The whole result, in one piece. */
    SeamBytes query(in SeamBytes statement);

    /** The lease is over: every later call is refused. The store itself stays open in Soil. */
    void close();
}
