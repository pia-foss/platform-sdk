package com.kape.platformsdk.vpn.service.models

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KapeSplitTunnelAppModeTest {
    @Test
    fun `addingPackage on Off returns null`() {
        assertNull(KapeSplitTunnelAppMode.Off.addingPackage("com.example.app"))
    }

    @Test
    fun `removingPackage on Off returns null`() {
        assertNull(KapeSplitTunnelAppMode.Off.removingPackage("com.example.app"))
    }

    @Test
    fun `resettingPackages on Off returns null`() {
        assertNull(KapeSplitTunnelAppMode.Off.resettingPackages())
    }

    @Test
    fun `addingPackage appends to Allow`() {
        val result = KapeSplitTunnelAppMode.Allow(listOf("a")).addingPackage("b")

        assertEquals(KapeSplitTunnelAppMode.Allow(listOf("a", "b")), result)
    }

    @Test
    fun `addingPackage appends to Disallow`() {
        val result = KapeSplitTunnelAppMode.Disallow(listOf("a")).addingPackage("b")

        assertEquals(KapeSplitTunnelAppMode.Disallow(listOf("a", "b")), result)
    }

    @Test
    fun `addingPackage dedupes an already-present package`() {
        val result = KapeSplitTunnelAppMode.Allow(listOf("a", "b")).addingPackage("a")

        assertEquals(KapeSplitTunnelAppMode.Allow(listOf("a", "b")), result)
    }

    @Test
    fun `removingPackage removes from Allow`() {
        val result = KapeSplitTunnelAppMode.Allow(listOf("a", "b")).removingPackage("a")

        assertEquals(KapeSplitTunnelAppMode.Allow(listOf("b")), result)
    }

    @Test
    fun `removingPackage removes from Disallow`() {
        val result = KapeSplitTunnelAppMode.Disallow(listOf("a", "b")).removingPackage("a")

        assertEquals(KapeSplitTunnelAppMode.Disallow(listOf("b")), result)
    }

    @Test
    fun `removingPackage is a no-op when the package isn't present`() {
        val result = KapeSplitTunnelAppMode.Allow(listOf("a")).removingPackage("z")

        assertEquals(KapeSplitTunnelAppMode.Allow(listOf("a")), result)
    }

    @Test
    fun `resettingPackages clears Allow while keeping the mode`() {
        val result = KapeSplitTunnelAppMode.Allow(listOf("a", "b")).resettingPackages()

        assertEquals(KapeSplitTunnelAppMode.Allow(emptyList()), result)
    }

    @Test
    fun `resettingPackages clears Disallow while keeping the mode`() {
        val result = KapeSplitTunnelAppMode.Disallow(listOf("a", "b")).resettingPackages()

        assertEquals(KapeSplitTunnelAppMode.Disallow(emptyList()), result)
    }
}
