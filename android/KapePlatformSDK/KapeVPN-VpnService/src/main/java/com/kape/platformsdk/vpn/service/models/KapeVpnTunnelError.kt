package com.kape.platformsdk.vpn.service.models

/**
 * A tunnel-side failure the app has to act on, as opposed to transient endpoint failures the session
 * run loop retries silently. Published by `KapeVpnManager.lastTunnelError`.
 */
enum class KapeVpnTunnelError {
    /**
     * The OS refused to build the tunnel interface: this app is not the prepared VPN app — consent was
     * never granted, or another VPN app took it. Call `KapeVpnManager.prepare()` and launch the
     * returned Intent before connecting again.
     */
    VpnPermissionMissing,

    /** The user revoked the VPN from system settings, or another VPN app replaced this one. */
    VpnPermissionRevoked,

    /**
     * The Dedicated IP's assigned address expired, or the subscription was reset by support. Both need
     * the address renewed or reassigned.
     */
    DipExpired,

    /** The Dedicated IP server is offline for maintenance. */
    DipUnderMaintenance,

    /** The Dedicated IP server's health could not be determined, or no access token is cached. */
    DipUnavailable,

    /** A Dedicated IP connection was attempted on a protocol other than Lightway. */
    DipUnsupportedProtocol,

    /** The SDK license does not grant Dedicated IP. */
    DipNotEntitled,

    /**
     * Every endpoint for this location was excluded by the SDK license — its protocols, or its
     * obfuscation, are not granted. Distinct from a location that serves no endpoints at all.
     */
    EndpointsExcludedByLicense,
}
