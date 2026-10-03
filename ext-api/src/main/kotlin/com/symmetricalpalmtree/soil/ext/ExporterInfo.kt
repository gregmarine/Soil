package com.symmetricalpalmtree.soil.ext

import android.os.Parcel
import android.os.Parcelable

/**
 * What one exporter offers, the reply of `IExporter.describe()`: the format (label, file
 * extension, MIME), the options Soil draws with its own widgets, the source kind it needs, the
 * highest [PageBundle] version it reads, and how it delivers. The constructor `require`s are the
 * validation: a descriptor that fails them drops the exporter with a log line, never a crash.
 *
 * Wire form: `String formatLabel · String fileExtension · String mimeType · OptionDescriptor[] ·
 * int sourceKind · int bundleVersion · int delivery`. A later version may append a tail; readers
 * of this version stop after `delivery`, and the descriptor must stay the reply's trailing
 * payload for a tail to be read by `dataAvail()`.
 */
class ExporterInfo(
    val formatLabel: String,
    val fileExtension: String,
    val mimeType: String,
    val options: List<OptionDescriptor>,
    val sourceKind: Int = ExportContract.SOURCE_FILE,
    val bundleVersion: Int = PageBundle.VERSION_1,
    val delivery: Int = ExportContract.DELIVERY_ONE_FILE,
) : Parcelable {

    init {
        require(bundleVersion >= PageBundle.VERSION_1) { "bundle version $bundleVersion < 1" }
        require(delivery == ExportContract.DELIVERY_ONE_FILE || delivery == ExportContract.DELIVERY_PER_PAGE) { "unknown delivery $delivery" }
        require(delivery == ExportContract.DELIVERY_ONE_FILE || sourceKind == ExportContract.SOURCE_PAGES) { "per-page delivery needs the pages source kind" }
        require(sourceKind == ExportContract.SOURCE_FILE || sourceKind == ExportContract.SOURCE_PAGES) { "unknown source kind $sourceKind" }
        ExportContract.requireLabel(formatLabel, "format label")
        ExportContract.requireExtension(fileExtension)
        ExportContract.requireMime(mimeType)
        require(options.size <= ExportContract.MAX_OPTIONS) { "${options.size} options > ${ExportContract.MAX_OPTIONS}" }
        require(options.map { it.id }.toSet().size == options.size) { "duplicate option ids" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(formatLabel)
        dest.writeString(fileExtension)
        dest.writeString(mimeType)
        dest.writeTypedList(options)
        dest.writeInt(sourceKind)
        dest.writeInt(bundleVersion)
        dest.writeInt(delivery)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): ExporterInfo {
            val formatLabel = parcel.readString() ?: ""
            val fileExtension = parcel.readString() ?: ""
            val mimeType = parcel.readString() ?: ""
            val options = parcel.createTypedArrayList(OptionDescriptor.CREATOR) ?: arrayListOf()
            val sourceKind = if (parcel.dataAvail() > 0) parcel.readInt() else ExportContract.SOURCE_FILE
            val bundleVersion = if (parcel.dataAvail() > 0) parcel.readInt() else PageBundle.VERSION_1
            val delivery = if (parcel.dataAvail() > 0) parcel.readInt() else ExportContract.DELIVERY_ONE_FILE
            return ExporterInfo(formatLabel, fileExtension, mimeType, options, sourceKind, bundleVersion, delivery)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<ExporterInfo> = object : Parcelable.Creator<ExporterInfo> {
            override fun createFromParcel(parcel: Parcel): ExporterInfo = read(parcel)
            override fun newArray(size: Int): Array<ExporterInfo?> = arrayOfNulls(size)
        }
    }
}
