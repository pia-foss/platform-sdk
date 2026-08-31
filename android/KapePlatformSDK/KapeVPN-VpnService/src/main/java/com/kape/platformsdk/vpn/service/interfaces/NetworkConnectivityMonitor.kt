package com.kape.platformsdk.vpn.service.interfaces

/**
 * Reports whether the device currently has a usable ("validated") network path, and lets the run
 * loop suspend until connectivity returns instead of retrying blindly against a network already
 * known to be down.
 */
interface NetworkConnectivityMonitor {
    /** True once a validated, usable network path is present. */
    val isOnline: Boolean

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

    override suspend fun awaitConnectivity() = Unit

    override fun start() = Unit

    override fun stop() = Unit
}
