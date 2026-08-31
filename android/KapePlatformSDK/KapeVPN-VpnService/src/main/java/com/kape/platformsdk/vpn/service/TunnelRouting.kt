package com.kape.platformsdk.vpn.service

import com.kape.platformsdk.vpn.service.models.KapeKillSwitchMode

internal val EXCLUDED_LOCAL_IPV4_RANGES =
    listOf(
        TunnelAddress("10.0.0.0", 8),
        TunnelAddress("172.16.0.0", 12),
        TunnelAddress("192.168.0.0", 16),
        TunnelAddress("169.254.0.0", 16),
        TunnelAddress("224.0.0.0", 24),
    )

// Computed once — EXCLUDED_LOCAL_IPV4_RANGES is fixed, so this never changes at runtime.
private val IPV4_ROUTES_EXCLUDING_LOCAL_RANGES: List<TunnelAddress> = subtractCidrRanges(EXCLUDED_LOCAL_IPV4_RANGES)

/**
 * Expands this route under [mode]'s kill-switch policy. Every route except the literal IPv4
 * default route (0.0.0.0/0) passes through unchanged — this is what lets it be applied uniformly
 * in [KapeSystemTunnel]'s `buildAndEstablish()` regardless of who requested the route (the
 * network-lock tunnel, Lightway/OpenVPN's hardcoded catch-all, or a WireGuard server's own
 * `AllowedIPs` if it happens to include one — any *other*, narrower route a split-tunnel server
 * sends passes through untouched).
 *
 * - [KapeKillSwitchMode.Advanced]: kept as-is — 0.0.0.0/0, no exceptions.
 * - [KapeKillSwitchMode.Off]/[KapeKillSwitchMode.Standard]: replaced with the minimal set of CIDR
 *   blocks covering 0.0.0.0/0 minus [EXCLUDED_LOCAL_IPV4_RANGES], so local/private-network traffic
 *   never gets routed into the VPN interface. `VpnService.Builder.excludeRoute()` needs API 33,
 *   above this SDK's minSDK, so exclusion is done by only including what's left instead.
 */
internal fun TunnelAddress.expandForKillSwitch(mode: KapeKillSwitchMode): List<TunnelAddress> =
    if (address == "0.0.0.0" && prefixLength == 0) {
        when (mode) {
            KapeKillSwitchMode.Advanced -> listOf(this)
            KapeKillSwitchMode.Off, KapeKillSwitchMode.Standard -> IPV4_ROUTES_EXCLUDING_LOCAL_RANGES
        }
    } else {
        listOf(this)
    }

/**
 * Minimal set of CIDR blocks covering 0.0.0.0/0 minus [excluded], via a binary-trie
 * split-and-prune: starting from 0.0.0.0/0, a node is kept whole if it doesn't overlap any
 * excluded range, dropped if fully contained in one, or split into its two `/prefix+1` children
 * and recursed into otherwise. Every branch terminates by `/32` at the latest — a single address
 * can never partially overlap a CIDR-aligned excluded range, so it's always either fully inside
 * (drop) or fully outside (keep). Sorted by start address so the result is a well-defined,
 * order-stable contract — route order feeds into [TunnelSettings] equality, which the tunnel
 * reuse mechanism in [KapeSystemTunnel] depends on.
 */
internal fun subtractCidrRanges(excluded: List<TunnelAddress>): List<TunnelAddress> {
    val excludedRanges = excluded.map { it.toIpv4Range() }
    val result = mutableListOf<TunnelAddress>()

    fun recurse(
        base: UInt,
        prefixLength: Int,
    ) {
        // ULong, not UInt: at prefixLength = 0 this needs a shift of 32, which silently wraps to
        // a no-op shift on a 32-bit UInt (shl masks the shift amount mod 32 on the JVM) instead of
        // producing 2^32.
        val size = 1uL shl (32 - prefixLength)
        val start = base.toULong()
        val end = start + size - 1uL
        if (excludedRanges.any { it.first <= start && end <= it.last }) return // That range is within an excluded range, we can stop here
        if (excludedRanges.none { start <= it.last && end >= it.first }) {
            // That range is outside an excluded range with no overlap, we add it to the list of included ranges
            result += TunnelAddress(base.toIpv4String(), prefixLength)
            return
        }
        // We have partial overlap from the range we are checking and the excluded ranges
        // We split the range into 2 (using the next prefix number) to get more precises ranges
        val childPrefix = prefixLength + 1
        val childBit = 1u shl (32 - childPrefix) // safe as UInt: childPrefix is always >= 1 here
        recurse(base, childPrefix)
        recurse(base or childBit, childPrefix)
    }

    recurse(0u, 0)
    return result.sortedBy { it.toIpv4Range().first }
}

private fun TunnelAddress.toIpv4Range(): ULongRange {
    val base = address.toIpv4UInt().toULong()
    val size = 1uL shl (32 - prefixLength)
    return base until (base + size)
}

private fun String.toIpv4UInt(): UInt = split(".").fold(0u) { acc, octet -> (acc shl 8) or octet.toUInt() }

private fun UInt.toIpv4String(): String =
    "${(this shr 24) and 0xFFu}.${(this shr 16) and 0xFFu}.${(this shr 8) and 0xFFu}.${this and 0xFFu}"
