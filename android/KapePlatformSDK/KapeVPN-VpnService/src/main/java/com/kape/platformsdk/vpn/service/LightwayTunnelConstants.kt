package com.kape.platformsdk.vpn.service

// RFC6598 addresses, unlikely to collide with local ranges — same constants as the Apple SDK.
// Lightway's tunnel settings don't vary by server/location, which is what lets the network lock
// tunnel (KapeSystemTunnel.openNetworkLockTunnel()) share this exact shape — see
// KapeSystemTunnel.establish(): requesting the same settings while a matching tunnel is already
// active hands back the same interface with zero interruption, so swapping between the network
// lock and a Lightway connection (in either direction, including every Lightway reconnect) never
// tears down/re-creates the TUN interface at all. Kept here (KapeVPN-VpnService), not in
// KapeVPN-Lightway, so openNetworkLockTunnel() can reference the exact same values without a
// circular module dependency — and so there's a single source of truth, since the reuse mechanism
// depends on byte-for-byte equality between the two call sites.
//
// One exception: LightwayConnectionController substitutes the resolvers for LIGHTWAY_DNS_IP. Custom
// DNS is pre-armed into the network lock so reuse holds; an Advanced Protection resolver needs a
// fetch, so that session's first connect (and a resume) establishes for real. Reconnects reuse.
const val LIGHTWAY_LOCAL_IP = "100.64.100.2"
const val LIGHTWAY_LOCAL_IPV6 = "fd00:cafe:face::2"
const val LIGHTWAY_DNS_IP = "100.64.100.3"
const val LIGHTWAY_MTU = 1350
