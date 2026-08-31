package com.kape.platformsdk.vpn.service.interfaces

import com.kape.platformsdk.vpn.service.models.IpAddress
import com.kape.platformsdk.vpn.service.models.KapeVpnTrafficStats
import kotlin.reflect.KClass

/**
 * Manages the lifecycle of a single VPN protocol's connection.
 *
 * Each VPN protocol (WireGuard, …) provides one concrete implementation.
 * [KapeSessionController] holds a map of controllers keyed by their [configurationClass]
 * and dispatches each configuration to the matching controller.
 *
 * Lifecycle for a single endpoint attempt:
 * 1. [connect] — establishes the tunnel. Returns true once the handshake completes, false on any
 *    failure. Only [CancellationException] is allowed to propagate. Reports its own attempt
 *    begin/end via [attemptReporter].
 * 2. [runVPN] — long-running. Suspends for the lifetime of the tunnel. Returns null on a clean
 *    exit (stop or cancellation), or a non-null [Throwable] when the tunnel fails
 *    post-establishment. A non-null return is the cue for the session controller to advance to
 *    the next configuration.
 * 3. [stop] — tears down the tunnel and unblocks any in-flight [runVPN] call.
 */
interface ConnectionController<Config : VpnConfiguration> {
    val configurationClass: KClass<Config>

    /** Set by whoever constructs this controller (`KapeVpnService`); used from [connect]. */
    var attemptReporter: ConnectionAttemptReporting?

    suspend fun connect(configuration: Config): Boolean

    suspend fun runVPN(): Throwable?

    suspend fun stop()

    /**
     * The private IP of the VPN server for the current connection, or null if unknown or no
     * connection is currently active.
     */
    fun getGateway(): IpAddress?

    /**
     * Cumulative traffic stats for the current connection, or null if unavailable — no
     * connection is active, or this protocol doesn't report traffic stats yet.
     */
    fun getTrafficStats(): KapeVpnTrafficStats? = null
}
