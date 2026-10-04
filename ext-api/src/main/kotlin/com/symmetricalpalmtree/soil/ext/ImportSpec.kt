package com.symmetricalpalmtree.soil.ext

import android.os.Parcel
import android.os.Parcelable

/**
 * What Soil sends into `IImporter.importDocument()`: a bounded option map (empty today; it
 * crosses now because an AIDL method cannot grow parameters later) and the picked document's
 * display name, which is display only: never a path, an id or a secret.
 *
 * Wire form: `int n · n × (String key · String value) · String displayName`.
 */
class ImportSpec(
    val values: Map<String, String>,
    val displayName: String,
) : Parcelable {

    init {
        ExportContract.requireValues(values)
        ExportContract.requireDisplayName(displayName, "display name")
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(values.size)
        for ((key, value) in values) {
            dest.writeString(key)
            dest.writeString(value)
        }
        dest.writeString(displayName)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): ImportSpec {
            val n = parcel.readInt()
            require(n in 0..ExportContract.MAX_OPTIONS) { "$n spec entries outside 0..${ExportContract.MAX_OPTIONS}" }
            val values = LinkedHashMap<String, String>(n)
            repeat(n) {
                val key = parcel.readString() ?: ""
                val value = parcel.readString() ?: ""
                require(key !in values) { "duplicate spec key '$key'" }
                values[key] = value
            }
            return ImportSpec(values, parcel.readString() ?: "")
        }

        @JvmField
        val CREATOR: Parcelable.Creator<ImportSpec> = object : Parcelable.Creator<ImportSpec> {
            override fun createFromParcel(parcel: Parcel): ImportSpec = read(parcel)
            override fun newArray(size: Int): Array<ImportSpec?> = arrayOfNulls(size)
        }
    }
}
