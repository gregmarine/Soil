package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/**
 * One template of the paper library as an app sees it: a picture, with the fit it is laid onto a
 * page with. Its bytes are asked for separately. Wire form: `String id · String name · int fit`.
 */
data class SeamTemplate(val id: String, val name: String, val fit: Int) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(name)
        dest.writeInt(fit)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SeamTemplate> = object : Parcelable.Creator<SeamTemplate> {
            override fun createFromParcel(source: Parcel): SeamTemplate = SeamTemplate(
                id = requireNotNull(source.readString()) { "null id" },
                name = requireNotNull(source.readString()) { "null name" },
                fit = source.readInt(),
            )

            override fun newArray(size: Int): Array<SeamTemplate?> = arrayOfNulls(size)
        }
    }
}
