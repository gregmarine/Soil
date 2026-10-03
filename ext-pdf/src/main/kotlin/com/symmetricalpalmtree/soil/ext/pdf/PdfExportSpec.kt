package com.symmetricalpalmtree.soil.ext.pdf

import com.symmetricalpalmtree.soil.ext.ExportContract

/** The spec, checked by construction: only our options, and a secret exactly when asked. */
internal object PdfExportSpec {

    val SUPPORTED_OPTIONS: Set<String> = setOf(ExportContract.OPTION_PAGE_TEMPLATE, ExportContract.OPTION_PROTECT)

    fun require(values: Map<String, String>, exportSecret: String?) {
        val unknown = values.keys.filter { it !in SUPPORTED_OPTIONS }.sorted()
        require(unknown.isEmpty()) { "options not offered by this exporter: ${unknown.joinToString(", ")}" }
        if (values[ExportContract.OPTION_PROTECT] == "1") {
            require(exportSecret != null) { "password-protect is armed but no export secret arrived" }
        } else {
            require(exportSecret == null) { "an export secret arrived that nothing asked for" }
        }
    }
}
