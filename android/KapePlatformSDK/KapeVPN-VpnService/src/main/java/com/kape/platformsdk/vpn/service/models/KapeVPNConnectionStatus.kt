package com.kape.platformsdk.vpn.service.models

enum class KapeVPNConnectionStatus {
    Connected,
    Connecting,
    Reconnecting,
    Disconnected,
    Disconnecting,

    /** Tunnel process alive, traffic bypasses it. Auto-resumes after the pause duration. */
    Paused,
}

/**
 * Whether an outbound socket needs to be explicitly protect()-ed to bypass the tunnel. Both
 * terminal states already have connectivity without protection: [KapeVPNConnectionStatus.Disconnected]
 * has no tunnel to be captured by, and [KapeVPNConnectionStatus.Connected] has a fully-configured
 * one. [KapeVPNConnectionStatus.Paused] also needs no protection — pause tears the tunnel down
 * entirely so traffic already bypasses it. Any in-between state has a tunnel that may not yet (or
 * no longer) route traffic correctly, so a plain socket risks being captured or blackholed.
 */
fun KapeVPNConnectionStatus.needsProtection(): Boolean =
    this != KapeVPNConnectionStatus.Connected &&
        this != KapeVPNConnectionStatus.Disconnected &&
        this != KapeVPNConnectionStatus.Paused
