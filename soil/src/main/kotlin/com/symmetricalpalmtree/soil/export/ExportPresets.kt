package com.symmetricalpalmtree.soil.export

/** The pure half of presets: which are listed for the exporters in front, and what one captures. */
object ExportPresets {

    data class State(val exporter: String, val values: Map<String, String>)

    class Row(val id: String, val name: String, val preset: ExportPreset)

    fun listable(rows: List<Row>, listedPackages: Set<String>): List<Row> = rows.filter { it.preset.exporter in listedPackages }

    fun capture(state: State): ExportPreset = ExportPreset(exporter = state.exporter, values = LinkedHashMap(state.values))

    fun apply(preset: ExportPreset): State = State(preset.exporter, LinkedHashMap(preset.values))
}
