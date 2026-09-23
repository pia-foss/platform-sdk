package com.kape.platformsdk.vpn.service

import com.kape.platformsdk.vpn.service.analytics.AttemptResult
import com.kape.platformsdk.vpn.service.analytics.ConnectReason
import com.kape.platformsdk.vpn.service.analytics.DisconnectReason
import com.kape.platformsdk.vpn.service.analytics.KapeConnectSource
import com.kape.platformsdk.vpn.service.analytics.NoOpVpnConnectionAnalytics
import com.kape.platformsdk.vpn.service.analytics.SessionAnalyticsReporter
import com.kape.platformsdk.vpn.service.analytics.VpnConnectionAnalytics
import com.kape.platformsdk.vpn.service.interfaces.ConnectionAttemptReporting
import com.kape.platformsdk.vpn.service.interfaces.ConnectionController
import com.kape.platformsdk.vpn.service.interfaces.DefaultGeneratorRetryBackoff
import com.kape.platformsdk.vpn.service.interfaces.GeneratorRetryBackoff
import com.kape.platformsdk.vpn.service.interfaces.KapeVpnTunnelException
import com.kape.platformsdk.vpn.service.interfaces.NetworkConnectivityMonitor
import com.kape.platformsdk.vpn.service.interfaces.NoConfigurationException
import com.kape.platformsdk.vpn.service.interfaces.NoOpNetworkConnectivityMonitor
import com.kape.platformsdk.vpn.service.interfaces.VpnConfiguration
import com.kape.platformsdk.vpn.service.interfaces.VpnConfigurationGenerator
import com.kape.platformsdk.vpn.service.interfaces.configurations
import com.kape.platformsdk.vpn.service.models.IpAddress
import com.kape.platformsdk.vpn.service.models.KapeVPNConnectionStatus
import com.kape.platformsdk.vpn.service.models.KapeVpnTrafficStats
import com.kape.platformsdk.vpn.service.models.KapeVpnTunnelError
import com.kape.platformsdk.vpn.service.models.VpnServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.reflect.KClass
import kotlin.time.Duration

class KapeSessionController(
    val configurationGenerator: VpnConfigurationGenerator,
    connectionControllers: List<ConnectionController<*>>,
    private val systemTunnel: KapeSystemTunnel,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: VpnServiceLogger = NoOpVpnServiceLogger,
    analytics: VpnConnectionAnalytics = NoOpVpnConnectionAnalytics,
    // Single-threaded by default, so callbacks arrive in order (see SessionAnalyticsReporter).
    // Tests pass UnconfinedTestDispatcher here too, same as `dispatcher` above.
    analyticsDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    // Pass the same scope handed to a ConnectionController's constructor (e.g.
    // LightwayConnectionController's parentScope) so a session stop also reaches its
    // connection-side background work. Defaults to an owned scope when the caller has none to share.
    private val runLoopScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
    selectedProtocol: String = "automatic",
    selectedLocationDescription: String? = null,
    connectSource: KapeConnectSource = KapeConnectSource.Manual,
    // Wait applied between configuration-generator retries after an empty batch (see
    // VpnConfigurationGenerator.configurations()).
    private val generatorRetryBackoff: GeneratorRetryBackoff = DefaultGeneratorRetryBackoff,
    // Gates endpoint fetches/retries on actual network reachability — see runConnectionLoop().
    private val networkConnectivityMonitor: NetworkConnectivityMonitor = NoOpNetworkConnectivityMonitor,
) : ConnectionAttemptReporting {
    val state = VpnServiceState()

    private val reporter =
        SessionAnalyticsReporter(
            analytics,
            logger,
            analyticsDispatcher,
            selectedProtocol,
            selectedLocationDescription,
            connectSource,
        )

    // Guards reportConnectionRestart() — failed attempts after a drop should open one
    // replacement connection, not one per attempt. Cleared on the next successful attempt.
    private var reconnectInProgress = false

    // Read by runLoop()/start()'s finally blocks when reporting connectionEnd/sessionEnd.
    // Default covers the run loop exiting on its own (e.g. no more configurations).
    private var stopReason: DisconnectReason = DisconnectReason.NoEndpointsAvailable

    private val connectionControllers: Map<KClass<out VpnConfiguration>, ConnectionController<*>> =
        connectionControllers.associateBy { it.configurationClass }

    @Volatile
    private var activeController: ConnectionController<*>? = null

    // Serializes pause()/resume()/resumeIfDue() — Kotlin has no actor isolation.
    private val pauseMutex = Mutex()

    /** True while parked in bypass mode. Drives the run-loop gate and guards. */
    var isPaused: Boolean = false
        private set

    /** Authoritative wall-clock end (epoch millis) of the current pause. */
    private var pausedUntilMillis: Long? = null

    /** Run loop parks here while paused; completed by [resume]. */
    private var resumeContinuation: CompletableDeferred<Unit>? = null

    // Best-effort auto-resume timer; pausedUntilMillis is authoritative. Manipulated outside
    // pauseMutex: the launched coroutine calls resumeIfDue(), which also takes pauseMutex, so
    // launching it from inside a withLock block risks a non-reentrant deadlock if that launch
    // ever runs synchronously (e.g. delay() not suspending for a non-positive duration).
    @Volatile
    private var resumeTimerJob: Job? = null

    // Wired onto every ConnectionController by KapeVpnService after construction.
    override fun reportAttemptBegin(configuration: VpnConfiguration): UUID = reporter.reportAttemptBegin(configuration)

    override fun reportAttemptEnd(
        attemptId: UUID,
        result: AttemptResult,
        elapsedMs: Long,
        configuration: VpnConfiguration?,
    ) = reporter.reportAttemptEnd(attemptId, result, elapsedMs, configuration)

    /**
     * The private IP of the VPN server for the currently active connection, or null if no
     * connection is currently active or the active protocol doesn't expose a gateway.
     */
    fun getGatewayForCurrentConnection(): IpAddress? = activeController?.getGateway()

    /**
     * Forces the currently-active connection to stop, letting the run loop reconnect immediately
     * (advancing to the next configuration) instead of tearing down the whole session. No-op if
     * no connection is currently active (e.g. still connecting, or already disconnected).
     */
    suspend fun forceReconnect() {
        if (isPaused) {
            logger.info("[runloop] forceReconnect requested while paused — resuming instead")
            resume()
            return
        }
        val controller = activeController
        if (controller == null) {
            logger.info("[runloop] forceReconnect requested but no active connection")
            return
        }
        logger.info("[runloop] forceReconnect requested — stopping active connection")
        state.connectionStatus.update { KapeVPNConnectionStatus.Reconnecting }
        controller.stop()
    }

    suspend fun start() {
        logger.info("[runloop] start")
        networkConnectivityMonitor.start()
        reporter.reportSessionBegin()
        val handler = CoroutineExceptionHandler { _, _ -> state.connectionStatus.update { KapeVPNConnectionStatus.Disconnected } }
        runLoopScope.launch(handler) {
            try {
                runSession()
            } finally {
                withContext(NonCancellable) {
                    state.connectionStatus.update { KapeVPNConnectionStatus.Disconnected }
                    state.trafficStats.update { KapeVpnTrafficStats.ZERO }
                    reporter.reportSessionEnd(stopReason)
                }
            }
        }
        runLoopScope.launch { pollTrafficStats() }
    }

    private suspend fun pollTrafficStats() {
        while (true) {
            delay(TRAFFIC_STATS_POLL_INTERVAL_MS)
            activeController?.getTrafficStats()?.let { stats -> state.trafficStats.update { stats } }
        }
    }

    suspend fun stop(reason: DisconnectReason = DisconnectReason.UserInitiated) {
        logger.info("[runloop] stop")
        state.connectionStatus.update { KapeVPNConnectionStatus.Disconnecting }
        stopReason = reason
        networkConnectivityMonitor.stop()
        // cancelAndJoin() below cancels the run loop wherever it is — including a parked
        // awaitResume() — via normal coroutine cancellation, so no manual pause teardown is needed.
        // Bounded wait for the run loop's finally blocks to report/tear down before flush().
        val stoppedCleanly =
            withTimeoutOrNull(STOP_TIMEOUT_MS) {
                runLoopScope.coroutineContext.job.cancelAndJoin()
                true
            }
        if (stoppedCleanly == null) {
            logger.warning("[runloop] stop() timed out waiting for run loop teardown after ${STOP_TIMEOUT_MS}ms")
        }
        // Last: the OS can reclaim this process shortly after stop() returns.
        reporter.flush()
        reporter.shutdown()
    }

    /**
     * Starts and runs as long as the VPN session is interrupted (e.g. stop() called).
     * A VPN session corresponds to the user's intent to be connected to a single location:
     * a single session may have multiple VPN connections as when one connection fails,
     * the session will try to reconnect automatically.
     */
    suspend fun runSession() {
        state.connectionStatus.update { KapeVPNConnectionStatus.Connecting }
        reporter.reportConnectionBegin(ConnectReason.Initial)

        if (!systemTunnel.openNetworkLockTunnel()) {
            // establish() only refuses when this app isn't the OS's prepared VPN app. The session
            // ends here, so lastTunnelError is the app's only signal.
            logger.error("[runloop] network lock tunnel establish failed at session start — aborting")
            state.lastTunnelError.update { KapeVpnTunnelError.VpnPermissionMissing }
            return
        }

        try {
            runConnectionLoop()
        } finally {
            withContext(NonCancellable) {
                reporter.reportConnectionEnd(stopReason)
                systemTunnel.closeCurrentTunnel()
            }
        }
    }

    /**
     * Pulls configurations from [configurationGenerator] and runs each through connect/runVPN.
     * An empty batch (see `VpnConfigurationGenerator.configurations()`) throws
     * [NoConfigurationException] instead of tearing the session down — a disconnected VPN
     * profile can leak traffic outside the tunnel, so we'd rather back off and retry than fail
     * closed-then-open. The whole "connection" analytics span (begin/end) stays open across
     * these retries, same as a successful reconnect — only [runSession]'s caller ends it.
     *
     * `tailrec` keeps the indefinite retry from growing the call stack; the recursive call must
     * stay outside the try/catch below since Kotlin can't tail-optimize a call made from inside one.
     */
    private tailrec suspend fun runConnectionLoop(retryCount: Int = 0) {
        var consecutiveFailureCount = retryCount
        var generatorFailed = false
        networkConnectivityMonitor.awaitConnectivity()
        try {
            configurationGenerator.configurations().collect { configuration ->
                // If we don't have connectivity, there is no need to try to connect the VPN
                networkConnectivityMonitor.awaitConnectivity()

                val configName = configuration::class.simpleName ?: "Unknown"
                logger.info("[runloop] next configuration: $configName")

                val controller = connectionControllers[configuration::class]
                if (controller == null) {
                    logger.error("No ConnectionController registered for $configName")
                    return@collect
                }

                // The cast cannot be checked as the type system lost the relationship between ConnectionController and VpnConfiguration
                // when we created the connectionControllers map. However, the ConnectionController interface do define that relationship
                // so that cast is safe
                @Suppress("UNCHECKED_CAST")
                val typedController = controller as ConnectionController<VpnConfiguration>

                logger.debug("[runloop] connect: $configName")
                val connected = typedController.connect(configuration)
                if (!connected) {
                    logger.info("[runloop] connect failed — advancing")
                    return@collect
                }
                reconnectInProgress = false

                logger.info("[runloop] connected: $configName")
                activeController = typedController
                consecutiveFailureCount = 0
                state.lastTunnelError.update { null }
                state.connectionStatus.update { KapeVPNConnectionStatus.Connected }

                val error =
                    try {
                        typedController.runVPN()
                    } finally {
                        withContext(NonCancellable) {
                            activeController = null
                            state.trafficStats.update { KapeVpnTrafficStats.ZERO }
                            typedController.stop()
                        }
                    }

                // Paused: park here so the loop doesn't reconnect until resume() — or until
                // stop()'s cancelAndJoin() interrupts this suspension directly. Reconnecting is
                // the run loop's own status to set, once it notices it just came out of a park.
                if (isPaused) {
                    awaitResume()
                    state.connectionStatus.update { KapeVPNConnectionStatus.Reconnecting }
                }

                // The just-stopped controller's tunnel is deliberately left open here — nothing
                // reads from it anymore, but switching to the network lock tunnel now would itself
                // cause a leak window (tearing down/recreating the OS interface). Its routes stay
                // claimed until the next real establish() call, real or network-lock. Safe even
                // though the controller called stop(): KapeSystemTunnel handed it its own dup()'d
                // descriptor, so KapeSystemTunnel's retained copy is unaffected either way.
                if (error != null) {
                    logger.info("[runloop] runVPN returned — reconnecting: $error")
                    state.connectionStatus.update { KapeVPNConnectionStatus.Reconnecting }
                    if (!reconnectInProgress) {
                        reconnectInProgress = true
                        reporter.reportConnectionRestart(
                            DisconnectReason.ConnectionError(error),
                            ConnectReason.NetworkLoss,
                        )
                    }
                } else {
                    logger.info("[runloop] runVPN returned")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: KapeVpnTunnelException) {
            // Actionable, so record why — the loop still backs off and retries.
            logger.error("[runloop] connection loop failed (failure #${consecutiveFailureCount + 1}) — retrying. Error: ${e.error}")
            state.lastTunnelError.update { e.error }
            generatorFailed = true
        } catch (e: Exception) {
            logger.error("[runloop] connection loop failed (failure #${consecutiveFailureCount + 1}) — retrying. Error: $e")
            generatorFailed = true
        }
        if (generatorFailed) {
            // Offline: the failure is connectivity-related, not a server/auth problem — defer to
            // awaitConnectivity() at the top of the next runConnectionLoop pass instead of
            // sleeping out a fixed backoff that a network recovery can't interrupt.
            if (networkConnectivityMonitor.isOnline) {
                generatorRetryBackoff.wait(consecutiveFailureCount + 1)
            } else {
                logger.debug("[runloop] offline — deferring retry to connectivity wait instead of backoff")
            }
            runConnectionLoop(consecutiveFailureCount + 1)
        }
    }

    /**
     * Pauses the tunnel (bypass mode) for [duration]. Keeps the process alive, tears down the
     * OS-level tunnel interface so traffic routes directly, and arms an auto-resume. Re-arms if
     * already paused (does not re-report analytics or re-close the tunnel). Ignored while stopping.
     */
    suspend fun pause(duration: Duration) {
        if (!runLoopScope.isActive) {
            logger.info("[pause] pause requested but session is stopping — ignored")
            return
        }
        logger.info("[pause] pause(duration=$duration) — alreadyPaused=$isPaused")

        val until = safePausedUntilMillis(duration)
        val firstPause =
            pauseMutex.withLock {
                val wasPaused = isPaused
                isPaused = true
                pausedUntilMillis = until
                !wasPaused
            }
        state.pausedUntil.update { until }

        // Outside the lock — see resumeTimerJob's comment. Cancel the previous timer so a stale
        // early fire (on the original, shorter duration) can't ignore this extension.
        resumeTimerJob?.cancel()
        resumeTimerJob =
            runLoopScope.launch {
                delay(duration)
                resumeIfDue()
            }

        if (firstPause) {
            state.connectionStatus.update { KapeVPNConnectionStatus.Paused }
            reporter.reportConnectionEnd(DisconnectReason.Paused)

            // Both descriptors reference the same OS-level TUN registration (see
            // KapeSystemTunnel.establish()) and must be closed for it to actually tear down:
            // the controller's own dup() first (also unblocks runVPN() so the run loop reaches
            // the park gate below), then the system tunnel's retained master descriptor.
            activeController?.stop()
            systemTunnel.closeCurrentTunnel()
        }
    }

    /**
     * Resumes a paused tunnel: cancels the timer and wakes the run loop to reconnect. No-op when
     * not paused.
     */
    suspend fun resume() {
        val continuation =
            pauseMutex.withLock {
                if (!isPaused) return@withLock null
                isPaused = false
                pausedUntilMillis = null
                val cont = resumeContinuation
                resumeContinuation = null
                cont
            } ?: return

        // Don't self-cancel when the timer's own resumeIfDue() invoked this — it's already
        // finishing on its own.
        resumeTimerJob?.takeIf { it !== coroutineContext[Job] }?.cancel()
        resumeTimerJob = null

        logger.info("[pause] resume")
        state.pausedUntil.update { null }
        // Restores kill-switch protection immediately instead of leaving a gap until the next
        // real connect() re-establishes it — no-op under KapeKillSwitchMode.Off. The run loop
        // itself sets Reconnecting once it notices it's coming out of the park (see runLoop()).
        systemTunnel.openNetworkLockTunnel()
        reporter.reportConnectionBegin(ConnectReason.Resumed)
        continuation.complete(Unit)
    }

    /**
     * Wall-clock gate: resume only once the pause has elapsed. Called by the auto-resume timer.
     */
    suspend fun resumeIfDue() {
        val due =
            pauseMutex.withLock {
                isPaused && pausedUntilMillis?.let { System.currentTimeMillis() >= it } == true
            }
        if (due) resume()
    }

    /**
     * Parks the run loop until [resume] fires, or until cancelled by [stop]. No-op if [resume]
     * already fired before the loop got here — `isPaused` would already read `false`.
     */
    private suspend fun awaitResume() {
        val deferred = CompletableDeferred<Unit>()
        pauseMutex.withLock {
            if (!isPaused) return
            resumeContinuation = deferred
        }
        deferred.await()
    }

    /**
     * Computes the wall-clock pause end, clamping [duration] so it can't overflow `Long` and wrap
     * into a garbage timestamp (e.g. `Duration.INFINITE`). Negative durations are left as-is —
     * `pause(negative)` is a valid way to arm an already-elapsed pause.
     */
    private fun safePausedUntilMillis(duration: Duration): Long {
        val addMillis = minOf(duration.inWholeMilliseconds, MAX_PAUSE_DURATION_MILLIS)
        return System.currentTimeMillis() + addMillis
    }

    private companion object {
        const val TRAFFIC_STATS_POLL_INTERVAL_MS = 1_000L
        const val STOP_TIMEOUT_MS = 2_000L

        // Generous cap comfortably below Long.MAX_VALUE so adding it to currentTimeMillis() can
        // never overflow, regardless of caller input (including Duration.INFINITE).
        const val MAX_PAUSE_DURATION_MILLIS = Long.MAX_VALUE / 2
    }
}
