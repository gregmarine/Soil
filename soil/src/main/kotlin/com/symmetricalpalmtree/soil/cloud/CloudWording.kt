package com.symmetricalpalmtree.soil.cloud

import com.symmetricalpalmtree.soil.ext.CloudStatus

/** The words a status line can end in, handed in so the rule holds no resources. */
class CloudWords(val notConnected: String, val connected: String, val notConfigured: String, val unavailable: String)

/**
 * The cloud status line as a pure rule: `<provider>: <detail>`, the questions in the order they
 * matter. Not configured first (no one can sign in on this build, and Connect would not work),
 * then not connected (what Connect is for), then the account's label (user content, shown on the
 * person's own screen only, never logged), then plain "connected" for a provider with no label.
 * [unavailableLine] is the fifth case, a provider that did not answer; it leaves Connect showing.
 */
object CloudWording {

    val DEFAULT_JOINER: (String, String) -> String = { provider, detail -> "$provider: $detail" }

    fun statusLine(status: CloudStatus, words: CloudWords, joiner: (String, String) -> String = DEFAULT_JOINER): String =
        joiner(status.providerName, detail(status, words))

    fun unavailableLine(providerName: String, words: CloudWords, joiner: (String, String) -> String = DEFAULT_JOINER): String =
        joiner(providerName, words.unavailable)

    fun detail(status: CloudStatus, words: CloudWords): String = when {
        !status.configured -> words.notConfigured
        !status.connected -> words.notConnected
        status.accountLabel.isNotEmpty() -> status.accountLabel
        else -> words.connected
    }

    /** Only a live connection turns the button to Disconnect; Connect is the one thing that helps otherwise. */
    fun showsDisconnect(status: CloudStatus?): Boolean = status != null && status.connected
}
