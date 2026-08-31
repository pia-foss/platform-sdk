package com.kape.platformsdk.vpn.service.models

/**
 * Controls what happens to network traffic when the VPN connection drops unexpectedly, and which
 * routes claim the device's default route while connected. See
 * `docs/platform-sdks/android/vpn/network-protection.md`.
 *
 * This controls two related things: whether [com.kape.platformsdk.vpn.service.KapeSystemTunnel]
 * ever keeps a TUN interface alive beyond what a connection controller is actively using (which is
 * what prevents packets from leaking outside the tunnel during the connection or reconnection
 * process), and whether the VPN's `0.0.0.0/0` route excludes local/private IPv4 ranges so local
 * network communication (printers, NAS, etc.) keeps working outside the tunnel.
 *
 * - [Standard]: the default and recommended option. Avoids packets leaking during connection and
 *   reconnection by keeping a tunnel open at all times. Local network communication remains
 *   functional — the VPN's default route excludes local/private IPv4 ranges.
 * - [Off]: allows packets to be leaked during connection and reconnection. This is helpful if
 *   internet traffic is required to get connected (e.g. an HTTP call to fetch endpoints) but that
 *   traffic hasn't been `protect()`-ed to force it outside the VPN tunnel. Routing-wise, behaves
 *   like [Standard] — local/private IPv4 ranges are still excluded from the VPN's default route.
 * - [Advanced]: all traffic without exception is routed through the tunnel — the VPN's default
 *   route is a plain `0.0.0.0/0`, including local/private IPv4 ranges, which are blocked (not
 *   reachable outside the tunnel) whenever it's down. For high-security scenarios where any
 *   unprotected traffic — including on the local network — is unacceptable.
 */
enum class KapeKillSwitchMode {
    Off,
    Standard,
    Advanced,
}
