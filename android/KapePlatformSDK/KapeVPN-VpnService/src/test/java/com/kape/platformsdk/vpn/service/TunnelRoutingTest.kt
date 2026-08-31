package com.kape.platformsdk.vpn.service

import com.kape.platformsdk.vpn.service.models.KapeKillSwitchMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Independent, test-side IPv4 range math — deliberately not reusing TunnelRouting.kt's private
// helpers, so a bug in the production conversion logic can't also hide itself from these checks.
private fun String.toUIntIpv4(): UInt = split(".").fold(0u) { acc, octet -> (acc shl 8) or octet.toUInt() }

private fun TunnelAddress.range(): ULongRange {
    val base = address.toUIntIpv4().toULong()
    val size = 1uL shl (32 - prefixLength)
    return base until (base + size)
}

private fun List<TunnelAddress>.covers(ip: String): Boolean {
    val value = ip.toUIntIpv4().toULong()
    return any { value in it.range() }
}

private fun List<TunnelAddress>.hasSelfOverlaps(): Boolean {
    val ranges = map { it.range() }
    for (i in ranges.indices) {
        for (j in i + 1 until ranges.size) {
            if (ranges[i].first <= ranges[j].last && ranges[j].first <= ranges[i].last) return true
        }
    }
    return false
}

class TunnelRoutingTest {
    @Test
    fun `no excluded ranges returns the whole default route unchanged`() {
        val result = subtractCidrRanges(emptyList())

        assertEquals(listOf(TunnelAddress("0.0.0.0", 0)), result)
    }

    @Test
    fun `excluding a single range produces a covering, non-overlapping, aligned set`() {
        val excluded = listOf(TunnelAddress("10.0.0.0", 8))
        val result = subtractCidrRanges(excluded)

        assertNoOverlapWithExcluded(result, excluded)
        assertFalse(result.hasSelfOverlaps())
        assertAllAligned(result)
        assertFullCoverage(result, excluded)
    }

    @Test
    fun `excluding two adjacent ranges produces a covering, non-overlapping, aligned set`() {
        val excluded = listOf(TunnelAddress("10.0.0.0", 9), TunnelAddress("10.128.0.0", 9))
        val result = subtractCidrRanges(excluded)

        assertNoOverlapWithExcluded(result, excluded)
        assertFalse(result.hasSelfOverlaps())
        assertAllAligned(result)
        assertFullCoverage(result, excluded)
    }

    @Test
    fun `excluding the real 5 local IPv4 ranges produces exactly 60 blocks`() {
        val result = subtractCidrRanges(EXCLUDED_LOCAL_IPV4_RANGES)

        assertEquals(60, result.size)
        assertNoOverlapWithExcluded(result, EXCLUDED_LOCAL_IPV4_RANGES)
        assertFalse(result.hasSelfOverlaps())
        assertAllAligned(result)
        assertFullCoverage(result, EXCLUDED_LOCAL_IPV4_RANGES)
    }

    @Test
    fun `result is sorted by start address ascending`() {
        val result = subtractCidrRanges(EXCLUDED_LOCAL_IPV4_RANGES)

        val starts = result.map { it.range().first }
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun `excluded ranges are never covered`() {
        val result = subtractCidrRanges(EXCLUDED_LOCAL_IPV4_RANGES)

        assertFalse(result.covers("10.1.2.3"))
        assertFalse(result.covers("172.20.0.5"))
        assertFalse(result.covers("192.168.1.1"))
        assertFalse(result.covers("169.254.1.1"))
        assertFalse(result.covers("224.0.0.5"))
    }

    @Test
    fun `addresses just outside each excluded range are covered`() {
        val result = subtractCidrRanges(EXCLUDED_LOCAL_IPV4_RANGES)

        assertTrue(result.covers("9.255.255.255")) // just below 10.0.0.0/8
        assertTrue(result.covers("11.0.0.0")) // just above 10.0.0.0/8
        assertTrue(result.covers("172.15.255.255")) // just below 172.16.0.0/12
        assertTrue(result.covers("172.32.0.0")) // just above 172.16.0.0/12
        assertTrue(result.covers("192.167.255.255")) // just below 192.168.0.0/16
        assertTrue(result.covers("192.169.0.0")) // just above 192.168.0.0/16
        assertTrue(result.covers("169.253.255.255")) // just below 169.254.0.0/16
        assertTrue(result.covers("169.255.0.0")) // just above 169.254.0.0/16
        assertTrue(result.covers("223.255.255.255")) // just below 224.0.0.0/24
        assertTrue(result.covers("224.0.1.0")) // just above 224.0.0.0/24
    }

    @Test
    fun `addresses at each excluded range's own boundaries are not covered`() {
        val result = subtractCidrRanges(EXCLUDED_LOCAL_IPV4_RANGES)

        assertFalse(result.covers("10.0.0.0"))
        assertFalse(result.covers("10.255.255.255"))
        assertFalse(result.covers("172.16.0.0"))
        assertFalse(result.covers("172.31.255.255"))
        assertFalse(result.covers("192.168.0.0"))
        assertFalse(result.covers("192.168.255.255"))
        assertFalse(result.covers("169.254.0.0"))
        assertFalse(result.covers("169.254.255.255"))
        assertFalse(result.covers("224.0.0.0"))
        assertFalse(result.covers("224.0.0.255"))
    }

    @Test
    fun `well-known public addresses are covered`() {
        val result = subtractCidrRanges(EXCLUDED_LOCAL_IPV4_RANGES)

        assertTrue(result.covers("8.8.8.8"))
        assertTrue(result.covers("1.1.1.1"))
    }

    @Test
    fun `expandForKillSwitch keeps 0_0_0_0-0 unchanged under Advanced`() {
        val route = TunnelAddress("0.0.0.0", 0)

        val expanded = route.expandForKillSwitch(KapeKillSwitchMode.Advanced)

        assertEquals(listOf(route), expanded)
    }

    @Test
    fun `expandForKillSwitch produces the same exclusion set under Off and Standard`() {
        val route = TunnelAddress("0.0.0.0", 0)

        val off = route.expandForKillSwitch(KapeKillSwitchMode.Off)
        val standard = route.expandForKillSwitch(KapeKillSwitchMode.Standard)

        assertEquals(off, standard)
        assertEquals(60, off.size)
    }

    @Test
    fun `expandForKillSwitch leaves non-default routes unchanged under every mode`() {
        val route = TunnelAddress("192.0.2.1", 32)

        KapeKillSwitchMode.entries.forEach { mode ->
            assertEquals(listOf(route), route.expandForKillSwitch(mode))
        }
    }

    private fun assertNoOverlapWithExcluded(
        result: List<TunnelAddress>,
        excluded: List<TunnelAddress>,
    ) {
        val excludedRanges = excluded.map { it.range() }
        result.forEach { block ->
            val range = block.range()
            assertFalse(
                excludedRanges.any { it.first <= range.last && range.first <= it.last },
                "block $block overlaps an excluded range",
            )
        }
    }

    private fun assertAllAligned(result: List<TunnelAddress>) {
        result.forEach { block ->
            val size = 1uL shl (32 - block.prefixLength)
            val start = block.address.toUIntIpv4().toULong()
            assertEquals(0uL, start % size, "block $block is not aligned to its own prefix length")
        }
    }

    private fun assertFullCoverage(
        result: List<TunnelAddress>,
        excluded: List<TunnelAddress>,
    ) {
        val coveredSize = result.sumOf { 1uL shl (32 - it.prefixLength) }
        val excludedSize = excluded.sumOf { 1uL shl (32 - it.prefixLength) }
        assertEquals(1uL shl 32, coveredSize + excludedSize)
    }
}
