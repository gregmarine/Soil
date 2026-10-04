package com.symmetricalpalmtree.soil.ext

import android.os.Parcel
import android.os.Parcelable

/**
 * What one importer accepts, the reply of `IImporter.describe()`: a label, the file extensions
 * Soil matches a picked document's name against, and the MIME types that seed the picker's
 * filter. The constructor `require`s are the validation.
 *
 * Wire form: `String formatLabel · String[] fileExtensions · String[] mimeTypes`. A later version
 * may append a tail; readers of this version stop after the MIME list.
 */
class ImporterInfo(
    val formatLabel: String,
    val fileExtensions: List<String>,
    val mimeTypes: List<String>,
) : Parcelable {

    init {
        ExportContract.requireLabel(formatLabel, "format label")
        require(fileExtensions.size in 1..ExportContract.MAX_FILE_EXTENSIONS) { "${fileExtensions.size} file extensions outside 1..${ExportContract.MAX_FILE_EXTENSIONS}" }
        fileExtensions.forEach { ExportContract.requireExtension(it) }
        require(fileExtensions.toSet().size == fileExtensions.size) { "duplicate file extensions" }
        require(mimeTypes.size in 1..ExportContract.MAX_MIME_TYPES) { "${mimeTypes.size} MIME types outside 1..${ExportContract.MAX_MIME_TYPES}" }
        mimeTypes.forEach { ExportContract.requireMime(it) }
        require(mimeTypes.toSet().size == mimeTypes.size) { "duplicate MIME types" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(formatLabel)
        dest.writeStringList(fileExtensions)
        dest.writeStringList(mimeTypes)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): ImporterInfo = ImporterInfo(
            parcel.readString() ?: "",
            parcel.createStringArrayList() ?: arrayListOf(),
            parcel.createStringArrayList() ?: arrayListOf(),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<ImporterInfo> = object : Parcelable.Creator<ImporterInfo> {
            override fun createFromParcel(parcel: Parcel): ImporterInfo = read(parcel)
            override fun newArray(size: Int): Array<ImporterInfo?> = arrayOfNulls(size)
        }
    }
}
