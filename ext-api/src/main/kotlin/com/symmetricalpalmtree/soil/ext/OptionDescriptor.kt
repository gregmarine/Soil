package com.symmetricalpalmtree.soil.ext

import android.os.Parcel
import android.os.Parcelable

/**
 * One option an exporter offers, drawn by Soil's own widgets so every exporter's panel is e-ink
 * chrome. The constructor `require`s are the validation.
 *
 * Kinds: single-choice ([choiceIds] and [choiceLabels] parallel and non-empty, [defaultValue] one
 * of the ids); toggle (no choices, default `"0"` or `"1"`); passphrase (no choices, default `""`,
 * the typed secret collected by Soil and never crossing).
 *
 * Wire form: `String id · String label · int kind · String[] choiceIds · String[] choiceLabels ·
 * String defaultValue`.
 */
class OptionDescriptor(
    val id: String,
    val label: String,
    val kind: Int,
    val choiceIds: List<String>,
    val choiceLabels: List<String>,
    val defaultValue: String,
) : Parcelable {

    init {
        ExportContract.requireId(id, "option id")
        ExportContract.requireLabel(label, "option label")
        require(kind in ExportContract.KIND_SINGLE_CHOICE..ExportContract.KIND_PASSPHRASE) { "unknown option kind $kind" }
        when (kind) {
            ExportContract.KIND_SINGLE_CHOICE -> {
                require(choiceIds.isNotEmpty()) { "single-choice option '$id' has no choices" }
                require(choiceIds.size <= ExportContract.MAX_CHOICES) { "option '$id': ${choiceIds.size} choices > ${ExportContract.MAX_CHOICES}" }
                require(choiceIds.size == choiceLabels.size) { "option '$id': ${choiceIds.size} choice ids vs ${choiceLabels.size} labels" }
                require(choiceIds.toSet().size == choiceIds.size) { "option '$id': duplicate choice ids" }
                choiceIds.forEach { ExportContract.requireId(it, "choice id of '$id'") }
                choiceLabels.forEach { ExportContract.requireLabel(it, "choice label of '$id'") }
                require(defaultValue in choiceIds) { "option '$id': default '$defaultValue' is not a declared choice" }
            }
            ExportContract.KIND_TOGGLE -> {
                require(choiceIds.isEmpty() && choiceLabels.isEmpty()) { "toggle option '$id' declares choices" }
                require(defaultValue == "0" || defaultValue == "1") { "toggle option '$id': default must be \"0\" or \"1\"" }
            }
            ExportContract.KIND_PASSPHRASE -> {
                require(choiceIds.isEmpty() && choiceLabels.isEmpty()) { "passphrase option '$id' declares choices" }
                require(defaultValue.isEmpty()) { "passphrase option '$id': default must be empty" }
            }
        }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(label)
        dest.writeInt(kind)
        dest.writeStringList(choiceIds)
        dest.writeStringList(choiceLabels)
        dest.writeString(defaultValue)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** A toggle, the common shape. */
        fun toggle(id: String, label: String, on: Boolean): OptionDescriptor =
            OptionDescriptor(id, label, ExportContract.KIND_TOGGLE, emptyList(), emptyList(), if (on) "1" else "0")

        /** A single choice, the other common shape. */
        fun choice(id: String, label: String, ids: List<String>, labels: List<String>, default: String): OptionDescriptor =
            OptionDescriptor(id, label, ExportContract.KIND_SINGLE_CHOICE, ids, labels, default)

        private fun read(parcel: Parcel): OptionDescriptor {
            val id = parcel.readString() ?: ""
            val label = parcel.readString() ?: ""
            val kind = parcel.readInt()
            val choiceIds = parcel.createStringArrayList() ?: arrayListOf()
            val choiceLabels = parcel.createStringArrayList() ?: arrayListOf()
            val defaultValue = parcel.readString() ?: ""
            return OptionDescriptor(id, label, kind, choiceIds, choiceLabels, defaultValue)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<OptionDescriptor> = object : Parcelable.Creator<OptionDescriptor> {
            override fun createFromParcel(parcel: Parcel): OptionDescriptor = read(parcel)
            override fun newArray(size: Int): Array<OptionDescriptor?> = arrayOfNulls(size)
        }
    }
}
