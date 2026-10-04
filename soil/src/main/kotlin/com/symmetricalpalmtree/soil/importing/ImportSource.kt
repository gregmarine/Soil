package com.symmetricalpalmtree.soil.importing

/**
 * **Where an import comes from**: a second source exists only when a cloud provider is
 * installed. Without one the tap goes straight to the document picker, as every import did
 * before; with one it asks first, this device then the provider. What the cloud answer then
 * does is the Export screen's [com.symmetricalpalmtree.soil.export.ExportDestination.onCloudTap],
 * reused so "connected?" is answered in one place.
 */
object ImportSource {

    enum class Source { LOCAL, CLOUD }

    fun choices(providerInstalled: Boolean): List<Source> = if (providerInstalled) listOf(Source.LOCAL, Source.CLOUD) else listOf(Source.LOCAL)

    fun asksSource(providerInstalled: Boolean): Boolean = choices(providerInstalled).size > 1

    /** The answer at [index], or null: a list dialog answers with a position, and one the flow cannot place ends the beat. */
    fun sourceAt(index: Int, providerInstalled: Boolean): Source? = choices(providerInstalled).getOrNull(index)
}
