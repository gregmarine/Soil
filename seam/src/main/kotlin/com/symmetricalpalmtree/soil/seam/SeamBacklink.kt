package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/**
 * One link into an item, as the library's link index holds it: which item and page it was made
 * on, and which page of the target it points at — null for a link to the whole item. Only links
 * from items that are alive are answered.
 *
 * Wire form: `String linkId · String sourceItemId · String sourceKind · String sourceName ·
 * String sourcePageId · String? targetPageId`.
 */
data class SeamBacklink(
    val linkId: String,
    val sourceItemId: String,
    val sourceKind: String,
    val sourceName: String,
    val sourcePageId: String,
    val targetPageId: String?,
) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(linkId)
        dest.writeString(sourceItemId)
        dest.writeString(sourceKind)
        dest.writeString(sourceName)
        dest.writeString(sourcePageId)
        dest.writeString(targetPageId)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SeamBacklink> = object : Parcelable.Creator<SeamBacklink> {
            override fun createFromParcel(source: Parcel): SeamBacklink = SeamBacklink(
                linkId = requireNotNull(source.readString()) { "null linkId" },
                sourceItemId = requireNotNull(source.readString()) { "null sourceItemId" },
                sourceKind = requireNotNull(source.readString()) { "null sourceKind" },
                sourceName = requireNotNull(source.readString()) { "null sourceName" },
                sourcePageId = requireNotNull(source.readString()) { "null sourcePageId" },
                targetPageId = source.readString(),
            )

            override fun newArray(size: Int): Array<SeamBacklink?> = arrayOfNulls(size)
        }
    }
}
