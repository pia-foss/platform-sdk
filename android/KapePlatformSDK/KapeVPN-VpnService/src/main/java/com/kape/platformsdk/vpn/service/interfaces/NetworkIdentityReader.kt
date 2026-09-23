package com.kape.platformsdk.vpn.service.interfaces

import android.net.NetworkCapabilities

/**
 * Reads which network the device is on, not merely what kind. Split from
 * [NetworkConnectivityMonitor] because it is the only part needing a runtime permission.
 *
 * `null` means unreadable, never "no such network".
 */
interface NetworkIdentityReader {
    /**
     * [capabilities] must be the instance delivered to a `NetworkCallback` registered with
     * `FLAG_INCLUDE_LOCATION_INFO`: the flag unredacts that callback's capabilities only, so a
     * fresh `getNetworkCapabilities()` reads `<unknown ssid>` whatever permissions are held.
     */
    fun wifiIdentity(capabilities: NetworkCapabilities?): WifiIdentity?

    fun carrierName(): String?

    data class WifiIdentity(
        val ssid: String,
        val isSecure: Boolean,
    )
}

/** The default, for consumers with no SSID-based rules. */
object NoNetworkIdentityReader : NetworkIdentityReader {
    override fun wifiIdentity(capabilities: NetworkCapabilities?): NetworkIdentityReader.WifiIdentity? = null

    override fun carrierName(): String? = null
}
