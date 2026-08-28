package com.kape.platformsdk.vpn.service.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IpAddressTest {
    @Test
    fun `dotted-quad IPv4 literals are accepted`() {
        for (value in listOf("0.0.0.0", "1.1.1.1", "9.9.9.9", "10.0.0.1", "255.255.255.255")) {
            assertTrue(value.isDottedQuadIpv4(), "'$value' should be a valid resolver")
        }
    }

    @Test
    fun `anything that is not a strict dotted quad is rejected`() {
        val invalid =
            listOf(
                "",
                " ",
                "1.1.1",
                "1.1.1.1.1",
                "01.1.1.1",
                "256.1.1.1",
                "1.1.1.-1",
                "1.1.1.a",
                "1.1.1.1/32",
                "1.1.1.1 ",
                "2001:4860:4860::8888",
                "dns.example.com",
            )
        for (value in invalid) {
            assertFalse(value.isDottedQuadIpv4(), "'$value' should not reach the tunnel")
        }
    }

    @Test
    fun `non-ASCII digits are rejected rather than parsed`() {
        for (value in listOf("٣.1.1.1", "１.1.1.1")) {
            assertFalse(value.isDottedQuadIpv4(), "'$value' should not reach the tunnel")
        }
    }

    @Test
    fun `normalizedDnsServers trims, drops blanks and de-duplicates, preserving order`() {
        assertEquals(
            listOf("9.9.9.9", "1.1.1.1"),
            listOf(" 9.9.9.9 ", "", "1.1.1.1", "  ", "9.9.9.9").normalizedDnsServers(),
        )
    }
}
