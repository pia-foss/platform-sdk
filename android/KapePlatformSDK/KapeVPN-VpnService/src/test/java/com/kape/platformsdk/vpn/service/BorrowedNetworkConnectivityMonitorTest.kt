package com.kape.platformsdk.vpn.service

import com.kape.platformsdk.vpn.service.interfaces.BorrowedNetworkConnectivityMonitor
import com.kape.platformsdk.vpn.service.interfaces.NetworkConnectivityMonitor
import com.kape.platformsdk.vpn.service.models.KapeNetworkState
import com.kape.platformsdk.vpn.service.models.KapeNetworkTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BorrowedNetworkConnectivityMonitorTest {
    private class RecordingMonitor : NetworkConnectivityMonitor {
        var started = false
        var stopped = false
        val onlineFlow = MutableStateFlow(false)
        override val networkState = MutableStateFlow(KapeNetworkState.OFFLINE)

        override val isOnline: Boolean get() = onlineFlow.value

        override suspend fun awaitConnectivity() {
            onlineFlow.first { it }
        }

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }
    }

    // A session tearing down must not unregister the callback Connect on Demand still needs.
    @Test
    fun `start and stop never reach the delegate`() {
        val delegate = RecordingMonitor()
        val borrowed = BorrowedNetworkConnectivityMonitor(delegate)

        borrowed.start()
        borrowed.stop()

        assertFalse(delegate.started)
        assertFalse(delegate.stopped)
    }

    @Test
    fun `reads pass straight through to the delegate`() =
        runTest {
            val delegate = RecordingMonitor()
            val borrowed = BorrowedNetworkConnectivityMonitor(delegate)

            assertFalse(borrowed.isOnline)
            assertEquals(KapeNetworkState.OFFLINE, borrowed.networkState.value)

            delegate.onlineFlow.value = true
            delegate.networkState.value = KapeNetworkState(transport = KapeNetworkTransport.Wifi, ssid = "Cafe")

            assertTrue(borrowed.isOnline)
            assertEquals("Cafe", borrowed.networkState.value.ssid)
            borrowed.awaitConnectivity()
        }
}
