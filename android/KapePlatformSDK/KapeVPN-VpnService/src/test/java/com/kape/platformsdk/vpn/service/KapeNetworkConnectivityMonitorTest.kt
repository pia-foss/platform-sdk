package com.kape.platformsdk.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.kape.platformsdk.vpn.service.interfaces.NetworkIdentityReader
import com.kape.platformsdk.vpn.service.models.KapeNetworkTransport
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        transport: Int? = null,
    ): NetworkCapabilities {
        val capabilities = mockk<NetworkCapabilities>()
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns isVpn
        every { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } returns (transport == NetworkCapabilities.TRANSPORT_WIFI)
        every {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } returns (transport == NetworkCapabilities.TRANSPORT_CELLULAR)
        return capabilities
    }

    private fun identityReader(
        wifi: NetworkIdentityReader.WifiIdentity? = null,
        carrier: String? = null,
    ) = object : NetworkIdentityReader {
        override fun wifiIdentity(capabilities: NetworkCapabilities?) = wifi

        override fun carrierName() = carrier
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

    @Test
    fun `reports the WiFi transport and the identity reader's SSID and security`() {
        val monitor =
            KapeNetworkConnectivityMonitor(
                context,
                identityReader = identityReader(wifi = NetworkIdentityReader.WifiIdentity("Cafe", isSecure = true)),
            )
        monitor.start()

        callbackSlot.captured.onCapabilitiesChanged(
            mockk<Network>(),
            capabilities(validated = true, transport = NetworkCapabilities.TRANSPORT_WIFI),
        )

        val state = monitor.networkState.value
        assertEquals(KapeNetworkTransport.Wifi, state.transport)
        assertEquals("Cafe", state.ssid)
        assertEquals(true, state.isSecure)
        assertNull(state.carrier)
    }

    @Test
    fun `reports the cellular transport and carrier`() {
        val monitor = KapeNetworkConnectivityMonitor(context, identityReader = identityReader(carrier = "O2"))
        monitor.start()

        callbackSlot.captured.onCapabilitiesChanged(
            mockk<Network>(),
            capabilities(validated = true, transport = NetworkCapabilities.TRANSPORT_CELLULAR),
        )

        val state = monitor.networkState.value
        assertEquals(KapeNetworkTransport.Cellular, state.transport)
        assertEquals("O2", state.carrier)
        assertNull(state.ssid)
    }

    // Must stay null rather than becoming a name of its own; the evaluator relies on it to fail closed.
    @Test
    fun `leaves the SSID null when the identity reader cannot read it`() {
        val monitor = KapeNetworkConnectivityMonitor(context, identityReader = identityReader(wifi = null))
        monitor.start()

        callbackSlot.captured.onCapabilitiesChanged(
            mockk<Network>(),
            capabilities(validated = true, transport = NetworkCapabilities.TRANSPORT_WIFI),
        )

        assertEquals(KapeNetworkTransport.Wifi, monitor.networkState.value.transport)
        assertNull(monitor.networkState.value.ssid)
    }

    @Test
    fun `a VPN network never becomes the reported transport`() {
        val monitor =
            KapeNetworkConnectivityMonitor(
                context,
                identityReader = identityReader(wifi = NetworkIdentityReader.WifiIdentity("Cafe", isSecure = true)),
            )
        monitor.start()

        callbackSlot.captured.onCapabilitiesChanged(
            mockk<Network>(),
            capabilities(validated = true, isVpn = true, transport = NetworkCapabilities.TRANSPORT_WIFI),
        )

        assertNull(monitor.networkState.value.transport)
    }

    @Test
    fun `stopping clears the reported network`() {
        val monitor =
            KapeNetworkConnectivityMonitor(
                context,
                identityReader = identityReader(wifi = NetworkIdentityReader.WifiIdentity("Cafe", isSecure = false)),
            )
        monitor.start()
        callbackSlot.captured.onCapabilitiesChanged(
            mockk<Network>(),
            capabilities(validated = true, transport = NetworkCapabilities.TRANSPORT_WIFI),
        )

        monitor.stop()

        assertNull(monitor.networkState.value.transport)
    }

    // Regression: FLAG_INCLUDE_LOCATION_INFO unredacts only the capabilities handed to that
    // callback. Re-reading from ConnectivityManager returns "<unknown ssid>" and breaks every
    // Ssid rule silently.
    @Test
    fun `hands the identity reader the capabilities the callback delivered`() {
        var seen: NetworkCapabilities? = null
        val reader =
            object : NetworkIdentityReader {
                override fun wifiIdentity(capabilities: NetworkCapabilities?): NetworkIdentityReader.WifiIdentity? {
                    seen = capabilities
                    return NetworkIdentityReader.WifiIdentity("Cafe", isSecure = true)
                }

                override fun carrierName(): String? = null
            }
        val monitor = KapeNetworkConnectivityMonitor(context, identityReader = reader)
        monitor.start()
        val delivered = capabilities(validated = true, transport = NetworkCapabilities.TRANSPORT_WIFI)

        callbackSlot.captured.onCapabilitiesChanged(mockk<Network>(), delivered)

        assertSame(delivered, seen)
    }
}
