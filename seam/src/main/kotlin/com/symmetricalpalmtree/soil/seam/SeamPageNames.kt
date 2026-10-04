package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/**
 * Pages as an app names them: parallel lists of ids, 1-based numbers and titles ("" for none).
 * From `pages` the ids are the item's; from `render` the ids are "" and the lists run over the
 * bundle's pages, endnotes included.
 */
class SeamPageNames(val ids: List<String>, val numbers: List<Int>, val titles: List<String>) : Parcelable {

    init {
        require(ids.size == numbers.size && numbers.size == titles.size) { "page name lists disagree" }
        require(ids.size <= MAX_PAGES) { "${ids.size} pages > $MAX_PAGES" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeStringList(ids)
        dest.writeIntArray(numbers.toIntArray())
        dest.writeStringList(titles)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val MAX_PAGES = 4096

        @JvmField
        val CREATOR: Parcelable.Creator<SeamPageNames> = object : Parcelable.Creator<SeamPageNames> {
            override fun createFromParcel(parcel: Parcel): SeamPageNames = SeamPageNames(
                parcel.createStringArrayList() ?: arrayListOf(),
                (parcel.createIntArray() ?: IntArray(0)).toList(),
                parcel.createStringArrayList() ?: arrayListOf(),
            )
            override fun newArray(size: Int): Array<SeamPageNames?> = arrayOfNulls(size)
        }
    }
}
