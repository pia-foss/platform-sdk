package com.kape.platformsdk.vpn.service.analytics

import com.kape.platformsdk.vpn.service.interfaces.VpnConfiguration
import java.util.UUID

data class SessionBeginEvent(
    val sessionId: UUID,
    // What started this session — a user action, a Connect on Demand rule, or connect-on-startup.
    val connectSource: KapeConnectSource,
    // The user's protocol preference (e.g. "automatic", "wireguard") — not which protocol ends
    // up connected (see ConnectionEndEvent.effectiveProtocol).
    val selectedProtocol: String,
    val selectedLocationDescription: String?,
)

data class SessionEndEvent(
    val sessionId: UUID,
    val connectSource: KapeConnectSource,
    val wasEverConnected: Boolean,
    val reason: DisconnectReason,
    val durationMs: Long,
    val selectedProtocol: String,
    val selectedLocationDescription: String?,
)

data class ConnectionBeginEvent(
    val sessionId: UUID,
    val connectionId: UUID,
    val reason: ConnectReason,
    val selectedProtocol: String,
    val selectedLocationDescription: String?,
)

data class ConnectionEndEvent(
    val sessionId: UUID,
    val connectionId: UUID,
    val wasConnected: Boolean,
    val reason: DisconnectReason,
    val durationMs: Long,
    // vpnProtocolName/obfuscationDescription of the configuration that last connected within
    // this connection's span, or null if it never reached Connected.
    val effectiveProtocol: String?,
    val effectiveObfuscation: String?,
    val selectedProtocol: String,
    val selectedLocationDescription: String?,
)

data class AttemptBeginEvent(
    val sessionId: UUID,
    val connectionId: UUID,
    val attemptId: UUID,
    val configuration: VpnConfiguration,
    val selectedProtocol: String,
    val selectedLocationDescription: String?,
)

data class AttemptEndEvent(
    val attemptId: UUID,
    val result: AttemptResult,
    val elapsedMs: Long,
    val selectedProtocol: String,
    val selectedLocationDescription: String?,
)
