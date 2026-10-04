// IExporter.aidl — an export format. Soil keys and prepares; the extension only delivers:
// everything that touches a key (the checkpoint, the keying transform, the destination) runs in
// Soil. What the read fd carries is the descriptor's sourceKind: the prepared item file, streamed
// verbatim, or a host-rendered PageBundle for a format that transforms (a PDF, an image) and so
// never sees the file itself. No passphrase, no path, no SQLCipher ever crosses — with one
// bounded exception: ExportSpec.exportSecret, a password typed for the OUTPUT file, which opens
// nothing of Soil's. Stateless: one bind per call, or one bind held across a per-page loop.
package com.symmetricalpalmtree.soil.ext;

import com.symmetricalpalmtree.soil.ext.ExporterInfo;
import com.symmetricalpalmtree.soil.ext.ExportSpec;
import com.symmetricalpalmtree.soil.ext.ExportResult;

interface IExporter {
    /** The one format this exporter offers: label, file extension, MIME, a bounded option list
     *  Soil renders with its own widgets, the source kind it needs and how it delivers. A
     *  descriptor over the caps in ExtContract fails at unmarshal and Soil drops the exporter.
     *  Must stay the reply's trailing payload. Fast; never touches storage. */
    ExporterInfo describe();

    /** Turn [source] (what the source kind asked for) into [destination] and report the bytes
     *  actually written there — a measured count, never a guess. [spec] must stay the trailing
     *  argument. The extension closes both descriptors before returning, success or not, and
     *  drops any exportSecret in its own finally. Throws IllegalArgumentException for a spec it
     *  cannot serve and IllegalStateException on a delivery failure — the only marshalable
     *  exceptions besides the caller check's SecurityException. */
    ExportResult export(in ParcelFileDescriptor source, in ParcelFileDescriptor destination, in ExportSpec spec);
}
