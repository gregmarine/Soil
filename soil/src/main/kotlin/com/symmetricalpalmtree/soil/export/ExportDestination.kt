package com.symmetricalpalmtree.soil.export

import com.symmetricalpalmtree.soil.ext.CloudStatus

/**
 * **Where an export goes**: the Destination row's decision core, pure. The row asks one question,
 * this device or the cloud, and exists only when a provider is installed: GONE otherwise, never
 * disabled. A standing cloud answer is forced back to local whenever the row is not on screen,
 * so a provider uninstalled under a standing screen never leaves an export aimed at a cloud that
 * is no longer there. A tap on the cloud radio is judged by the last status: a build with no
 * credentials says so first; no account, or no answer, is the Connect offer; connected selects.
 */
object ExportDestination {

    enum class Choice { LOCAL, CLOUD }

    enum class Tap { SELECT, NOT_CONFIGURED, OFFER_CONNECT }

    fun rowVisible(providerInstalled: Boolean): Boolean = providerInstalled

    fun settled(choice: Choice, rowVisible: Boolean): Choice = if (rowVisible) choice else Choice.LOCAL

    fun onCloudTap(status: CloudStatus?): Tap = when {
        status == null -> Tap.OFFER_CONNECT
        !status.configured -> Tap.NOT_CONFIGURED
        !status.connected -> Tap.OFFER_CONNECT
        else -> Tap.SELECT
    }

    /** What every cloud sentence calls the provider: the name it gave, or the extension's label. */
    fun providerName(status: CloudStatus?, extensionLabel: String): String =
        status?.providerName?.takeIf { it.isNotBlank() } ?: extensionLabel

    /** The folder under the provider's root that exports go to, and the browser's floor. */
    const val EXPORTS_FOLDER = "Exports"
}
