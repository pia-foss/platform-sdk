package com.kape.platformsdk.vpn.wireguard

import com.kape.platformsdk.vpn.service.models.IpAddress
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WireGuardVpnConfigurationTest {
    @Test
    fun `every pre-seeded resolver is used, in order`() {
        val config =
            baseConfig(
                internalIp = "10.0.0.2/32",
                dnsServers = listOf("10.20.30.1", "8.8.8.8"),
            )

        assertEquals(listOf("10.20.30.1", "8.8.8.8"), config.resolvedDnsServers)
    }

    @Test
    fun `internalIp gateway convention is used when dnsServers is empty`() {
        val config =
            baseConfig(
                internalIp = "10.20.30.2/32",
                dnsServers = emptyList(),
            )

        assertEquals(listOf("10.20.0.1"), config.resolvedDnsServers)
    }

    @Test
    fun `no resolver can be derived when internalIp is invalid and dnsServers is empty`() {
        val config =
            baseConfig(
                internalIp = "",
                dnsServers = emptyList(),
            )

        assertTrue(config.resolvedDnsServers.isEmpty())
    }

    // AttemptBeginEvent carries the whole configuration into analytics debug logs — its
    // toString() must never surface key material.
    @Test
    fun `toString does not leak key material`() {
        val config =
            baseConfig(internalIp = "10.0.0.2/32", dnsServers = emptyList())
                .copy(
                    serverPublicKeyBase64 = "server-public-key-secret",
                    clientPrivateKeyBase64 = "client-private-key-secret",
                    presharedKeyBase64 = "preshared-key-secret",
                )

        val stringForm = config.toString()

        assertEquals("WireGuardVpnConfiguration", stringForm)
    }

    private fun baseConfig(
        internalIp: String,
        dnsServers: List<String>,
    ) = WireGuardVpnConfiguration(
        endpointConfiguration =
            WireGuardEndpointConfiguration(
                ip = IpAddress.V4("1.2.3.4"),
                port = 51820,
                authIp = IpAddress.V4("1.2.3.4"),
                authPort = 443,
                certDn = "test.example.com",
                obfuscation = WireGuardObfuscation.None,
            ),
        host = "1.2.3.4",
        port = 51820,
        obfuscation = WireGuardObfuscation.None,
        internalIp = internalIp,
        dnsServers = dnsServers,
    )
}
