package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/**
 * One link into the Bible, as the library's link index holds it: which item and page it was
 * made on (the page's number too, 0 for an item with no pages), the whole wire it names, and the
 * verse-key span of the one range this row is. Only links from items that are alive are
 * answered. Never logged: a wire names where someone reads, and a name is their own.
 *
 * Wire form: `String linkId · String sourceItemId · String sourceKind · String sourceName ·
 * String sourcePageId · int pageNumber · String wire · int startKey · int endKey`.
 */
data class SeamBibleBacklink(
    val linkId: String,
    val sourceItemId: String,
    val sourceKind: String,
    val sourceName: String,
    val sourcePageId: String,
    val pageNumber: Int,
    val wire: String,
    val startKey: Int,
    val endKey: Int,
) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(linkId)
        dest.writeString(sourceItemId)
        dest.writeString(sourceKind)
        dest.writeString(sourceName)
        dest.writeString(sourcePageId)
        dest.writeInt(pageNumber)
        dest.writeString(wire)
        dest.writeInt(startKey)
        dest.writeInt(endKey)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SeamBibleBacklink> = object : Parcelable.Creator<SeamBibleBacklink> {
            override fun createFromParcel(source: Parcel): SeamBibleBacklink = SeamBibleBacklink(
                linkId = requireNotNull(source.readString()) { "null linkId" },
                sourceItemId = requireNotNull(source.readString()) { "null sourceItemId" },
                sourceKind = requireNotNull(source.readString()) { "null sourceKind" },
                sourceName = requireNotNull(source.readString()) { "null sourceName" },
                sourcePageId = requireNotNull(source.readString()) { "null sourcePageId" },
                pageNumber = source.readInt(),
                wire = requireNotNull(source.readString()) { "null wire" },
                startKey = source.readInt(),
                endKey = source.readInt(),
            )

            override fun newArray(size: Int): Array<SeamBibleBacklink?> = arrayOfNulls(size)
        }
    }
}
