package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/**
 * One link to a day of the calendar, as the library's link index holds it: which item and page
 * it was made on (the page's number too, 0 for an item with no pages) and the day it names. Only
 * links from items that are alive are answered. Never logged: a name is the person's own.
 *
 * Wire form: `String linkId · String sourceItemId · String sourceKind · String sourceName ·
 * String sourcePageId · int pageNumber · String date`.
 */
data class SeamCalBacklink(
    val linkId: String,
    val sourceItemId: String,
    val sourceKind: String,
    val sourceName: String,
    val sourcePageId: String,
    val pageNumber: Int,
    val date: String,
) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(linkId)
        dest.writeString(sourceItemId)
        dest.writeString(sourceKind)
        dest.writeString(sourceName)
        dest.writeString(sourcePageId)
        dest.writeInt(pageNumber)
        dest.writeString(date)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SeamCalBacklink> = object : Parcelable.Creator<SeamCalBacklink> {
            override fun createFromParcel(source: Parcel): SeamCalBacklink = SeamCalBacklink(
                linkId = requireNotNull(source.readString()) { "null linkId" },
                sourceItemId = requireNotNull(source.readString()) { "null sourceItemId" },
                sourceKind = requireNotNull(source.readString()) { "null sourceKind" },
                sourceName = requireNotNull(source.readString()) { "null sourceName" },
                sourcePageId = requireNotNull(source.readString()) { "null sourcePageId" },
                pageNumber = source.readInt(),
                date = requireNotNull(source.readString()) { "null date" },
            )

            override fun newArray(size: Int): Array<SeamCalBacklink?> = arrayOfNulls(size)
        }
    }
}
