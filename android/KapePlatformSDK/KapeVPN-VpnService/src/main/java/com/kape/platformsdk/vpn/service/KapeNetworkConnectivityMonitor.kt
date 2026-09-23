package com.kape.platformsdk.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.kape.platformsdk.vpn.service.interfaces.NetworkConnectivityMonitor
import com.kape.platformsdk.vpn.service.interfaces.NetworkIdentityReader
import com.kape.platformsdk.vpn.service.interfaces.NoNetworkIdentityReader
import com.kape.platformsdk.vpn.service.models.KapeNetworkState
import com.kape.platformsdk.vpn.service.models.KapeNetworkTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Connect on Demand needs one of these for the life of the process, since a rule has to fire while
 * no session exists, so a VPN session gets a
 * [com.kape.platformsdk.vpn.service.interfaces.BorrowedNetworkConnectivityMonitor] over it rather
 * than registering its own.
 */
class KapeNetworkConnectivityMonitor(
    private val context: Context,
    private val logger: VpnServiceLogger = NoOpVpnServiceLogger,
    private val identityReader: NetworkIdentityReader = NoNetworkIdentityReader,
) : NetworkConnectivityMonitor {
    // Every known network's last-reported capabilities, keyed by Network — mirrors
    // KapeProtectedDnsResolver's trackedNetworks. Read by updateState(); the callback below
    // is the only writer.
    private val networkCapabilities = ConcurrentHashMap<Network, NetworkCapabilities>()
    private var callback: ConnectivityManager.NetworkCallback? = null

    // Optimistic default: a monitor that hasn't started yet, or whose very first callback hasn't
    // landed, is treated as reachable — so a fresh session never blocks on the initial callback.
    private val onlineFlow = MutableStateFlow(true)

    private val _networkState = MutableStateFlow(KapeNetworkState.OFFLINE)

    override val isOnline: Boolean get() = onlineFlow.value

    override val networkState: StateFlow<KapeNetworkState> = _networkState.asStateFlow()

    override fun start() {
        if (callback != null) return
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            logger.error("[connectivity] ConnectivityManager unavailable — treating as always online")
            return
        }
        val newCallback = newNetworkCallback()
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), newCallback)
        callback = newCallback
        logger.info("[connectivity] monitor started")
    }

    // FLAG_INCLUDE_LOCATION_INFO is the only route to an unredacted SSID for a background caller.
    private fun newNetworkCallback(): ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = onCapabilities(network, networkCapabilities)

                override fun onLost(network: Network) = onNetworkLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) = onCapabilities(network, networkCapabilities)

                override fun onLost(network: Network) = onNetworkLost(network)
            }
        }

    private fun onCapabilities(
        network: Network,
        capabilities: NetworkCapabilities,
    ) {
        networkCapabilities[network] = capabilities
        updateState()
    }

    private fun onNetworkLost(network: Network) {
        networkCapabilities.remove(network)
        updateState()
    }

    override fun stop() {
        val current = callback ?: return
        callback = null
        networkCapabilities.clear()
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(current)
        }
        _networkState.update { KapeNetworkState.OFFLINE }
        logger.info("[connectivity] monitor stopped")
    }

    override suspend fun awaitConnectivity() {
        onlineFlow.first { it }
    }

    private fun updateState() {
        val usable =
            networkCapabilities.values.filter { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        val isOnline = usable.isNotEmpty()
        logger.debug("[connectivity] state changed — online=$isOnline")
        onlineFlow.update { isOnline }
        _networkState.update { resolveNetworkState(usable) }
    }

    // WiFi wins over cellular when both are usable, as in KapeProtectedDnsResolver.
    private fun resolveNetworkState(usable: List<NetworkCapabilities>): KapeNetworkState {
        val wifiCapabilities = usable.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        val transport =
            when {
                wifiCapabilities != null -> KapeNetworkTransport.Wifi
                usable.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) } -> KapeNetworkTransport.Cellular
                usable.isNotEmpty() -> KapeNetworkTransport.Other
                else -> null
            } ?: return KapeNetworkState.OFFLINE

        return when (transport) {
            KapeNetworkTransport.Wifi -> {
                // Must be the callback's own instance — see NetworkIdentityReader.wifiIdentity.
                val wifi = identityReader.wifiIdentity(wifiCapabilities)
                KapeNetworkState(transport = transport, ssid = wifi?.ssid, isSecure = wifi?.isSecure)
            }

            KapeNetworkTransport.Cellular ->
                KapeNetworkState(transport = transport, carrier = identityReader.carrierName())

            KapeNetworkTransport.Other -> KapeNetworkState(transport = transport)
        }
    }
}
