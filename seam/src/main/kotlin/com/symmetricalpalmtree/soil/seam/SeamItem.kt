package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/**
 * One item of the library as the index describes it: a notebook, a sketchbook or a document.
 * Nothing of what the item holds is here.
 *
 * Wire form: `String id · String kind · String name · long createdAt · long updatedAt ·
 * int pageCount`.
 */
data class SeamItem(
    val id: String,
    val kind: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int = 0,
) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(kind)
        dest.writeString(name)
        dest.writeLong(createdAt)
        dest.writeLong(updatedAt)
        dest.writeInt(pageCount)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SeamItem> = object : Parcelable.Creator<SeamItem> {
            override fun createFromParcel(source: Parcel): SeamItem = SeamItem(
                id = requireNotNull(source.readString()) { "null id" },
                kind = requireNotNull(source.readString()) { "null kind" },
                name = requireNotNull(source.readString()) { "null name" },
                createdAt = source.readLong(),
                updatedAt = source.readLong(),
                pageCount = source.readInt(),
            )

            override fun newArray(size: Int): Array<SeamItem?> = arrayOfNulls(size)
        }
    }
}
