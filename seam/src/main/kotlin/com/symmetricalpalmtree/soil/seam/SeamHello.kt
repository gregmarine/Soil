package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/**
 * Soil's answer to `hello()`.
 *
 * @property seamVersion [Seam.VERSION] of the Soil that answered. An app built for a later seam
 *   than the Soil it finds knows not to ask for what is not there.
 * @property libraryUnlocked whether Soil holds the key right now. When it does not, nothing can
 *   be read or written until the person unlocks the library **in Soil**: an app never asks for a
 *   key and never shows a prompt for one.
 * @property libraryOpen whether rows may be read and written right now: the library is unlocked,
 *   the recovery key has been saved, and no passphrase change stands unfinished. Every storage
 *   call is refused until it is.
 */
data class SeamHello(
    val seamVersion: Int,
    val libraryUnlocked: Boolean,
    val libraryOpen: Boolean,
) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(seamVersion)
        dest.writeInt(if (libraryUnlocked) 1 else 0)
        dest.writeInt(if (libraryOpen) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SeamHello> = object : Parcelable.Creator<SeamHello> {
            override fun createFromParcel(source: Parcel): SeamHello =
                SeamHello(
                    seamVersion = source.readInt(),
                    libraryUnlocked = source.readInt() != 0,
                    libraryOpen = source.readInt() != 0,
                )

            override fun newArray(size: Int): Array<SeamHello?> = arrayOfNulls(size)
        }
    }
}
