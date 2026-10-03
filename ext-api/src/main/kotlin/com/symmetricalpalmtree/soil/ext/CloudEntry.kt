package com.symmetricalpalmtree.soil.ext

import android.os.Parcel
import android.os.Parcelable

/** One folder or file as the provider lists it. Validated on construction, so a malformed
 *  answer fails at unmarshal and never reaches a screen. */
class CloudEntry(val id: String, val name: String, val isFolder: Boolean, val sizeBytes: Long, val modifiedAt: Long) : Parcelable {

    init {
        require(CloudContract.isEntryId(id)) { "entry id is not an id" }
        require(CloudContract.isName(name)) { "entry name is not a name" }
        require(sizeBytes >= 0) { "size is negative ($sizeBytes)" }
        require(!isFolder || sizeBytes == 0L) { "a folder has no size" }
        require(modifiedAt >= 0) { "modifiedAt is negative ($modifiedAt)" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(name)
        dest.writeInt(if (isFolder) 1 else 0)
        dest.writeLong(sizeBytes)
        dest.writeLong(modifiedAt)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is CloudEntry && other.id == id && other.name == name && other.isFolder == isFolder && other.sizeBytes == sizeBytes && other.modifiedAt == modifiedAt

    override fun hashCode(): Int = (((id.hashCode() * 31 + name.hashCode()) * 31 + (if (isFolder) 1 else 0)) * 31 + sizeBytes.hashCode()) * 31 + modifiedAt.hashCode()

    override fun toString(): String = "CloudEntry(${if (isFolder) "folder" else "file"}, name=${name.length} chars, $sizeBytes B)"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<CloudEntry> = object : Parcelable.Creator<CloudEntry> {
            override fun createFromParcel(parcel: Parcel): CloudEntry =
                CloudEntry(parcel.readString() ?: "", parcel.readString() ?: "", parcel.readInt() != 0, parcel.readLong(), parcel.readLong())
            override fun newArray(size: Int): Array<CloudEntry?> = arrayOfNulls(size)
        }
    }
}
