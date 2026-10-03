package com.symmetricalpalmtree.soil.ext

import android.os.Parcel
import android.os.Parcelable

/**
 * What `IImporter.importDocument()` reports: the bytes it wrote to the cache file. Soil verifies
 * the count against the document's size, where the provider will say, before probing a byte.
 */
class ImportResult(val bytesWritten: Long) : Parcelable {

    init {
        require(bytesWritten >= 0L) { "negative bytesWritten $bytesWritten" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(bytesWritten)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ImportResult> = object : Parcelable.Creator<ImportResult> {
            override fun createFromParcel(parcel: Parcel): ImportResult = ImportResult(parcel.readLong())
            override fun newArray(size: Int): Array<ImportResult?> = arrayOfNulls(size)
        }
    }
}
