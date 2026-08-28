package com.kape.platformsdk.vpn.openvpn

import org.junit.Test
import kotlin.test.assertEquals

class OpenVpnConfigurationTest {
    @Test
    fun `vpnProtocolName is openvpn`() {
        assertEquals("openvpn", baseConfig(xorValue = null).vpnProtocolName)
    }

    @Test
    fun `obfuscationDescription is xor when xorValue is set, none otherwise`() {
        assertEquals("xor", baseConfig(xorValue = 42L).obfuscationDescription)
        assertEquals("none", baseConfig(xorValue = null).obfuscationDescription)
    }

    // AttemptBeginEvent carries the whole configuration into analytics debug logs — its
    // toString() must never surface credentials/keys/certs.
    @Test
    fun `toString does not leak credentials or key material`() {
        val stringForm = baseConfig(xorValue = null).toString()

        assertEquals("OpenVpnConfiguration", stringForm)
    }

    private fun baseConfig(xorValue: Long?) =
        OpenVpnConfiguration(
            host = "1.2.3.4",
            port = 1194,
            transport = OpenVpnTransport.UDP,
            ovpnConfiguration = "client",
            xorValue = xorValue,
            mtu = 1500,
            certDn = "test.example.com",
            username = "test_user",
            password = "test_pass-secret",
            caCertificate = "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----",
            clientCertificate = "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----",
            clientKey = "-----BEGIN PRIVATE KEY-----\ntest-secret\n-----END PRIVATE KEY-----",
            tlsAuthKey = "tls-auth-key-secret",
        )
}
