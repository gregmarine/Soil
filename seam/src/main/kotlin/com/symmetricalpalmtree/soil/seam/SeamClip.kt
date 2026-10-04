package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/**
 * What is on the clipboard, without the payload: the app's own kind of payload (a page, a
 * selection), the item it was copied from, and when. Soil stores these beside the bytes and
 * answers them blob-free, so an app can decide what a sheet offers without reading megabytes.
 */
class SeamClip(
    val payloadKind: String,
    val sourceItemId: String,
    val copiedAt: Long,
) : Parcelable {

    init {
        require(payloadKind.isNotBlank() && payloadKind.length <= MAX_KIND_CHARS) { "not a payload kind" }
        require(sourceItemId.length <= MAX_ID_CHARS) { "not an item id" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(payloadKind)
        dest.writeString(sourceItemId)
        dest.writeLong(copiedAt)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val MAX_KIND_CHARS = 32
        const val MAX_ID_CHARS = 64

        @JvmField
        val CREATOR: Parcelable.Creator<SeamClip> = object : Parcelable.Creator<SeamClip> {
            override fun createFromParcel(parcel: Parcel): SeamClip = SeamClip(
                payloadKind = parcel.readString().orEmpty(),
                sourceItemId = parcel.readString().orEmpty(),
                copiedAt = parcel.readLong(),
            )

            override fun newArray(size: Int): Array<SeamClip?> = arrayOfNulls(size)
        }
    }
}
