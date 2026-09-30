package com.kape.platformsdk.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRedactionTest {
    @Test
    fun redactsPublicIpv4() {
        assertEquals("addKey to <redacted>:1337", "addKey to 212.102.49.1:1337".redactPublicIps())
    }

    @Test
    fun keepsPrivateLoopbackLinkLocalAndCgnatIpv4() {
        val message = "10.1.2.3 172.16.0.5 172.31.255.1 192.168.1.1 127.0.0.1 169.254.1.1 100.64.100.2 0.0.0.0"
        assertEquals(message, message.redactPublicIps())
    }

    @Test
    fun redactsIpv4JustOutsidePrivateRanges() {
        assertEquals(
            "<redacted> <redacted> <redacted>",
            "172.32.0.1 100.128.0.1 192.169.0.1".redactPublicIps(),
        )
    }

    @Test
    fun redactsPublicIpv6() {
        assertEquals("server <redacted>", "server 2001:db8:85a3::8a2e:370:7334".redactPublicIps())
    }

    @Test
    fun keepsLoopbackUniqueLocalAndLinkLocalIpv6() {
        val message = "::1 fd00::1 fc12:3456::1 fe80::1"
        assertEquals(message, message.redactPublicIps())
    }

    @Test
    fun leavesTextWithoutAddressesUnchanged() {
        assertEquals("failed with status 401 Unauthorized", "failed with status 401 Unauthorized".redactPublicIps())
    }

    @Test
    fun doesNotTreatVersionNumbersAsAddresses() {
        assertEquals("version 1.2.3", "version 1.2.3".redactPublicIps())
    }
}
