// IExtStore.aidl — a store of Soil's lent to an extension for one call: rows in, rows out,
// through the seam's own encoding (SeamBytes, RowCodec). Soil opens the encrypted file, makes
// the table, checks every statement and closes the lease after the call. The extension never
// sees a path or a key, and Soil never reads the rows.
package com.symmetricalpalmtree.soil.ext;

import com.symmetricalpalmtree.soil.seam.SeamBytes;

interface IExtStore {
    /** Statements encoded with RowCodec, run in one transaction; the change count of each. */
    long[] exec(in SeamBytes batch);

    /** One SELECT, encoded; its rows, encoded. */
    SeamBytes query(in SeamBytes statement);
}
