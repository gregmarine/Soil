package com.symmetricalpalmtree.soil.ext

import android.os.Parcel
import android.os.Parcelable

/**
 * One stroke of bare geometry for a recogniser: parallel [x] and [y] arrays in the caller's px
 * space, and nothing else. Both arrays are the same non-zero length; a malformed one is refused
 * at unmarshal.
 */
class InkStroke(val x: FloatArray, val y: FloatArray) : Parcelable {

    init {
        require(x.size == y.size) { "x/y length mismatch (${x.size} vs ${y.size})" }
        require(x.isNotEmpty()) { "empty stroke" }
    }

    val size: Int get() = x.size

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(x.size)
        dest.writeFloatArray(x)
        dest.writeFloatArray(y)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<InkStroke> = object : Parcelable.Creator<InkStroke> {
            override fun createFromParcel(parcel: Parcel): InkStroke {
                parcel.readInt()
                val x = parcel.createFloatArray() ?: FloatArray(0)
                val y = parcel.createFloatArray() ?: FloatArray(0)
                return InkStroke(x, y)
            }

            override fun newArray(size: Int): Array<InkStroke?> = arrayOfNulls(size)
        }
    }
}
