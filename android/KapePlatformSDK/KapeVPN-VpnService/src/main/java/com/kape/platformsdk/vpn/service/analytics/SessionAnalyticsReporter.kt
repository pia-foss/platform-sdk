package com.kape.platformsdk.vpn.service.analytics

import com.kape.platformsdk.vpn.service.NoOpVpnServiceLogger
import com.kape.platformsdk.vpn.service.VpnServiceLogger
import com.kape.platformsdk.vpn.service.interfaces.VpnConfiguration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.Executors

/**
 * [VpnConnectionAnalytics] reporting helpers for `KapeSessionController`, with its own
 * correlation state, independent of the controller's.
 *
 * Dispatches on a single-threaded [CoroutineDispatcher] so callbacks arrive off the run
 * loop, in the order events occurred.
 *
 * State is guarded by a plain JVM monitor ([lock]), not `Mutex` — `withLock` is `suspend`,
 * which would force every `report*()` (and `KapeSessionController.stop()`) to become
 * `suspend fun` too.
 */
class SessionAnalyticsReporter(
    private val analytics: VpnConnectionAnalytics = NoOpVpnConnectionAnalytics,
    private val logger: VpnServiceLogger = NoOpVpnServiceLogger,
    private val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
    private val selectedProtocol: String = "automatic",
    private val selectedLocationDescription: String? = null,
    private val connectSource: KapeConnectSource = KapeConnectSource.Manual,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()

    private var currentSessionId: UUID? = null
    private var sessionStartedAtMs: Long? = null
    private var hasEverConnected = false

    private var currentConnectionId: UUID? = null
    private var connectionStartedAtMs: Long? = null
    private var currentConnectionWasConnected = false
    private var currentConnectionEffectiveProtocol: String? = null
    private var currentConnectionEffectiveObfuscation: String? = null

    fun reportSessionBegin() {
        val event =
            synchronized(lock) {
                val sessionId = UUID.randomUUID()
                currentSessionId = sessionId
                sessionStartedAtMs = System.currentTimeMillis()
                hasEverConnected = false
                SessionBeginEvent(sessionId, connectSource, selectedProtocol, selectedLocationDescription)
            }
        dispatch(event) { a, e -> a.sessionDidBegin(e) }
    }

    fun reportSessionEnd(reason: DisconnectReason) {
        val event =
            synchronized(lock) {
                val sessionId = currentSessionId ?: return
                val startedAt = sessionStartedAtMs ?: return
                val wasEverConnected = hasEverConnected
                currentSessionId = null
                sessionStartedAtMs = null
                SessionEndEvent(
                    sessionId = sessionId,
                    connectSource = connectSource,
                    wasEverConnected = wasEverConnected,
                    reason = reason,
                    durationMs = System.currentTimeMillis() - startedAt,
                    selectedProtocol = selectedProtocol,
                    selectedLocationDescription = selectedLocationDescription,
                )
            }
        dispatch(event) { a, e -> a.sessionDidEnd(e) }
    }

    fun reportConnectionBegin(reason: ConnectReason): UUID {
        val connectionId = UUID.randomUUID()
        val event =
            synchronized(lock) {
                currentConnectionId = connectionId
                connectionStartedAtMs = System.currentTimeMillis()
                currentConnectionWasConnected = false
                currentConnectionEffectiveProtocol = null
                currentConnectionEffectiveObfuscation = null
                currentSessionId?.let { sessionId ->
                    ConnectionBeginEvent(sessionId, connectionId, reason, selectedProtocol, selectedLocationDescription)
                }
            }
        if (event != null) dispatch(event) { a, e -> a.connectionDidBegin(e) }
        return connectionId
    }

    fun reportConnectionEnd(reason: DisconnectReason) {
        val event =
            synchronized(lock) {
                val sessionId = currentSessionId ?: return
                val connectionId = currentConnectionId ?: return
                val startedAt = connectionStartedAtMs ?: return
                val wasConnected = currentConnectionWasConnected
                val effectiveProtocol = currentConnectionEffectiveProtocol
                val effectiveObfuscation = currentConnectionEffectiveObfuscation
                currentConnectionId = null
                connectionStartedAtMs = null
                currentConnectionWasConnected = false
                currentConnectionEffectiveProtocol = null
                currentConnectionEffectiveObfuscation = null
                ConnectionEndEvent(
                    sessionId = sessionId,
                    connectionId = connectionId,
                    wasConnected = wasConnected,
                    reason = reason,
                    durationMs = System.currentTimeMillis() - startedAt,
                    effectiveProtocol = effectiveProtocol,
                    effectiveObfuscation = effectiveObfuscation,
                    selectedProtocol = selectedProtocol,
                    selectedLocationDescription = selectedLocationDescription,
                )
            }
        dispatch(event) { a, e -> a.connectionDidEnd(e) }
    }

    /**
     * Ends the live connection and opens its replacement back-to-back — for network-loss,
     * which has no real time gap (unlike a future pause/resume, handled separately).
     */
    fun reportConnectionRestart(
        endReason: DisconnectReason,
        beginReason: ConnectReason,
    ) {
        reportConnectionEnd(endReason)
        reportConnectionBegin(beginReason)
    }

    fun reportAttemptBegin(configuration: VpnConfiguration): UUID {
        val attemptId = UUID.randomUUID()
        val event =
            synchronized(lock) {
                val sessionId = currentSessionId
                val connectionId = currentConnectionId
                if (sessionId != null && connectionId != null) {
                    AttemptBeginEvent(sessionId, connectionId, attemptId, configuration, selectedProtocol, selectedLocationDescription)
                } else {
                    null
                }
            }
        if (event != null) dispatch(event) { a, e -> a.attemptDidBegin(e) }
        return attemptId
    }

    fun reportAttemptEnd(
        attemptId: UUID,
        result: AttemptResult,
        elapsedMs: Long,
        configuration: VpnConfiguration? = null,
    ) {
        if (result == AttemptResult.Connected) {
            synchronized(lock) {
                currentConnectionWasConnected = true
                hasEverConnected = true
                currentConnectionEffectiveProtocol = configuration?.vpnProtocolName
                currentConnectionEffectiveObfuscation = configuration?.obfuscationDescription
            }
        }
        dispatch(AttemptEndEvent(attemptId, result, elapsedMs, selectedProtocol, selectedLocationDescription)) { a, e ->
            a.attemptDidEnd(e)
        }
    }

    private fun <T> dispatch(
        event: T,
        call: (VpnConnectionAnalytics, T) -> Unit,
    ) {
        logger.debug("[analytics] $event")
        scope.launch { call(analytics, event) }
    }

    /**
     * Blocks — bounded by [timeoutMs] — until every queued dispatch completes. Call once,
     * at the end of `stop()`, which can run on the Main thread (`onDestroy`/`onRevoke`) —
     * an unbounded join here risks an ANR. Times out silently: dropped events beat a hung app.
     */
    fun flush(timeoutMs: Long = 200) {
        runBlocking {
            val completed =
                withTimeoutOrNull(timeoutMs) {
                    scope.coroutineContext.job.children
                        .toList()
                        .forEach { it.join() }
                    true
                }
            if (completed == null) {
                logger.warning("[analytics] flush() timed out after ${timeoutMs}ms")
            }
        }
    }

    /** Cancels the scope and closes [dispatcher] if it's the default owned executor. */
    fun shutdown() {
        scope.cancel()
        (dispatcher as? ExecutorCoroutineDispatcher)?.close()
    }
}
