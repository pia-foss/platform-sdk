package com.kape.platformsdk.vpn.service

import com.kape.platformsdk.vpn.service.analytics.AttemptResult
import com.kape.platformsdk.vpn.service.analytics.ConnectReason
import com.kape.platformsdk.vpn.service.analytics.DisconnectReason
import com.kape.platformsdk.vpn.service.analytics.VpnConnectionAnalytics
import com.kape.platformsdk.vpn.service.interfaces.ConnectionController
import com.kape.platformsdk.vpn.service.interfaces.GeneratorRetryBackoff
import com.kape.platformsdk.vpn.service.interfaces.NetworkConnectivityMonitor
import com.kape.platformsdk.vpn.service.interfaces.VpnConfiguration
import com.kape.platformsdk.vpn.service.interfaces.VpnConfigurationGenerator
import com.kape.platformsdk.vpn.service.models.KapeNetworkState
import com.kape.platformsdk.vpn.service.models.KapeVPNConnectionStatus
import com.kape.platformsdk.vpn.service.models.KapeVpnTrafficStats
import com.kape.platformsdk.vpn.service.models.KapeVpnTunnelError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class KapeSessionControllerTest {
    private fun fakeSystemTunnel(): KapeSystemTunnel {
        val tunnel = mockk<KapeSystemTunnel>()
        coEvery { tunnel.openNetworkLockTunnel() } returns true
        coEvery { tunnel.closeCurrentTunnel() } returns Unit
        return tunnel
    }

    @Test
    fun `a refused network lock tunnel reports a missing VPN permission`() =
        runTest {
            val tunnel = fakeSystemTunnel()
            coEvery { tunnel.openNetworkLockTunnel() } returns false
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { awaitCancellation() },
                    connectionControllers = emptyList(),
                    systemTunnel = tunnel,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            assertEquals(KapeVpnTunnelError.VpnPermissionMissing, controller.state.lastTunnelError.value)
            controller.stop()
        }

    @Test
    fun `status is Connecting after start`() =
        runTest {
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { awaitCancellation() },
                    connectionControllers = emptyList(),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            assertEquals(KapeVPNConnectionStatus.Connecting, controller.state.connectionStatus.value)
            controller.stop()
        }

    @Test
    fun `stop transitions status through Disconnecting before Disconnected`() =
        runTest {
            val statusChanges = mutableListOf<KapeVPNConnectionStatus>()

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { awaitCancellation() },
                    connectionControllers = emptyList(),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            val collectionJob =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    controller.state.connectionStatus.collect { statusChanges.add(it) }
                }

            controller.start()
            controller.stop()
            collectionJob.cancel()

            assertEquals(
                listOf(
                    KapeVPNConnectionStatus.Disconnected, // initial
                    KapeVPNConnectionStatus.Connecting,
                    KapeVPNConnectionStatus.Disconnecting,
                    KapeVPNConnectionStatus.Disconnected,
                ),
                statusChanges,
            )
        }

    @Test
    fun `connect is called when a matching configuration is emitted`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            coVerify { mockController.connect(any()) }
            controller.stop()
        }

    @Test
    fun `runVPN is called after connect succeeds`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            coVerify { mockController.runVPN() }
            controller.stop()
        }

    @Test
    fun `runVPN is not called when connect returns false`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            // generateConfigurations() is called in an infinite loop; suspend on the second call
            // so the test doesn't spin forever after the failed connect.
            var generated = false
            coEvery { mockController.connect(any()) } returns false

            val controller =
                KapeSessionController(
                    configurationGenerator =
                        VpnConfigurationGenerator {
                            if (!generated) {
                                generated = true
                                listOf(TestVpnConfiguration())
                            } else {
                                awaitCancellation()
                            }
                        },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            coVerify(exactly = 0) { mockController.runVPN() }
            controller.stop()
        }

    @Test
    fun `connection controller stop is called when the session is cancelled mid-runVPN`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            controller.stop()

            coVerify { mockController.stop() }
        }

    @Test
    fun `connection controller stop is called when runVPN returns an error`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } returns RuntimeException("tunnel error")
            coEvery { mockController.stop() } returns Unit

            var generated = false
            val controller =
                KapeSessionController(
                    configurationGenerator =
                        VpnConfigurationGenerator {
                            if (!generated) {
                                generated = true
                                listOf(TestVpnConfiguration())
                            } else {
                                awaitCancellation()
                            }
                        },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            coVerify { mockController.stop() }
            controller.stop()
        }

    @Test
    fun `network lock tunnel is opened before the first connect`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit

            val systemTunnel = fakeSystemTunnel()

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = systemTunnel,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            coVerifyOrder {
                systemTunnel.openNetworkLockTunnel()
                mockController.connect(any())
            }
            controller.stop()
        }

    @Test
    fun `network lock tunnel is not re-opened after runVPN returns — the previous tunnel is left in place`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } returns RuntimeException("tunnel error")
            coEvery { mockController.stop() } returns Unit

            val systemTunnel = fakeSystemTunnel()

            var generated = false
            val controller =
                KapeSessionController(
                    configurationGenerator =
                        VpnConfigurationGenerator {
                            if (!generated) {
                                generated = true
                                listOf(TestVpnConfiguration())
                            } else {
                                awaitCancellation()
                            }
                        },
                    connectionControllers = listOf(mockController),
                    systemTunnel = systemTunnel,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            coVerifyOrder {
                systemTunnel.openNetworkLockTunnel() // session start
                mockController.connect(any())
                mockController.runVPN()
                mockController.stop()
            }
            // Opening the network lock tunnel again here would itself reopen a leak window
            // (tearing down/recreating the OS interface) — the previous controller's own interface
            // is left claiming its routes until the next real establish() call instead.
            coVerify(exactly = 1) { systemTunnel.openNetworkLockTunnel() }
            controller.stop()
        }

    @Test
    fun `analytics fires session, connection and attempt begin+end for a happy path`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            // Attempt reporting lives on the controller now (see ConnectionAttemptReporting) — the
            // mock simulates what a real connect() does: report begin, then end on success/failure.
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>(relaxed = true)
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } coAnswers {
                val config = firstArg<TestVpnConfiguration>()
                val attemptId = mockController.attemptReporter?.reportAttemptBegin(config)
                attemptId?.let { mockController.attemptReporter?.reportAttemptEnd(it, AttemptResult.Connected, 0, config) }
                true
            }
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    analytics = analytics,
                    analyticsDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            every { mockController.attemptReporter } returns controller

            controller.start()

            verifyOrder {
                analytics.sessionDidBegin(any())
                analytics.connectionDidBegin(match { it.reason == ConnectReason.Initial })
                analytics.attemptDidBegin(any())
                analytics.attemptDidEnd(match { it.result == AttemptResult.Connected })
            }

            controller.stop()
            verifyOrder {
                analytics.connectionDidEnd(match { it.reason == DisconnectReason.UserInitiated && it.wasConnected })
                analytics.sessionDidEnd(match { it.reason == DisconnectReason.UserInitiated && it.wasEverConnected })
            }
        }

    @Test
    fun `analytics fires attemptDidEnd(Failed) and does not open a connection when connect fails`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>(relaxed = true)
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } coAnswers {
                val config = firstArg<TestVpnConfiguration>()
                val attemptId = mockController.attemptReporter?.reportAttemptBegin(config)
                attemptId?.let { mockController.attemptReporter?.reportAttemptEnd(it, AttemptResult.Failed(), 0) }
                false
            }

            var generated = false
            val controller =
                KapeSessionController(
                    configurationGenerator =
                        VpnConfigurationGenerator {
                            if (!generated) {
                                generated = true
                                listOf(TestVpnConfiguration())
                            } else {
                                awaitCancellation()
                            }
                        },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    analytics = analytics,
                    analyticsDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            every { mockController.attemptReporter } returns controller

            controller.start()

            verify { analytics.attemptDidEnd(match { it.result == AttemptResult.Failed(null) }) }
            verify(exactly = 0) { analytics.connectionDidEnd(any()) }
            controller.stop()
        }

    @Test
    fun `the retained tunnel is closed for good on stop`() =
        runTest {
            val systemTunnel = fakeSystemTunnel()

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { awaitCancellation() },
                    connectionControllers = emptyList(),
                    systemTunnel = systemTunnel,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            controller.stop()

            coVerify { systemTunnel.closeCurrentTunnel() }
        }

    @Test
    fun `forceReconnect stops the active connection and the run loop reconnects`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true

            // Mirrors a real ConnectionController: each runVPN() call suspends on its own fresh
            // deferred until stop() unblocks the current one — a shared deferred would make the
            // reconnect's own runVPN() call immediately return already-completed, looping forever.
            val currentDeferred = AtomicReference<CompletableDeferred<Throwable?>?>(null)
            coEvery { mockController.runVPN() } coAnswers {
                val deferred = CompletableDeferred<Throwable?>()
                currentDeferred.set(deferred)
                deferred.await()
            }
            coEvery { mockController.stop() } coAnswers { currentDeferred.get()?.complete(null) }

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            assertEquals(KapeVPNConnectionStatus.Connected, controller.state.connectionStatus.value)

            controller.forceReconnect()

            coVerify(exactly = 2) { mockController.stop() } // once via forceReconnect(), once via the run loop's own finally
            coVerify(exactly = 2) { mockController.connect(any()) } // initial connect + the reconnect
            assertEquals(KapeVPNConnectionStatus.Connected, controller.state.connectionStatus.value)
            controller.stop()
        }

    @Test
    fun `analytics reports a networkLoss reconnect exactly once across a failed retry`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.stop() } returns Unit

            // a: connects, then runVPN drops (loss). b: fails to reconnect. c: reconnects and stays up.
            var connectCalls = 0
            coEvery { mockController.connect(any()) } coAnswers {
                connectCalls++
                connectCalls != 2
            }
            var runVpnCalls = 0
            coEvery { mockController.runVPN() } coAnswers {
                runVpnCalls++
                if (runVpnCalls == 1) RuntimeException("tunnel lost") else awaitCancellation()
            }

            var generated = false
            val controller =
                KapeSessionController(
                    configurationGenerator =
                        VpnConfigurationGenerator {
                            if (!generated) {
                                generated = true
                                listOf(TestVpnConfiguration(), TestVpnConfiguration(), TestVpnConfiguration())
                            } else {
                                awaitCancellation()
                            }
                        },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    analytics = analytics,
                    analyticsDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            // One connectionEnd, one connectionBegin — not two of each, despite b's failed
            // retry happening while reconnectInProgress is set.
            verify(exactly = 1) {
                analytics.connectionDidEnd(match { it.reason is DisconnectReason.ConnectionError })
            }
            verify(exactly = 1) {
                analytics.connectionDidBegin(match { it.reason == ConnectReason.NetworkLoss })
            }
            controller.stop()
        }

    @Test
    fun `forceReconnect is a no-op when no connection is active`() =
        runTest {
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { awaitCancellation() },
                    connectionControllers = emptyList(),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            controller.forceReconnect()

            assertEquals(KapeVPNConnectionStatus.Connecting, controller.state.connectionStatus.value)
            controller.stop()
        }

    @Test
    fun `trafficStats stays ZERO until the poll interval elapses`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit
            every { mockController.getTrafficStats() } returns KapeVpnTrafficStats(bytesReceived = 100, bytesSent = 200)

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()

            assertEquals(KapeVpnTrafficStats.ZERO, controller.state.trafficStats.value)
            controller.stop()
        }

    @Test
    fun `trafficStats updates to the active controller's reading after the poll interval`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit
            every { mockController.getTrafficStats() } returns KapeVpnTrafficStats(bytesReceived = 100, bytesSent = 200)

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            advanceTimeBy(1_001)

            assertEquals(KapeVpnTrafficStats(bytesReceived = 100, bytesSent = 200), controller.state.trafficStats.value)
            controller.stop()
        }

    @Test
    fun `trafficStats resets to ZERO when a connection drops before the reconnect`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            every { mockController.getTrafficStats() } returns KapeVpnTrafficStats(bytesReceived = 100, bytesSent = 200)
            val currentDeferred = AtomicReference<CompletableDeferred<Throwable?>?>(null)
            coEvery { mockController.runVPN() } coAnswers {
                val deferred = CompletableDeferred<Throwable?>()
                currentDeferred.set(deferred)
                deferred.await()
            }
            coEvery { mockController.stop() } coAnswers { currentDeferred.get()?.complete(null) }

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            advanceTimeBy(1_001)
            assertEquals(KapeVpnTrafficStats(bytesReceived = 100, bytesSent = 200), controller.state.trafficStats.value)

            // The dropped connection's counters must not survive into the reconnected session;
            // the poll only refreshes them after the next interval.
            controller.forceReconnect()

            assertEquals(KapeVpnTrafficStats.ZERO, controller.state.trafficStats.value)
            controller.stop()
        }

    @Test
    fun `trafficStats resets to ZERO when the session stops`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit
            every { mockController.getTrafficStats() } returns KapeVpnTrafficStats(bytesReceived = 100, bytesSent = 200)

            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { listOf(TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            advanceTimeBy(1_001)
            assertEquals(KapeVpnTrafficStats(bytesReceived = 100, bytesSent = 200), controller.state.trafficStats.value)

            controller.stop()

            assertEquals(KapeVpnTrafficStats.ZERO, controller.state.trafficStats.value)
        }

    @Test
    fun `an empty configuration batch backs off and retries instead of spinning`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit

            val backoffCalls = mutableListOf<Int>()
            val fakeBackoff = GeneratorRetryBackoff { consecutiveFailures -> backoffCalls.add(consecutiveFailures) }

            var callCount = 0
            val controller =
                KapeSessionController(
                    configurationGenerator =
                        VpnConfigurationGenerator {
                            callCount++
                            when (callCount) {
                                1, 2 -> emptyList()
                                3 -> listOf(TestVpnConfiguration())
                                else -> awaitCancellation()
                            }
                        },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    generatorRetryBackoff = fakeBackoff,
                )

            controller.start()

            assertEquals(listOf(1, 2), backoffCalls)
            assertEquals(KapeVPNConnectionStatus.Connected, controller.state.connectionStatus.value)
            coVerify { mockController.connect(any()) }
            controller.stop()
        }

    @Test
    fun `a generator retry does not end the session's connection analytics span`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>(relaxed = true)
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } coAnswers {
                val config = firstArg<TestVpnConfiguration>()
                val attemptId = mockController.attemptReporter?.reportAttemptBegin(config)
                attemptId?.let { mockController.attemptReporter?.reportAttemptEnd(it, AttemptResult.Connected, 0, config) }
                true
            }
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }

            var callCount = 0
            val controller =
                KapeSessionController(
                    configurationGenerator =
                        VpnConfigurationGenerator {
                            callCount++
                            if (callCount == 1) emptyList() else listOf(TestVpnConfiguration())
                        },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    analytics = analytics,
                    analyticsDispatcher = UnconfinedTestDispatcher(testScheduler),
                    generatorRetryBackoff = GeneratorRetryBackoff { },
                )
            every { mockController.attemptReporter } returns controller

            controller.start()

            // The connection span opened at session start is still the active one — a transient
            // empty batch must not have silently ended it (which would also kill all further
            // attempt reporting for the rest of the session).
            verify(exactly = 0) { analytics.connectionDidEnd(any()) }
            verify { analytics.attemptDidBegin(any()) }
            verify { analytics.attemptDidEnd(match { it.result == AttemptResult.Connected }) }
            controller.stop()
        }

    @Test
    fun `waits for connectivity before fetching the first configuration, and fetches once online`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit

            var generatorCallCount = 0
            val connectivityMonitor = FakeNetworkConnectivityMonitor(online = false)
            val controller =
                KapeSessionController(
                    configurationGenerator =
                        VpnConfigurationGenerator {
                            generatorCallCount++
                            listOf(TestVpnConfiguration())
                        },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    networkConnectivityMonitor = connectivityMonitor,
                )

            controller.start()

            assertEquals(0, generatorCallCount)
            coVerify(exactly = 0) { mockController.connect(any()) }

            connectivityMonitor.setOnline(true)

            assertEquals(1, generatorCallCount)
            coVerify(exactly = 1) { mockController.connect(any()) }
            controller.stop()
        }

    @Test
    fun `a generator failure while offline defers to connectivity wait instead of the fixed backoff`() =
        runTest {
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.runVPN() } coAnswers { awaitCancellation() }
            coEvery { mockController.stop() } returns Unit

            val backoffCalls = mutableListOf<Int>()
            val fakeBackoff = GeneratorRetryBackoff { consecutiveFailures -> backoffCalls.add(consecutiveFailures) }
            val connectivityMonitor = FakeNetworkConnectivityMonitor(online = true)

            var generatorCallCount = 0
            val controller =
                KapeSessionController(
                    configurationGenerator =
                        VpnConfigurationGenerator {
                            generatorCallCount++
                            if (generatorCallCount == 1) {
                                // Simulates the fetch failing exactly as the network drops.
                                connectivityMonitor.setOnline(false)
                                throw Exception("simulated network error")
                            }
                            listOf(TestVpnConfiguration())
                        },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    generatorRetryBackoff = fakeBackoff,
                    networkConnectivityMonitor = connectivityMonitor,
                )

            controller.start()

            // The failure happened while offline — no fixed backoff was consulted, and the retry
            // parks behind the connectivity gate instead of hammering the generator again.
            assertEquals(emptyList<Int>(), backoffCalls)
            assertEquals(1, generatorCallCount)

            connectivityMonitor.setOnline(true)

            assertEquals(2, generatorCallCount)
            assertEquals(KapeVPNConnectionStatus.Connected, controller.state.connectionStatus.value)
            controller.stop()
        }

    @Test
    fun `mid-session loss waits for connectivity before trying the next cached configuration`() =
        runTest {
            // A single fetch already yielded two configurations — connect(a) succeeds and
            // runVPN(a) is lost; "b" is already cached in the same batch, so pulling it needs no
            // new fetch.
            val mockController = mockk<ConnectionController<TestVpnConfiguration>>()
            coEvery { mockController.configurationClass } returns TestVpnConfiguration::class
            coEvery { mockController.connect(any()) } returns true
            coEvery { mockController.stop() } returns Unit

            val connectivityMonitor = FakeNetworkConnectivityMonitor(online = true)
            var runVPNCallCount = 0
            coEvery { mockController.runVPN() } coAnswers {
                runVPNCallCount++
                if (runVPNCallCount == 1) {
                    // Simulates the loss coinciding with the network actually dropping.
                    connectivityMonitor.setOnline(false)
                    Exception("connection lost")
                } else {
                    awaitCancellation()
                }
            }

            val controller =
                KapeSessionController(
                    configurationGenerator =
                        VpnConfigurationGenerator { listOf(TestVpnConfiguration(), TestVpnConfiguration()) },
                    connectionControllers = listOf(mockController),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    networkConnectivityMonitor = connectivityMonitor,
                )

            controller.start()

            // "a" connected, but the cached "b" isn't tried yet — we're offline.
            coVerify(exactly = 1) { mockController.connect(any()) }

            connectivityMonitor.setOnline(true)

            // "b" — already cached from the original fetch — is connected now that we're online.
            coVerify(exactly = 2) { mockController.connect(any()) }
            controller.stop()
        }

    @Test
    fun `analytics reports the reason passed to stop`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val controller =
                KapeSessionController(
                    configurationGenerator = VpnConfigurationGenerator { awaitCancellation() },
                    connectionControllers = emptyList(),
                    systemTunnel = fakeSystemTunnel(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                    analytics = analytics,
                    analyticsDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            controller.start()
            controller.stop(DisconnectReason.Revoked)

            verify { analytics.sessionDidEnd(match { it.reason == DisconnectReason.Revoked }) }
        }
}

private class TestVpnConfiguration : VpnConfiguration {
    override val vpnProtocolName: String = "test"
}

/** Test-controlled [NetworkConnectivityMonitor] — [setOnline] drives [awaitConnectivity] directly. */
private class FakeNetworkConnectivityMonitor(
    online: Boolean = true,
) : NetworkConnectivityMonitor {
    private val onlineFlow = MutableStateFlow(online)

    override val isOnline: Boolean get() = onlineFlow.value

    override val networkState = MutableStateFlow(KapeNetworkState.OFFLINE)

    override suspend fun awaitConnectivity() {
        onlineFlow.first { it }
    }

    override fun start() = Unit

    override fun stop() = Unit

    fun setOnline(value: Boolean) {
        onlineFlow.update { value }
    }
}
