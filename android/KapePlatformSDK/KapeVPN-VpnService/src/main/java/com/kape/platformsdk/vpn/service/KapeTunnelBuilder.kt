package com.kape.platformsdk.vpn.service

import android.os.ParcelFileDescriptor
import java.net.InetAddress

/**
 * Mirrors [android.net.VpnService.Builder]'s fluent surface so every existing call site
 * (`.addAddress(...).addRoute(...)....establish()?.detachFd()`) keeps compiling unchanged, while
 * accumulating the configured [TunnelSettings] so [KapeSystemTunnel] can decide whether this
 * request can reuse the currently-active tunnel instead of establishing a new one.
 */
class KapeTunnelBuilder internal constructor(
    private val systemTunnel: KapeSystemTunnel,
) {
    private var session: String? = null
    private val addresses = mutableListOf<TunnelAddress>()
    private val routes = mutableListOf<TunnelAddress>()
    private val dnsServers = mutableListOf<String>()
    private var mtu: Int = 0
    private var metered: Boolean = false

    fun setSession(session: String): KapeTunnelBuilder = apply { this.session = session }

    fun addAddress(
        address: String,
        prefixLength: Int,
    ): KapeTunnelBuilder = apply { addresses += TunnelAddress(address, prefixLength) }

    // VpnService.Builder overloads on InetAddress too (WireGuard's config types hand these out
    // directly) — normalize to the literal address string, same representation used everywhere
    // else, so equality comparisons in KapeSystemTunnel.establish() stay consistent.
    fun addAddress(
        address: InetAddress,
        prefixLength: Int,
    ): KapeTunnelBuilder = addAddress(address.hostAddress ?: address.toString(), prefixLength)

    fun addRoute(
        address: String,
        prefixLength: Int,
    ): KapeTunnelBuilder = apply { routes += TunnelAddress(address, prefixLength) }

    fun addRoute(
        address: InetAddress,
        prefixLength: Int,
    ): KapeTunnelBuilder = addRoute(address.hostAddress ?: address.toString(), prefixLength)

    fun addDnsServer(address: String): KapeTunnelBuilder = apply { dnsServers += address }

    fun addDnsServer(address: InetAddress): KapeTunnelBuilder = addDnsServer(address.hostAddress ?: address.toString())

    fun setMtu(mtu: Int): KapeTunnelBuilder = apply { this.mtu = mtu }

    fun setMetered(metered: Boolean): KapeTunnelBuilder = apply { this.metered = metered }

    suspend fun establish(): ParcelFileDescriptor? =
        systemTunnel.establish(
            TunnelSettings(
                addresses = addresses.toList(),
                routes = routes.toList(),
                dnsServers = dnsServers.toList(),
                mtu = mtu,
                metered = metered,
            ),
            session,
        )
}
