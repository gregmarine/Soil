// IImporter.aidl — the exporter's mirror. Soil keys (probe, unlock, re-key to this device,
// placement, the index); the extension only streams the picked document (read fd) into a cache
// file Soil owns (write fd). No passphrase, no path, no SQLCipher ever crosses.
package com.symmetricalpalmtree.soil.ext;

import com.symmetricalpalmtree.soil.ext.ImporterInfo;
import com.symmetricalpalmtree.soil.ext.ImportSpec;
import com.symmetricalpalmtree.soil.ext.ImportResult;

interface IImporter {
    /** The formats this importer accepts: label, file extensions, MIME types. Fast. */
    ImporterInfo describe();

    /** Stream [source] to [destination] and report the bytes written. The extension closes both
     *  descriptors before returning. Same exceptions as IExporter.export. Named importDocument
     *  because `import` is a reserved word in the generated Java. */
    ImportResult importDocument(in ParcelFileDescriptor source, in ParcelFileDescriptor destination, in ImportSpec spec);
}
