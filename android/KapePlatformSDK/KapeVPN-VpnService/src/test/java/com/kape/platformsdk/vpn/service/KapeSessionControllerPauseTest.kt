package com.kape.platformsdk.vpn.service

import com.kape.platformsdk.vpn.service.analytics.ConnectReason
import com.kape.platformsdk.vpn.service.analytics.DisconnectReason
import com.kape.platformsdk.vpn.service.analytics.VpnConnectionAnalytics
import com.kape.platformsdk.vpn.service.interfaces.ConnectionController
import com.kape.platformsdk.vpn.service.interfaces.VpnConfiguration
import com.kape.platformsdk.vpn.service.interfaces.VpnConfigurationGenerator
import com.kape.platformsdk.vpn.service.models.KapeVPNConnectionStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class KapeSessionControllerPauseTest {
    private fun fakeSystemTunnel(): KapeSystemTunnel {
        val tunnel = mockk<KapeSystemTunnel>()
        coEvery { tunnel.openNetworkLockTunnel() } returns true
        coEvery { tunnel.closeCurrentTunnel() } returns Unit
        return tunnel
    }

    // Mirrors a real ConnectionController: each runVPN() call suspends on its own fresh deferred
    // until stop() unblocks the current one — the same pattern KapeSessionControllerTest's
    // `forceReconnect` test uses, needed here because pause()/resume() drive the exact same
    // stop-then-reconnect run-loop mechanics.
    private fun reconnectableController(): ConnectionController<PauseTestVpnConfiguration> {
        val mockController = mockk<ConnectionController<PauseTestVpnConfiguration>>()
        coEvery { mockController.configurationClass } returns PauseTestVpnConfiguration::class
        coEvery { mockController.connect(any()) } returns true
        val currentDeferred = AtomicReference<CompletableDeferred<Throwable?>?>(null)
        coEvery { mockController.runVPN() } coAnswers {
            val deferred = CompletableDeferred<Throwable?>()
            currentDeferred.set(deferred)
            deferred.await()
        }
        coEvery { mockController.stop() } coAnswers { currentDeferred.get()?.complete(null) }
        return mockController
    }

    @Test
    fun `pause parks the run loop without reconnecting`() =
        runTest {
            val mockController = reconnectableController()
            val systemTunnel = fakeSystemTunnel()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = systemTunnel,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            assertEquals(KapeVPNConnectionStatus.Connected, controller.state.connectionStatus.value)

            controller.pause(100.seconds)

            assertEquals(KapeVPNConnectionStatus.Paused, controller.state.connectionStatus.value)
            coVerify { systemTunnel.closeCurrentTunnel() }
            coVerify(exactly = 1) { mockController.connect(any()) } // parked, not reconnected

            controller.stop()
        }

    @Test
    fun `resume reconnects the tunnel`() =
        runTest {
            val mockController = reconnectableController()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            controller.pause(100.seconds)

            controller.resume()

            coVerify(exactly = 2) { mockController.connect(any()) }
            assertEquals(KapeVPNConnectionStatus.Connected, controller.state.connectionStatus.value)

            controller.stop()
        }

    @Test
    fun `resume restores the network lock tunnel instead of leaving a gap until reconnect`() =
        runTest {
            val mockController = reconnectableController()
            val systemTunnel = fakeSystemTunnel()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = systemTunnel,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            coVerify(exactly = 1) { systemTunnel.openNetworkLockTunnel() } // session start

            controller.pause(100.seconds)
            controller.resume()

            coVerify(exactly = 2) { systemTunnel.openNetworkLockTunnel() } // re-armed on resume

            controller.stop()
        }

    @Test
    fun `analytics pause fires connectionEnd(Paused), resume fires connectionBegin(Resumed), never networkLoss`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val mockController = reconnectableController()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    analytics = analytics,
                    analyticsDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            controller.pause(100.seconds)

            verify { analytics.connectionDidEnd(match { it.reason == DisconnectReason.Paused }) }

            controller.resume()

            verify { analytics.connectionDidBegin(match { it.reason == ConnectReason.Resumed }) }
            verify(exactly = 0) { analytics.connectionDidBegin(match { it.reason == ConnectReason.NetworkLoss }) }

            controller.stop()
        }

    @Test
    fun `concurrent resume calls reconnect exactly once`() =
        runTest {
            val mockController = reconnectableController()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            controller.pause(100.seconds)

            val r1 = launch { controller.resume() }
            val r2 = launch { controller.resume() }
            r1.join()
            r2.join()

            coVerify(exactly = 2) { mockController.connect(any()) } // initial + exactly one reconnect

            controller.stop()
        }

    // Deliberately real time, not runTest's virtual scheduler — driving the auto-resume timer's
    // own launched coroutine through a shared UnconfinedTestDispatcher + advanceTimeBy proved
    // fragile (coVerify could wedge on scheduler state left behind by the self-firing timer).
    @Test
    fun `timer auto-resumes after the duration elapses`() =
        runBlocking {
            val mockController = reconnectableController()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                )

            controller.start()
            // Wait for the initial connect to actually land before pausing — on a real (non-Unconfined)
            // dispatcher, start() returns before the run loop has necessarily called connect() yet;
            // pausing too early would see a null activeController and pause() would no-op the stop().
            withTimeout(2_000) {
                while (controller.state.connectionStatus.value != KapeVPNConnectionStatus.Connected) delay(10)
            }

            controller.pause(50.milliseconds)

            // Wait for the run loop to actually leave Connected (confirms the pause took hold),
            // then for the actual reconnect to land — not just isPaused flipping false, since
            // resume() clears isPaused before the woken run loop has necessarily finished
            // reconnecting, so polling isPaused alone can race ahead of connect().
            withTimeout(2_000) {
                while (controller.state.connectionStatus.value == KapeVPNConnectionStatus.Connected) delay(10)
            }
            withTimeout(2_000) {
                while (controller.state.connectionStatus.value != KapeVPNConnectionStatus.Connected) delay(10)
            }

            coVerify(exactly = 2) { mockController.connect(any()) }

            controller.stop()
        }

    @Test
    fun `resumeIfDue resumes when pause already elapsed, no-op when future`() =
        runTest {
            val mockController = reconnectableController()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            // Paused far in the future — resumeIfDue is a no-op (pausedUntil not reached).
            controller.pause(100.seconds)
            controller.resumeIfDue()
            coVerify(exactly = 1) { mockController.connect(any()) }

            // Re-armed already elapsed (negative duration -> pausedUntil in the past).
            controller.pause((-1).seconds)
            controller.resumeIfDue()
            coVerify(exactly = 2) { mockController.connect(any()) }

            controller.stop()
        }

    @Test
    fun `re-arming pause does not double-fire analytics or close the tunnel twice`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val systemTunnel = fakeSystemTunnel()
            val mockController = reconnectableController()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = systemTunnel,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    analytics = analytics,
                    analyticsDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            controller.pause(100.seconds)
            controller.pause(200.seconds) // re-arm, not a fresh pause

            coVerify(exactly = 1) { systemTunnel.closeCurrentTunnel() }
            verify(exactly = 1) { analytics.connectionDidEnd(match { it.reason == DisconnectReason.Paused }) }

            controller.stop()
        }

    @Test
    fun `stop during pause exits without reconnecting`() =
        runTest {
            val mockController = reconnectableController()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            controller.pause(100.seconds)

            controller.stop()

            coVerify(exactly = 1) { mockController.connect(any()) } // never reconnected
            assertEquals(KapeVPNConnectionStatus.Disconnected, controller.state.connectionStatus.value)
        }

    @Test
    fun `pause is ignored once stop has been called`() =
        runTest {
            val mockController = reconnectableController()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            controller.stop()

            controller.pause(100.seconds)

            assertFalse(controller.isPaused)
        }

    @Test
    fun `forceReconnect while paused resumes instead of no-op`() =
        runTest {
            val mockController = reconnectableController()
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(PauseTestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            controller.pause(100.seconds)

            controller.forceReconnect()

            assertFalse(controller.isPaused)
            coVerify(exactly = 2) { mockController.connect(any()) }
            assertEquals(KapeVPNConnectionStatus.Connected, controller.state.connectionStatus.value)

            controller.stop()
        }
}

private class PauseTestVpnConfiguration : VpnConfiguration {
    override val vpnProtocolName: String = "test"
}
