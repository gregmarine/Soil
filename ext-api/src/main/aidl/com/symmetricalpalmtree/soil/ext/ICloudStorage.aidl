// ICloudStorage.aidl — cloud storage, generic over a provider: folders by name under the
// provider's own root, files by opaque id, bytes over descriptors. The extension owns the
// provider's sign-in; its token lives in a store Soil lends it on every call (an IExtStore over
// an encrypted store of Soil's, keyed by the extension's package, with one table `account(key
// TEXT PRIMARY KEY, value TEXT NOT NULL)` that Soil makes). Soil never reads what is in it.
// Only SecurityException, IllegalArgumentException and IllegalStateException cross; two
// IllegalStateException messages are read verbatim: ExtContract.CLOUD_NOT_CONNECTED and
// ExtContract.CLOUD_NETWORK.
package com.symmetricalpalmtree.soil.ext;

import com.symmetricalpalmtree.soil.ext.CloudEntry;
import com.symmetricalpalmtree.soil.ext.CloudStatus;
import com.symmetricalpalmtree.soil.ext.IExtStore;

interface ICloudStorage {
    /** From the store alone, never the network. */
    CloudStatus status(IExtStore store);

    /** Revoke what can be revoked and forget the account. Idempotent. */
    void disconnect(IExtStore store);

    /** Park the store for the connect screen Soil is about to start (ExtContract.ACTION_CLOUD_SCREEN). */
    void beginConnect(IExtStore store);

    /** Forget the parked store. Idempotent. */
    void endConnect();

    /** The folder at [path] (names under the root; empty = the root): folders first, then files,
     *  by name; a missing folder lists as empty; at most ExtContract.CLOUD_MAX_LIST_ENTRIES. */
    CloudEntry[] list(IExtStore store, in String[] path);

    /** Find or create every segment. */
    CloudEntry ensureFolder(IExtStore store, in String[] path);

    /** Put [source]'s [expectedBytes] bytes at [path]/[name], replacing a file of that name. The
     *  extension closes the descriptor. Answers the provider's entry for it. */
    CloudEntry upload(IExtStore store, in String[] path, String name, String mime, in ParcelFileDescriptor source, long expectedBytes);

    /** Stream [entryId] into [destination], truncated first and synced after. Answers the bytes. */
    long download(IExtStore store, String entryId, in ParcelFileDescriptor destination);

    /** Idempotent: a missing entry is a success. */
    void delete(IExtStore store, String entryId);
}
