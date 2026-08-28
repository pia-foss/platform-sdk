package com.kape.platformsdk.vpn.service

/** A single address/route entry — an IP literal plus its prefix length. */
data class TunnelAddress(
    val address: String,
    val prefixLength: Int,
)

/**
 * The full set of TUN interface parameters a [KapeTunnelBuilder] accumulates before calling
 * [KapeTunnelBuilder.establish]. Structural equality is what [KapeSystemTunnel] uses to decide
 * whether a request can reuse the currently-active tunnel instead of establishing a new one.
 */
data class TunnelSettings(
    val addresses: List<TunnelAddress>,
    val routes: List<TunnelAddress>,
    val dnsServers: List<String>,
    val mtu: Int,
    val metered: Boolean = false,
)
