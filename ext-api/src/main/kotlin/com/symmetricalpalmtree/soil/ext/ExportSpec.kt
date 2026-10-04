package com.symmetricalpalmtree.soil.ext

import android.os.Parcel
import android.os.Parcelable

/**
 * What Soil sends into `IExporter.export()`: the chosen option [values] (option id → a choice id
 * or a toggle `"0"`/`"1"`, never free text) and the item's display name, for formats that carry
 * a title. No id, no path, no key. A passphrase-kind option, the keying option included, has no
 * entry: Soil consumed the secret itself.
 *
 * [exportSecret] is the one deliberate exception, and not to the rule's point: a password typed
 * for the output file (a PDF password), export-scoped, opening nothing of Soil's. Never logged,
 * never saved, never put in an Intent; the extension holds it only for its protect step and
 * clears its copy in `finally`. `null` means no secret.
 *
 * Wire form: `int n · n × (String key · String value) · String itemName · String? exportSecret`.
 */
class ExportSpec(
    val values: Map<String, String>,
    val itemName: String,
    val exportSecret: String? = null,
) : Parcelable {

    init {
        ExportContract.requireValues(values)
        ExportContract.requireDisplayName(itemName, "item name")
        exportSecret?.let {
            require(it.isNotEmpty() && it.length <= ExportContract.MAX_EXPORT_SECRET_CHARS) { "export secret empty or over ${ExportContract.MAX_EXPORT_SECRET_CHARS} chars" }
        }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(values.size)
        for ((key, value) in values) {
            dest.writeString(key)
            dest.writeString(value)
        }
        dest.writeString(itemName)
        dest.writeString(exportSecret)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): ExportSpec {
            val n = parcel.readInt()
            require(n in 0..ExportContract.MAX_OPTIONS) { "$n spec entries outside 0..${ExportContract.MAX_OPTIONS}" }
            val values = LinkedHashMap<String, String>(n)
            repeat(n) {
                val key = parcel.readString() ?: ""
                val value = parcel.readString() ?: ""
                require(key !in values) { "duplicate spec key '$key'" }
                values[key] = value
            }
            val itemName = parcel.readString() ?: ""
            val exportSecret = if (parcel.dataAvail() > 0) parcel.readString() else null
            return ExportSpec(values, itemName, exportSecret)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<ExportSpec> = object : Parcelable.Creator<ExportSpec> {
            override fun createFromParcel(parcel: Parcel): ExportSpec = read(parcel)
            override fun newArray(size: Int): Array<ExportSpec?> = arrayOfNulls(size)
        }
    }
}
