package com.symmetricalpalmtree.soil.seam

import android.os.Parcel
import android.os.Parcelable

/**
 * One format an app writes itself: the bytes of an exported file, finished, that Soil only has
 * to put where the person asked. [paged] says the format is laid out on pages, so it takes the
 * page size chosen on the export screen.
 *
 * Wire form: `String id · String label · String fileExtension · String mimeType · int paged`.
 */
class SeamFormat(val id: String, val label: String, val fileExtension: String, val mimeType: String, val paged: Boolean) : Parcelable {

    init {
        require(id.isNotEmpty() && id.length <= MAX_ID_CHARS && id.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }) { "a format id is [a-z0-9_]{1..$MAX_ID_CHARS}" }
        require(label.isNotBlank() && label.length <= MAX_LABEL_CHARS) { "a format label is 1..$MAX_LABEL_CHARS characters" }
        require(fileExtension.isNotEmpty() && fileExtension.length <= 12 && fileExtension.all { it in 'a'..'z' || it in '0'..'9' }) { "a file extension is [a-z0-9]{1..12}" }
        require(mimeType.length in 3..128 && mimeType.count { it == '/' } == 1 && !mimeType.startsWith('/') && !mimeType.endsWith('/')) { "malformed MIME type" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(label)
        dest.writeString(fileExtension)
        dest.writeString(mimeType)
        dest.writeInt(if (paged) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val MAX_ID_CHARS = 32
        const val MAX_LABEL_CHARS = 80

        @JvmField
        val CREATOR: Parcelable.Creator<SeamFormat> = object : Parcelable.Creator<SeamFormat> {
            override fun createFromParcel(parcel: Parcel): SeamFormat =
                SeamFormat(parcel.readString().orEmpty(), parcel.readString().orEmpty(), parcel.readString().orEmpty(), parcel.readString().orEmpty(), parcel.readInt() != 0)
            override fun newArray(size: Int): Array<SeamFormat?> = arrayOfNulls(size)
        }
    }
}

/**
 * What an app's renderer says of its kind: whether an item **flows** (it has no pages of its
 * own, so it is laid out at a page size the person chooses, and has no paper to put under it),
 * the [formats] the app writes itself beyond its pages, and the files it can take in as a new
 * item: [importLabel] names what they become, [importExtensions] and [importMimeTypes] say
 * which. An app that takes nothing in has no extensions.
 *
 * Wire form: `int flowing · SeamFormat[] formats · String importLabel · String[]
 * importExtensions · String[] importMimeTypes`. A reader stops where the bytes do, so one
 * written before the import fields reads as taking nothing in.
 */
class SeamRenderInfo(
    val flowing: Boolean,
    val formats: List<SeamFormat>,
    val importLabel: String = "",
    val importExtensions: List<String> = emptyList(),
    val importMimeTypes: List<String> = emptyList(),
) : Parcelable {

    init {
        require(formats.size <= MAX_FORMATS) { "${formats.size} formats > $MAX_FORMATS" }
        require(formats.map { it.id }.toSet().size == formats.size) { "duplicate format ids" }
        require(importLabel.length <= SeamFormat.MAX_LABEL_CHARS) { "an import label is at most ${SeamFormat.MAX_LABEL_CHARS} characters" }
        require(importExtensions.size <= MAX_FORMATS && importMimeTypes.size <= MAX_FORMATS) { "too many import types" }
        require(importExtensions.all { e -> e.isNotEmpty() && e.length <= 12 && e.all { it in 'a'..'z' || it in '0'..'9' } }) { "a file extension is [a-z0-9]{1..12}" }
        require(importExtensions.isEmpty() || (importLabel.isNotBlank() && importMimeTypes.isNotEmpty())) { "an app that takes files in names them and their types" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(if (flowing) 1 else 0)
        dest.writeTypedList(formats)
        dest.writeString(importLabel)
        dest.writeStringList(importExtensions)
        dest.writeStringList(importMimeTypes)
    }

    override fun describeContents(): Int = 0

    companion object {
        const val MAX_FORMATS = 8

        /** An app whose items are pages and nothing else: what an app that says nothing is taken to be. */
        val PAGES_ONLY = SeamRenderInfo(false, emptyList())

        @JvmField
        val CREATOR: Parcelable.Creator<SeamRenderInfo> = object : Parcelable.Creator<SeamRenderInfo> {
            override fun createFromParcel(parcel: Parcel): SeamRenderInfo {
                val flowing = parcel.readInt() != 0
                val formats = parcel.createTypedArrayList(SeamFormat.CREATOR) ?: arrayListOf()
                if (parcel.dataAvail() <= 0) return SeamRenderInfo(flowing, formats)
                return SeamRenderInfo(flowing, formats, parcel.readString().orEmpty(), parcel.createStringArrayList() ?: arrayListOf(), parcel.createStringArrayList() ?: arrayListOf())
            }
            override fun newArray(size: Int): Array<SeamRenderInfo?> = arrayOfNulls(size)
        }
    }
}
