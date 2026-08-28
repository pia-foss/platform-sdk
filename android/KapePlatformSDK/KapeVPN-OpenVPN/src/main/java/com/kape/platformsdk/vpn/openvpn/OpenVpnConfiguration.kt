package com.kape.platformsdk.vpn.openvpn

import com.kape.platformsdk.vpn.service.interfaces.VpnConfiguration

enum class OpenVpnTransport {
    UDP,
    TCP,
}

data class OpenVpnConfiguration(
    val host: String,
    val port: Int,
    val transport: OpenVpnTransport,
    val ovpnConfiguration: String,
    val xorValue: Long?,
    val mtu: Int,
    val certDn: String,
    val username: String,
    val password: String,
    val caCertificate: String,
    val clientCertificate: String,
    val clientKey: String,
    val tlsAuthKey: String,
    val dnsServers: List<String> = emptyList(),
) : VpnConfiguration {
    override val vpnProtocolName: String = "openvpn"

    override val obfuscationDescription: String get() = if (xorValue != null) "xor" else "none"

    override fun toString(): String = "OpenVpnConfiguration"
}
