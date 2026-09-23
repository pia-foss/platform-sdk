package com.kape.platformsdk.vpn.service.models

/** The kind of network in use, ignoring any VPN layered on top. */
enum class KapeNetworkTransport {
    Wifi,
    Cellular,
    Other,
}

/**
 * The device's current non-VPN network. A `null` [transport] means offline.
 *
 * Identity fields are `null` when unreadable, which is deliberately not the same as absent — see
 * [com.kape.platformsdk.vpn.service.OnDemandDecision.Unidentified]. [ssid]/[isSecure] are WiFi only
 * and need location permission on API 29+; [carrier] is cellular only.
 */
data class KapeNetworkState(
    val transport: KapeNetworkTransport? = null,
    val ssid: String? = null,
    val carrier: String? = null,
    val isSecure: Boolean? = null,
) {
    companion object {
        val OFFLINE = KapeNetworkState()
    }
}
