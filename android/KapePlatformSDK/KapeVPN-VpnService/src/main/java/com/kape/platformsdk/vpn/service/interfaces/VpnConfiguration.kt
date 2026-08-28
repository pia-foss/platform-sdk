package com.kape.platformsdk.vpn.service.interfaces

// / Protocol-specific VPN configuration. Each protocol defines its own concrete type.
interface VpnConfiguration {
    // / Short protocol name for diagnostics/analytics (e.g. "wireguard").
    val vpnProtocolName: String

    // / Obfuscation mode, if any (e.g. "amnezia", "xor", "none").
    val obfuscationDescription: String get() = "none"

    // / Cipher in use, if applicable.
    val cipherDescription: String get() = "none"

    // / Whether this configuration is routed through an HTTPS proxy.
    val isProxied: Boolean get() = false
}
