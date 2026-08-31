package com.kape.platformsdk.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.kape.platformsdk.vpn.service.interfaces.NetworkConnectivityMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * [NetworkConnectivityMonitor] backed by [ConnectivityManager.registerNetworkCallback] — tracks
 * every network the device knows about (mirroring [com.kape.platformsdk.core.KapeProtectedDnsResolver]'s
 * approach) and reports online whenever at least one is both validated and **not** a VPN network.
 *
 * The naive approach — [ConnectivityManager.registerDefaultNetworkCallback] — doesn't work here:
 * this monitor runs inside the same process that owns the VPN tunnel (including the network-lock
 * "kill switch" tunnel kept up between connections), and Android resolves that process's own
 * "default network" to its *own* VPN network, not the underlying Wi-Fi/cellular one. That
 * self-owned VPN network can stay reported `VALIDATED` even after its real underlying network
 * (e.g. Wi-Fi) has completely disappeared, which would make this monitor always report online no
 * matter what actually happens to the device's connectivity — confirmed by an on-device test that
 * disabled Wi-Fi while connected and saw no state change at all. Explicitly excluding
 * `TRANSPORT_VPN` networks is what makes this track the same thing `NWPathMonitor` does on Apple.
 *
 * Registered for the lifetime of a VPN session — started from `KapeSessionController.start()`,
 * torn down from `stop()`.
 */
class KapeNetworkConnectivityMonitor(
    private val context: Context,
    private val logger: VpnServiceLogger = NoOpVpnServiceLogger,
) : NetworkConnectivityMonitor {
    // Every known network's last-reported capabilities, keyed by Network — mirrors
    // KapeProtectedDnsResolver's trackedNetworks. Read by updateOnlineState(); the callback below
    // is the only writer.
    private val networkCapabilities = ConcurrentHashMap<Network, NetworkCapabilities>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    // Optimistic default: a monitor that hasn't started yet, or whose very first callback hasn't
    // landed, is treated as reachable — so a fresh session never blocks on the initial callback.
    private val onlineFlow = MutableStateFlow(true)

    override val isOnline: Boolean get() = onlineFlow.value

    override fun start() {
        if (callback != null) return
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            logger.error("[connectivity] ConnectivityManager unavailable — treating as always online")
            return
        }
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    this@KapeNetworkConnectivityMonitor.networkCapabilities[network] = networkCapabilities
                    updateOnlineState()
                }

                override fun onLost(network: Network) {
                    this@KapeNetworkConnectivityMonitor.networkCapabilities.remove(network)
                    updateOnlineState()
                }
            }
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), newCallback)
        callback = newCallback
        logger.info("[connectivity] monitor started")
    }

    override fun stop() {
        val current = callback ?: return
        callback = null
        networkCapabilities.clear()
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(current)
        }
        logger.info("[connectivity] monitor stopped")
    }

    override suspend fun awaitConnectivity() {
        onlineFlow.first { it }
    }

    private fun updateOnlineState() {
        val isOnline =
            networkCapabilities.values.any { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        logger.debug("[connectivity] state changed — online=$isOnline")
        onlineFlow.update { isOnline }
    }
}
