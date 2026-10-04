package com.symmetricalpalmtree.soil.ext

import android.os.Parcel
import android.os.Parcelable

/**
 * What `IExporter.export()` reports: the bytes it wrote to the destination. Soil verifies the
 * count before it says "Exported": an exporter that died mid-stream must never read as success.
 */
class ExportResult(val bytesWritten: Long) : Parcelable {

    init {
        require(bytesWritten >= 0L) { "negative bytesWritten $bytesWritten" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(bytesWritten)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ExportResult> = object : Parcelable.Creator<ExportResult> {
            override fun createFromParcel(parcel: Parcel): ExportResult = ExportResult(parcel.readLong())
            override fun newArray(size: Int): Array<ExportResult?> = arrayOfNulls(size)
        }
    }
}
