package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.ExportContract
import com.symmetricalpalmtree.soil.ext.ExporterInfo
import com.symmetricalpalmtree.soil.ext.OptionDescriptor

/** What the export screen makes of an exporter's options. Pure. */
object ExportOptions {

    /** Whether this build can draw the descriptor and act on every reserved option it declares. */
    fun isRenderable(info: ExporterInfo): Boolean {
        if (info.options.any { it.kind == ExportContract.KIND_PASSPHRASE }) return false
        for (d in info.options) {
            val allowed = when (d.id) {
                ExportContract.OPTION_KEYING -> ExportContract.SOURCE_FILE
                ExportContract.OPTION_PAGE_TEMPLATE, ExportContract.OPTION_IMAGE_FORMAT -> ExportContract.SOURCE_PAGES
                else -> continue
            }
            if (info.sourceKind != allowed) return false
        }
        val format = info.options.firstOrNull { it.id == ExportContract.OPTION_IMAGE_FORMAT }
        if (format != null && (format.kind != ExportContract.KIND_SINGLE_CHOICE || !format.choiceIds.all { ExportContract.imageExtension(it) != null })) return false
        val keying = info.options.firstOrNull { it.id == ExportContract.OPTION_KEYING && it.kind == ExportContract.KIND_SINGLE_CHOICE } ?: return true
        val known = keying.choiceIds.all { it == ExportContract.KEYING_KEEP || it == ExportContract.KEYING_REKEY || it == ExportContract.KEYING_PLAIN }
        if (!known) return false
        val protect = info.options.any { it.id == ExportContract.OPTION_PROTECT && it.kind == ExportContract.KIND_TOGGLE }
        // One field block: an exporter cannot ask for a passphrase and a password both.
        return !(protect && ExportContract.KEYING_REKEY in keying.choiceIds)
    }

    /** Every option's value, the chosen one when it is a legal value, else the default. */
    fun specValues(info: ExporterInfo, chosen: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(info.options.size)
        for (d in info.options) {
            val value = when (d.kind) {
                ExportContract.KIND_SINGLE_CHOICE -> chosen[d.id]?.takeIf { it in d.choiceIds } ?: d.defaultValue
                ExportContract.KIND_TOGGLE -> chosen[d.id]?.takeIf { it == "0" || it == "1" } ?: d.defaultValue
                else -> null
            }
            if (value != null) out[d.id] = value
        }
        return out
    }

    fun isFixed(d: OptionDescriptor): Boolean = d.kind == ExportContract.KIND_SINGLE_CHOICE && d.choiceIds.size == 1

    fun choiceLabel(d: OptionDescriptor, value: String): String {
        val i = d.choiceIds.indexOf(value)
        return if (i >= 0) d.choiceLabels[i] else value
    }

    private fun choice(info: ExporterInfo, chosen: Map<String, String>, id: String): String? {
        val d = info.options.firstOrNull { it.id == id && it.kind == ExportContract.KIND_SINGLE_CHOICE } ?: return null
        return chosen[d.id]?.takeIf { it in d.choiceIds } ?: d.defaultValue
    }

    private fun toggle(info: ExporterInfo, chosen: Map<String, String>, id: String): String? {
        val d = info.options.firstOrNull { it.id == id && it.kind == ExportContract.KIND_TOGGLE } ?: return null
        return chosen[d.id]?.takeIf { it == "0" || it == "1" } ?: d.defaultValue
    }

    fun keying(info: ExporterInfo, chosen: Map<String, String>): String? = choice(info, chosen, ExportContract.OPTION_KEYING)
    fun needsPassphrase(info: ExporterInfo, chosen: Map<String, String>): Boolean = keying(info, chosen) == ExportContract.KEYING_REKEY
    fun showsPlainWarning(info: ExporterInfo, chosen: Map<String, String>): Boolean = keying(info, chosen) == ExportContract.KEYING_PLAIN
    fun wantsExportSecret(info: ExporterInfo, chosen: Map<String, String>): Boolean = toggle(info, chosen, ExportContract.OPTION_PROTECT) == "1"
    fun includeTemplate(info: ExporterInfo, chosen: Map<String, String>): Boolean = (toggle(info, chosen, ExportContract.OPTION_PAGE_TEMPLATE) ?: "1") == "1"

    /** The file extension the export is named with: the image format's when one is chosen. */
    fun fileExtension(info: ExporterInfo, chosen: Map<String, String>): String =
        choice(info, chosen, ExportContract.OPTION_IMAGE_FORMAT)?.let { ExportContract.imageExtension(it) } ?: info.fileExtension

    fun mimeType(info: ExporterInfo, chosen: Map<String, String>): String =
        choice(info, chosen, ExportContract.OPTION_IMAGE_FORMAT)?.let { ExportContract.imageMime(it) } ?: info.mimeType
}
