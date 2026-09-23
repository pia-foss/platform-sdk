package com.kape.platformsdk.vpn.service.interfaces

import com.kape.platformsdk.vpn.service.models.KapeNetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Reports whether the device currently has a usable ("validated") network path, and lets the run
 * loop suspend until connectivity returns instead of retrying blindly against a network already
 * known to be down.
 *
 * Also publishes [networkState], which is what Connect on Demand evaluates its rules against.
 */
interface NetworkConnectivityMonitor {
    /** True once a validated, usable network path is present. */
    val isOnline: Boolean

    /** The current non-VPN network. Emits on every change, so it can be observed edge-triggered. */
    val networkState: StateFlow<KapeNetworkState>

    /** Suspends until [isOnline] becomes true. Returns immediately if already true. */
    suspend fun awaitConnectivity()

    /** Starts observing the device's network state. Idempotent. */
    fun start()

    /** Stops observing and releases any registered system callback. Idempotent. */
    fun stop()
}

/** Always reports online — the default for consumers that don't need a reachability check. */
object NoOpNetworkConnectivityMonitor : NetworkConnectivityMonitor {
    override val isOnline: Boolean = true

    override val networkState: StateFlow<KapeNetworkState> = MutableStateFlow(KapeNetworkState.OFFLINE)

    override suspend fun awaitConnectivity() = Unit

    override fun start() = Unit

    override fun stop() = Unit
}

/**
 * A non-owning view of [delegate]: reads pass through, but [start] and [stop] do nothing.
 *
 * This is what lets one process-lifetime monitor serve both Connect on Demand and a VPN session —
 * the session must not be able to unregister a callback it does not own when it tears down.
 */
class BorrowedNetworkConnectivityMonitor(
    private val delegate: NetworkConnectivityMonitor,
) : NetworkConnectivityMonitor {
    override val isOnline: Boolean get() = delegate.isOnline

    override val networkState: StateFlow<KapeNetworkState> get() = delegate.networkState

    override suspend fun awaitConnectivity() = delegate.awaitConnectivity()

    override fun start() = Unit

    override fun stop() = Unit
}
