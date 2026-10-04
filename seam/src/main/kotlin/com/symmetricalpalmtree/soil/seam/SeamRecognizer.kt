package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/** The recogniser Soil relays to: its label, as the app may show it, and the language it is asked in. */
class SeamRecognizer(val label: String, val languageTag: String) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(label)
        dest.writeString(languageTag)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** `recognizerStatus`: the model is on the device and the engine built. */
        const val STATUS_READY = 0

        /** The model is not on the device: ask the person, then `prepareRecognizer`. */
        const val STATUS_NEEDS_DOWNLOAD = 1

        /** The download is in flight. */
        const val STATUS_DOWNLOADING = 2

        /** The engine cannot run here. */
        const val STATUS_UNAVAILABLE = 3

        @JvmField
        val CREATOR: Parcelable.Creator<SeamRecognizer> = object : Parcelable.Creator<SeamRecognizer> {
            override fun createFromParcel(parcel: Parcel): SeamRecognizer = SeamRecognizer(parcel.readString().orEmpty(), parcel.readString().orEmpty())
            override fun newArray(size: Int): Array<SeamRecognizer?> = arrayOfNulls(size)
        }
    }
}
