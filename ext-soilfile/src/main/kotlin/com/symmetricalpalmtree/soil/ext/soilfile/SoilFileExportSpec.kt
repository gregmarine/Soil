package com.symmetricalpalmtree.soil.ext.soilfile

import com.symmetricalpalmtree.soil.ext.ExportContract

/** The one option this exporter offers, checked by construction: an absent keying is Keep. */
object SoilFileExportSpec {

    val SUPPORTED_KEYING: Set<String> = setOf(ExportContract.KEYING_KEEP, ExportContract.KEYING_REKEY, ExportContract.KEYING_PLAIN)

    fun keying(values: Map<String, String>): String {
        val asked = values[ExportContract.OPTION_KEYING] ?: return ExportContract.KEYING_KEEP
        require(asked in SUPPORTED_KEYING) { "keying '$asked' is not offered by this exporter" }
        return asked
    }
}
