package com.kape.platformsdk.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KapeNetworkConnectivityMonitorTest {
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private val callbackSlot = slot<ConnectivityManager.NetworkCallback>()

    @Before
    fun setup() {
        connectivityManager = mockk(relaxed = true)
        context = mockk()
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivityManager
        every {
            connectivityManager.registerNetworkCallback(any<NetworkRequest>(), capture(callbackSlot))
        } just Runs
    }

    private fun capabilities(
        validated: Boolean,
        isVpn: Boolean = false,
    ): NetworkCapabilities {
        val capabilities = mockk<NetworkCapabilities>()
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns isVpn
        return capabilities
    }

    @Test
    fun `defaults to online before the first callback lands`() {
        val monitor = KapeNetworkConnectivityMonitor(context)
        assertTrue(monitor.isOnline)
    }

    @Test
    fun `start registers a network callback exactly once, even if called twice`() {
        val monitor = KapeNetworkConnectivityMonitor(context)

        monitor.start()
        monitor.start()

        verify(exactly = 1) {
            connectivityManager.registerNetworkCallback(any<NetworkRequest>(), any<ConnectivityManager.NetworkCallback>())
        }
    }

    @Test
    fun `reports offline once a network reports unvalidated capabilities`() {
        val monitor = KapeNetworkConnectivityMonitor(context)
        monitor.start()

        callbackSlot.captured.onCapabilitiesChanged(mockk<Network>(), capabilities(validated = false))

        assertFalse(monitor.isOnline)
    }

    @Test
    fun `reports online once a non-VPN network is validated`() {
        val monitor = KapeNetworkConnectivityMonitor(context)
        monitor.start()

        callbackSlot.captured.onCapabilitiesChanged(mockk<Network>(), capabilities(validated = true, isVpn = false))

        assertTrue(monitor.isOnline)
    }

    @Test
    fun `a validated VPN network alone does not count as online`() {
        // Regression test: this monitor runs inside the same process that owns the VPN tunnel
        // (including the network-lock "kill switch" tunnel kept up between connections). That
        // self-owned VPN network can report itself validated independently of whether the real
        // underlying network (Wi-Fi/cellular) is actually up — confirmed on-device, where
        // disabling Wi-Fi while connected left the VPN's own network still reporting validated.
        val monitor = KapeNetworkConnectivityMonitor(context)
        monitor.start()

        callbackSlot.captured.onCapabilitiesChanged(mockk<Network>(), capabilities(validated = true, isVpn = true))

        assertFalse(monitor.isOnline)
    }

    @Test
    fun `reports offline when the underlying network is lost, even though the VPN network is still validated`() {
        val monitor = KapeNetworkConnectivityMonitor(context)
        monitor.start()
        val vpnNetwork = mockk<Network>()
        val wifiNetwork = mockk<Network>()
        callbackSlot.captured.onCapabilitiesChanged(vpnNetwork, capabilities(validated = true, isVpn = true))
        callbackSlot.captured.onCapabilitiesChanged(wifiNetwork, capabilities(validated = true, isVpn = false))
        check(monitor.isOnline) // sanity: online while the real network is still up

        callbackSlot.captured.onLost(wifiNetwork)

        assertFalse(monitor.isOnline)
    }

    @Test
    fun `a network going unvalidated after being online reports offline`() {
        val monitor = KapeNetworkConnectivityMonitor(context)
        monitor.start()
        val network = mockk<Network>()
        callbackSlot.captured.onCapabilitiesChanged(network, capabilities(validated = true))

        callbackSlot.captured.onCapabilitiesChanged(network, capabilities(validated = false))

        assertFalse(monitor.isOnline)
    }

    @Test
    fun `stop unregisters the callback and is idempotent`() {
        val monitor = KapeNetworkConnectivityMonitor(context)
        monitor.start()

        monitor.stop()
        monitor.stop()

        verify(exactly = 1) { connectivityManager.unregisterNetworkCallback(any<ConnectivityManager.NetworkCallback>()) }
    }

    @Test
    fun `awaitConnectivity resumes immediately when already online`() =
        runTest {
            val monitor = KapeNetworkConnectivityMonitor(context)
            // Never started — defaults to online, so this must not hang the test.
            monitor.awaitConnectivity()
        }

    @Test
    fun `awaitConnectivity suspends while offline and resumes once a non-VPN network is validated`() =
        runTest {
            val monitor = KapeNetworkConnectivityMonitor(context)
            monitor.start()
            val network = mockk<Network>()
            callbackSlot.captured.onCapabilitiesChanged(network, capabilities(validated = false)) // offline

            var resumed = false
            val job =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    monitor.awaitConnectivity()
                    resumed = true
                }

            assertFalse(resumed)

            callbackSlot.captured.onCapabilitiesChanged(network, capabilities(validated = true, isVpn = false))
            job.join()

            assertTrue(resumed)
        }
}
