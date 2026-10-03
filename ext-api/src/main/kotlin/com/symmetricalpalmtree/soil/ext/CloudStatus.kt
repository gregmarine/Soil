package com.symmetricalpalmtree.soil.ext

import android.os.Parcel
import android.os.Parcelable

/** Where the provider stands: built with its credentials at all, an account connected, and the
 *  account's label (an address, say) for a screen. The label is never printed by [toString]. */
class CloudStatus(val connected: Boolean, val configured: Boolean, val accountLabel: String, val providerName: String) : Parcelable {

    init {
        require(!connected || configured) { "connected without being configured" }
        require(CloudContract.isLabel(accountLabel, CloudContract.MAX_ACCOUNT_LABEL_CHARS)) { "account label is not display text" }
        require(connected || accountLabel.isEmpty()) { "account label without a connection" }
        require(providerName.isNotBlank() && providerName == providerName.trim()) { "provider name is blank or padded" }
        require(CloudContract.isLabel(providerName, CloudContract.MAX_PROVIDER_NAME_CHARS)) { "provider name is not display text" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(if (connected) 1 else 0)
        dest.writeInt(if (configured) 1 else 0)
        dest.writeString(accountLabel)
        dest.writeString(providerName)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is CloudStatus && other.connected == connected && other.configured == configured && other.accountLabel == accountLabel && other.providerName == providerName

    override fun hashCode(): Int = ((if (connected) 1 else 0) * 31 + (if (configured) 1 else 0)) * 31 * 31 + accountLabel.hashCode() * 31 + providerName.hashCode()

    override fun toString(): String = "CloudStatus($providerName, connected=$connected, configured=$configured, label=${accountLabel.length} chars)"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<CloudStatus> = object : Parcelable.Creator<CloudStatus> {
            override fun createFromParcel(parcel: Parcel): CloudStatus =
                CloudStatus(parcel.readInt() != 0, parcel.readInt() != 0, parcel.readString() ?: "", parcel.readString() ?: "")
            override fun newArray(size: Int): Array<CloudStatus?> = arrayOfNulls(size)
        }
    }
}
