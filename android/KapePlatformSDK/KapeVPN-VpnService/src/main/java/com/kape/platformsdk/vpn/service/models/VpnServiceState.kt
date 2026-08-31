package com.kape.platformsdk.vpn.service.models

import kotlinx.coroutines.flow.MutableStateFlow

class VpnServiceState {
    val connectionStatus = MutableStateFlow(KapeVPNConnectionStatus.Disconnected)
    val trafficStats = MutableStateFlow(KapeVpnTrafficStats.ZERO)

    /** Wall-clock instant (epoch millis) the active pause ends, or `null` when not paused. */
    val pausedUntil = MutableStateFlow<Long?>(null)

    /** Last actionable tunnel failure, or `null`. Survives the run loop's retry backoff. */
    val lastTunnelError = MutableStateFlow<KapeVpnTunnelError?>(null)
}
