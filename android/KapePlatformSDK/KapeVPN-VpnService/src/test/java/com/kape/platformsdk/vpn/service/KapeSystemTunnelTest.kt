package com.kape.platformsdk.vpn.service

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.kape.platformsdk.vpn.service.models.KapeKillSwitchMode
import com.kape.platformsdk.vpn.service.models.KapeSplitTunnelAppMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KapeSystemTunnelTest {
    private lateinit var systemTunnel: KapeSystemTunnel

    @Before
    fun setup() {
        systemTunnel = KapeSystemTunnel(mockk())
        mockkConstructor(VpnService.Builder::class)
    }

    @After
    fun tearDown() {
        unmockkConstructor(VpnService.Builder::class)
    }

    private fun fakePfd(): ParcelFileDescriptor {
        val pfd = mockk<ParcelFileDescriptor>()
        every { pfd.dup() } answers { mockk<ParcelFileDescriptor>(relaxed = true) }
        every { pfd.close() } returns Unit
        return pfd
    }

    private fun tunnel(session: String? = null) =
        systemTunnel
            .newBuilder()
            .apply { session?.let { setSession(it) } }
            .addAddress(LIGHTWAY_LOCAL_IP, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(LIGHTWAY_DNS_IP)
            .setMtu(LIGHTWAY_MTU)

    @Test
    fun `requesting the same settings twice reuses the tunnel — a single real establish() call`() =
        runTest {
            val established = fakePfd()
            every { anyConstructed<VpnService.Builder>().establish() } returns established

            val fd1 = tunnel().establish()
            val fd2 = tunnel().establish()

            assertNotNull(fd1)
            assertNotNull(fd2)
            assertNotSame(fd1, fd2) // each caller gets its own independent dup()
            verify(exactly = 1) { anyConstructed<VpnService.Builder>().establish() }
        }

    @Test
    fun `setSession does not affect the reuse decision`() =
        runTest {
            val established = fakePfd()
            every { anyConstructed<VpnService.Builder>().establish() } returns established

            tunnel(session = "First").establish()
            tunnel(session = "Second").establish()

            verify(exactly = 1) { anyConstructed<VpnService.Builder>().establish() }
        }

    @Test
    fun `different settings close the previous tunnel and establish a new one`() =
        runTest {
            val first = fakePfd()
            val second = fakePfd()
            every { anyConstructed<VpnService.Builder>().establish() } returns first andThen second

            tunnel().establish()
            systemTunnel
                .newBuilder()
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.0.0.1")
                .setMtu(1280)
                .establish()

            verify(exactly = 2) { anyConstructed<VpnService.Builder>().establish() }
            verify(exactly = 1) { first.close() }
        }

    @Test
    fun `the new tunnel is established before the previous one is closed`() =
        runTest {
            val events = mutableListOf<String>()
            val first = fakePfd()
            val second = fakePfd()
            every { first.close() } answers {
                events += "close"
                Unit
            }
            var establishCount = 0
            every { anyConstructed<VpnService.Builder>().establish() } answers {
                establishCount++
                events += "establish"
                if (establishCount == 1) first else second
            }

            tunnel().establish()
            systemTunnel
                .newBuilder()
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.0.0.1")
                .setMtu(1280)
                .establish()

            // Shrinks the leak window: the new interface supersedes the old one at the OS level
            // as soon as the second establish() succeeds, so there's no point where zero
            // interfaces are registered — closing the old fd afterwards only releases resources.
            assertEquals(listOf("establish", "establish", "close"), events)
        }

    @Test
    fun `a failed establish leaves the previous tunnel untouched and still reusable`() =
        runTest {
            val first = fakePfd()
            every { anyConstructed<VpnService.Builder>().establish() } returns first andThen null

            val fd1 = tunnel().establish()
            val failedFd =
                systemTunnel
                    .newBuilder()
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("10.0.0.1")
                    .setMtu(1280)
                    .establish()
            val fd2 = tunnel().establish() // same settings as fd1 — should still reuse

            assertNotNull(fd1)
            assertNull(failedFd)
            assertNotNull(fd2)
            verify(exactly = 0) { first.close() }
            verify(exactly = 2) { anyConstructed<VpnService.Builder>().establish() } // fd1's + the failed one — fd2 reused, no 3rd call
        }

    @Test
    fun `a failed establish returns null and does not mark anything as current`() =
        runTest {
            every { anyConstructed<VpnService.Builder>().establish() } returns null

            val fd = tunnel().establish()

            assertNull(fd)
        }

    @Test
    fun `openNetworkLockTunnel establishes the Lightway-shaped tunnel and closes its own extra dup`() =
        runTest {
            val established = fakePfd()
            val dup = mockk<ParcelFileDescriptor>(relaxed = true)
            every { established.dup() } returns dup
            every { anyConstructed<VpnService.Builder>().establish() } returns established

            val result = systemTunnel.openNetworkLockTunnel()

            assertTrue(result)
            verify(exactly = 1) { anyConstructed<VpnService.Builder>().establish() }
            // Nothing needs to keep this dup alive — KapeSystemTunnel's own establish() already
            // retains the master; leaking it would keep the OS-level VPN registration alive even
            // after a later closeCurrentTunnel().
            verify(exactly = 1) { dup.close() }
            verify(exactly = 0) { established.close() }
        }

    @Test
    fun `calling openNetworkLockTunnel twice reuses the same tunnel`() =
        runTest {
            val established = fakePfd()
            every { anyConstructed<VpnService.Builder>().establish() } returns established

            systemTunnel.openNetworkLockTunnel()
            systemTunnel.openNetworkLockTunnel()

            verify(exactly = 1) { anyConstructed<VpnService.Builder>().establish() }
        }

    @Test
    fun `openNetworkLockTunnel returns false when establish fails`() =
        runTest {
            every { anyConstructed<VpnService.Builder>().establish() } returns null

            val result = systemTunnel.openNetworkLockTunnel()

            assertFalse(result)
        }

    @Test
    fun `a Lightway-shaped connect after openNetworkLockTunnel reuses the same tunnel`() =
        runTest {
            val established = fakePfd()
            every { anyConstructed<VpnService.Builder>().establish() } returns established

            systemTunnel.openNetworkLockTunnel()
            val lightwayFd =
                systemTunnel
                    .newBuilder()
                    .addAddress(LIGHTWAY_LOCAL_IP, 32)
                    .addRoute("0.0.0.0", 0)
                    .addAddress(LIGHTWAY_LOCAL_IPV6, 128)
                    .addRoute("::", 0)
                    .addDnsServer(LIGHTWAY_DNS_IP)
                    .setMtu(LIGHTWAY_MTU)
                    .establish()

            assertNotNull(lightwayFd)
            verify(exactly = 1) { anyConstructed<VpnService.Builder>().establish() }
        }

    @Test
    fun `a Lightway connect with an Advanced Protection DNS does not reuse the network lock tunnel`() =
        runTest {
            // The resolver replaces LIGHTWAY_DNS_IP, so the settings no longer match what
            // openNetworkLockTunnel() established. The second establish() is expected.
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            systemTunnel.openNetworkLockTunnel()
            val lightwayFd =
                systemTunnel
                    .newBuilder()
                    .addAddress(LIGHTWAY_LOCAL_IP, 32)
                    .addRoute("0.0.0.0", 0)
                    .addAddress(LIGHTWAY_LOCAL_IPV6, 128)
                    .addRoute("::", 0)
                    .addDnsServer("10.20.30.1")
                    .setMtu(LIGHTWAY_MTU)
                    .establish()

            assertNotNull(lightwayFd)
            verify(exactly = 2) { anyConstructed<VpnService.Builder>().establish() }
        }

    @Test
    fun `a Lightway connect with custom DNS still reuses the pre-armed network lock tunnel`() =
        runTest {
            systemTunnel.customDnsServers = listOf("1.1.1.1", "9.9.9.9")
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            systemTunnel.openNetworkLockTunnel()
            val lightwayFd =
                systemTunnel
                    .newBuilder()
                    .addAddress(LIGHTWAY_LOCAL_IP, 32)
                    .addRoute("0.0.0.0", 0)
                    .addAddress(LIGHTWAY_LOCAL_IPV6, 128)
                    .addRoute("::", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("9.9.9.9")
                    .setMtu(LIGHTWAY_MTU)
                    .establish()

            assertNotNull(lightwayFd)
            verify(exactly = 1) { anyConstructed<VpnService.Builder>().establish() }
        }

    @Test
    fun `a resolver list the network lock did not pre-arm costs one extra establish`() =
        runTest {
            systemTunnel.customDnsServers = listOf("1.1.1.1")
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            systemTunnel.openNetworkLockTunnel()
            systemTunnel
                .newBuilder()
                .addAddress(LIGHTWAY_LOCAL_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addAddress(LIGHTWAY_LOCAL_IPV6, 128)
                .addRoute("::", 0)
                .addDnsServer("9.9.9.9")
                .setMtu(LIGHTWAY_MTU)
                .establish()

            verify(exactly = 2) { anyConstructed<VpnService.Builder>().establish() }
        }

    @Test
    fun `every DNS server gets a host route so it survives the local-range exclusion`() =
        runTest {
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            systemTunnel
                .newBuilder()
                .addAddress(LIGHTWAY_LOCAL_IP, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("9.9.9.9")
                .setMtu(LIGHTWAY_MTU)
                .establish()

            verify { anyConstructed<VpnService.Builder>().addRoute("1.1.1.1", 32) }
            verify { anyConstructed<VpnService.Builder>().addRoute("9.9.9.9", 32) }
        }

    @Test
    fun `openNetworkLockTunnel is a no-op under KapeKillSwitchMode Off`() =
        runTest {
            systemTunnel.killSwitchMode = KapeKillSwitchMode.Off
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            val result = systemTunnel.openNetworkLockTunnel()

            assertTrue(result)
            verify(exactly = 0) { anyConstructed<VpnService.Builder>().establish() }
        }

    @Test
    fun `establish hands back the real descriptor, not a dup, under KapeKillSwitchMode Off`() =
        runTest {
            systemTunnel.killSwitchMode = KapeKillSwitchMode.Off
            val established = fakePfd()
            every { anyConstructed<VpnService.Builder>().establish() } returns established

            val fd = tunnel().establish()

            assertSame(established, fd)
            verify(exactly = 0) { established.dup() }
        }

    @Test
    fun `establish never retains or reuses under KapeKillSwitchMode Off — every call is a fresh establish()`() =
        runTest {
            systemTunnel.killSwitchMode = KapeKillSwitchMode.Off
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd() andThen fakePfd()

            tunnel().establish()
            tunnel().establish()

            verify(exactly = 2) { anyConstructed<VpnService.Builder>().establish() }
        }

    @Test
    fun `establish passes a 0_0_0_0-0 route through unchanged under KapeKillSwitchMode Advanced`() =
        runTest {
            systemTunnel.killSwitchMode = KapeKillSwitchMode.Advanced
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            tunnel().establish() // includes one DNS server, which also gets its own host route

            verify(exactly = 2) { anyConstructed<VpnService.Builder>().addRoute(any<String>(), any<Int>()) }
            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addRoute("0.0.0.0", 0) }
            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addRoute(LIGHTWAY_DNS_IP, 32) }
        }

    @Test
    fun `establish expands a 0_0_0_0-0 route into the local-range exclusion set under Standard`() =
        runTest {
            // systemTunnel defaults to KapeKillSwitchMode.Standard.
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            tunnel().establish() // includes one DNS server, which also gets its own host route

            verify(exactly = 61) { anyConstructed<VpnService.Builder>().addRoute(any<String>(), any<Int>()) }
            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addRoute(LIGHTWAY_DNS_IP, 32) }
            verify(exactly = 0) { anyConstructed<VpnService.Builder>().addRoute("10.0.0.0", 8) }
            verify(exactly = 0) { anyConstructed<VpnService.Builder>().addRoute("172.16.0.0", 12) }
            verify(exactly = 0) { anyConstructed<VpnService.Builder>().addRoute("192.168.0.0", 16) }
            verify(exactly = 0) { anyConstructed<VpnService.Builder>().addRoute("169.254.0.0", 16) }
            verify(exactly = 0) { anyConstructed<VpnService.Builder>().addRoute("224.0.0.0", 24) }
        }

    @Test
    fun `establish expands a 0_0_0_0-0 route the same way under Off`() =
        runTest {
            systemTunnel.killSwitchMode = KapeKillSwitchMode.Off
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            tunnel().establish() // includes one DNS server, which also gets its own host route

            verify(exactly = 61) { anyConstructed<VpnService.Builder>().addRoute(any<String>(), any<Int>()) }
        }

    @Test
    fun `establish leaves a non-default route unchanged regardless of kill switch mode`() =
        runTest {
            systemTunnel.killSwitchMode = KapeKillSwitchMode.Advanced
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            systemTunnel
                .newBuilder()
                .addAddress("10.0.0.2", 32)
                .addRoute("192.0.2.1", 32)
                .addDnsServer("10.0.0.1")
                .setMtu(1280)
                .establish()

            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addRoute("192.0.2.1", 32) }
        }

    @Test
    fun `a DNS server inside an excluded local range still gets an explicit host route under Standard`() =
        runTest {
            // systemTunnel defaults to KapeKillSwitchMode.Standard. 10.159.0.1 falls inside the
            // excluded 10.0.0.0/8 range, matching a real WireGuard/OpenVPN server's own DNS —
            // without a host route it would be unreachable through the tunnel once 10.0.0.0/8 is
            // excluded from the broader route list, breaking DNS resolution entirely.
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            systemTunnel
                .newBuilder()
                .addAddress("10.159.0.3", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.159.0.1")
                .setMtu(1280)
                .establish()

            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addRoute("10.159.0.1", 32) }
        }

    @Test
    fun `an IPv6 DNS server gets a _-128 host route`() =
        runTest {
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            systemTunnel
                .newBuilder()
                .addAddress(LIGHTWAY_LOCAL_IPV6, 128)
                .addRoute("::", 0)
                .addDnsServer("fd00:cafe:face::3")
                .setMtu(1280)
                .establish()

            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addRoute("fd00:cafe:face::3", 128) }
        }

    @Test
    fun `splitTunnelAppMode Off does not call addAllowedApplication or addDisallowedApplication`() =
        runTest {
            // systemTunnel defaults to KapeSplitTunnelAppMode.Off.
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            tunnel().establish()

            verify(exactly = 0) { anyConstructed<VpnService.Builder>().addAllowedApplication(any()) }
            verify(exactly = 0) { anyConstructed<VpnService.Builder>().addDisallowedApplication(any()) }
        }

    @Test
    fun `splitTunnelAppMode Allow calls addAllowedApplication once per package`() =
        runTest {
            systemTunnel.splitTunnelAppMode = KapeSplitTunnelAppMode.Allow(listOf("a", "b"))
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            tunnel().establish()

            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addAllowedApplication("a") }
            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addAllowedApplication("b") }
            verify(exactly = 0) { anyConstructed<VpnService.Builder>().addDisallowedApplication(any()) }
        }

    @Test
    fun `splitTunnelAppMode Disallow calls addDisallowedApplication once per package`() =
        runTest {
            systemTunnel.splitTunnelAppMode = KapeSplitTunnelAppMode.Disallow(listOf("a"))
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            tunnel().establish()

            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addDisallowedApplication("a") }
            verify(exactly = 0) { anyConstructed<VpnService.Builder>().addAllowedApplication(any()) }
        }

    @Test
    fun `duplicate packages in splitTunnelAppMode Allow are only applied once`() =
        runTest {
            systemTunnel.splitTunnelAppMode = KapeSplitTunnelAppMode.Allow(listOf("a", "a"))
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            tunnel().establish()

            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addAllowedApplication("a") }
        }

    @Test
    fun `an unknown package in splitTunnelAppMode Allow is skipped without blocking the rest or establish()`() =
        runTest {
            systemTunnel.splitTunnelAppMode = KapeSplitTunnelAppMode.Allow(listOf("bad", "good"))
            every { anyConstructed<VpnService.Builder>().addAllowedApplication("bad") } throws PackageManager.NameNotFoundException()
            val established = fakePfd()
            every { anyConstructed<VpnService.Builder>().establish() } returns established

            val fd = tunnel().establish()

            assertNotNull(fd)
            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addAllowedApplication("good") }
        }

    @Test
    fun `openNetworkLockTunnel also applies the current splitTunnelAppMode`() =
        runTest {
            systemTunnel.splitTunnelAppMode = KapeSplitTunnelAppMode.Disallow(listOf("x"))
            every { anyConstructed<VpnService.Builder>().establish() } returns fakePfd()

            systemTunnel.openNetworkLockTunnel()

            verify(exactly = 1) { anyConstructed<VpnService.Builder>().addDisallowedApplication("x") }
        }
}
