package com.kape.platformsdk.vpn.wireguard

import com.kape.platformsdk.vpn.service.interfaces.VpnConfiguration
import com.kape.platformsdk.vpn.service.models.IpAddress

data class WireGuardVpnConfiguration(
    val endpointConfiguration: WireGuardEndpointConfiguration,
    val host: String,
    val port: Int,
    val obfuscation: WireGuardObfuscation,
    // Auth fields — populated by the authentication step
    val serverPublicKeyBase64: String = "",
    val clientPrivateKeyBase64: String = "",
    val presharedKeyBase64: String? = null,
    val internalIp: String = "",
    val allowedIPs: List<String> = DEFAULT_ALLOWED_IPS,
    val mtu: Int = DEFAULT_MTU,
    val gatewayIp: IpAddress? = null,
    // Pre-seeded: custom DNS or the filtering DNS. Empty → derive from `internalIp`.
    val dnsServers: List<String> = emptyList(),
) : VpnConfiguration {
    override val vpnProtocolName: String = "wireguard"

    val resolvedDnsServers: List<String>
        get() = dnsServers.ifEmpty { listOfNotNull(transformToDns(internalIp)) }

    @Deprecated("Collapses a multi-resolver list. Use resolvedDnsServers.", ReplaceWith("resolvedDnsServers.firstOrNull()"))
    val dns: String?
        get() = resolvedDnsServers.firstOrNull()

    val protocolDescription: String
        get() =
            when (obfuscation) {
                is WireGuardObfuscation.None -> "WireGuard"
                is WireGuardObfuscation.Amnezia -> "WireGuard+Amnezia"
                is WireGuardObfuscation.Unknown -> "WireGuard+Unknown"
            }

    override val obfuscationDescription: String
        get() =
            when (obfuscation) {
                is WireGuardObfuscation.None -> "none"
                is WireGuardObfuscation.Amnezia -> "amnezia"
                is WireGuardObfuscation.Unknown -> "unknown"
            }

    override fun toString(): String = "WireGuardVpnConfiguration"

    companion object {
        val DEFAULT_ALLOWED_IPS: List<String> = listOf("0.0.0.0/0", "::/0")
        const val DEFAULT_MTU: Int = 1280
    }
}

private fun transformToDns(ipAddress: String): String? {
    val addressString = ipAddress.substringBefore("/")
    val components = addressString.split(".").mapNotNull { it.toIntOrNull() }
    if (components.size != 4 || components.any { it !in 0..255 }) return null
    return "${components[0]}.${components[1]}.0.1"
}

data class WireGuardEndpointConfiguration(
    val ip: IpAddress,
    val port: Int,
    val authIp: IpAddress,
    val authPort: Int,
    val certDn: String,
    val obfuscation: WireGuardObfuscation,
) {
    override fun toString(): String = "WireGuardEndpointConfiguration"
}

sealed class WireGuardObfuscation {
    object None : WireGuardObfuscation()

    data class Amnezia(
        val initPacketJunkSize: Long,
        val responsePacketJunkSize: Long,
        val junkPacketCount: Long,
        val junkPacketMinSize: Long,
        val junkPacketMaxSize: Long,
        val initPacketMagicHeader: Long,
        val responsePacketMagicHeader: Long,
        val underloadPacketMagicHeader: Long,
        val transportPacketMagicHeader: Long,
    ) : WireGuardObfuscation()

    object Unknown : WireGuardObfuscation()
}

data class WireGuardAuthConfiguration(
    val psk: String,
    val serverPublicKey: String,
    val clientPrivateKey: String,
    val internalIp: String,
    val dnsServers: List<String> = emptyList(),
    val gatewayIp: IpAddress? = null,
    // Null means the authenticator has no opinion — the pre-authentication configuration's own
    // obfuscation setting is kept. Lets an authenticator that learns real obfuscation parameters
    // as part of authenticating (e.g. AmneziaWG's addKey response) apply them to this same
    // connection attempt instead of only the next one.
    val obfuscation: WireGuardObfuscation? = null,
) {
    override fun toString(): String = "WireGuardAuthConfiguration"
}
