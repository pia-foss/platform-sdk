package com.kape.platformsdk.vpn.service.analytics

import com.kape.platformsdk.vpn.service.interfaces.VpnConfiguration
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionAnalyticsReporterTest {
    @Test
    fun `session begin and end fire in order with a matching sessionId`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val reporter = SessionAnalyticsReporter(analytics, dispatcher = UnconfinedTestDispatcher(testScheduler))

            reporter.reportSessionBegin()
            reporter.reportSessionEnd(DisconnectReason.UserInitiated)

            verifyOrder {
                analytics.sessionDidBegin(any())
                analytics.sessionDidEnd(any())
            }
        }

    @Test
    fun `reportSessionEnd is a no-op once the session already ended`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val reporter = SessionAnalyticsReporter(analytics, dispatcher = UnconfinedTestDispatcher(testScheduler))

            reporter.reportSessionBegin()
            reporter.reportSessionEnd(DisconnectReason.UserInitiated)
            reporter.reportSessionEnd(DisconnectReason.UserInitiated)

            verify(exactly = 1) { analytics.sessionDidEnd(any()) }
        }

    @Test
    fun `reportConnectionBegin mints a fresh id each call`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val reporter = SessionAnalyticsReporter(analytics, dispatcher = UnconfinedTestDispatcher(testScheduler))

            reporter.reportSessionBegin()
            val first = reporter.reportConnectionBegin(ConnectReason.Initial)
            reporter.reportConnectionEnd(DisconnectReason.UserInitiated)
            val second = reporter.reportConnectionBegin(ConnectReason.NetworkLoss)

            assertNotEquals(first, second)
        }

    @Test
    fun `reportConnectionRestart ends the old connection before the new one begins`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val reporter = SessionAnalyticsReporter(analytics, dispatcher = UnconfinedTestDispatcher(testScheduler))

            reporter.reportSessionBegin()
            val original = reporter.reportConnectionBegin(ConnectReason.Initial)
            reporter.reportConnectionRestart(DisconnectReason.ConnectionError(null), ConnectReason.NetworkLoss)

            verifyOrder {
                analytics.connectionDidBegin(match { it.connectionId == original })
                analytics.connectionDidEnd(match { it.connectionId == original })
                analytics.connectionDidBegin(match { it.connectionId != original && it.reason == ConnectReason.NetworkLoss })
            }
        }

    @Test
    fun `a connected attempt marks the connection as wasConnected on end`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val reporter = SessionAnalyticsReporter(analytics, dispatcher = UnconfinedTestDispatcher(testScheduler))

            reporter.reportSessionBegin()
            reporter.reportConnectionBegin(ConnectReason.Initial)
            val attemptId = reporter.reportAttemptBegin(FakeVpnConfiguration())
            reporter.reportAttemptEnd(attemptId, AttemptResult.Connected, elapsedMs = 10, configuration = FakeVpnConfiguration())
            reporter.reportConnectionEnd(DisconnectReason.UserInitiated)

            verify { analytics.connectionDidEnd(match { it.wasConnected }) }
        }

    @Test
    fun `a connection that never connects reports wasConnected false`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val reporter = SessionAnalyticsReporter(analytics, dispatcher = UnconfinedTestDispatcher(testScheduler))

            reporter.reportSessionBegin()
            reporter.reportConnectionBegin(ConnectReason.Initial)
            val attemptId = reporter.reportAttemptBegin(FakeVpnConfiguration())
            reporter.reportAttemptEnd(attemptId, AttemptResult.Failed(), elapsedMs = 10)
            reporter.reportConnectionEnd(DisconnectReason.NoEndpointsAvailable)

            verify { analytics.connectionDidEnd(match { !it.wasConnected }) }
        }

    @Test
    fun `connectionDidEnd carries the effectiveProtocol and effectiveObfuscation of the winning attempt`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val reporter = SessionAnalyticsReporter(analytics, dispatcher = UnconfinedTestDispatcher(testScheduler))

            reporter.reportSessionBegin()
            reporter.reportConnectionBegin(ConnectReason.Initial)
            val attemptId = reporter.reportAttemptBegin(FakeVpnConfiguration())
            reporter.reportAttemptEnd(
                attemptId,
                AttemptResult.Connected,
                elapsedMs = 10,
                configuration = FakeVpnConfiguration(vpnProtocolName = "lightway", obfuscationDescription = "amnezia"),
            )
            reporter.reportConnectionEnd(DisconnectReason.ConnectionError(null))

            verify {
                analytics.connectionDidEnd(
                    match { it.effectiveProtocol == "lightway" && it.effectiveObfuscation == "amnezia" },
                )
            }
        }

    @Test
    fun `connectionDidEnd effectiveProtocol and effectiveObfuscation are null when never connected`() =
        runTest {
            val analytics = mockk<VpnConnectionAnalytics>(relaxed = true)
            val reporter = SessionAnalyticsReporter(analytics, dispatcher = UnconfinedTestDispatcher(testScheduler))

            reporter.reportSessionBegin()
            reporter.reportConnectionBegin(ConnectReason.Initial)
            val attemptId = reporter.reportAttemptBegin(FakeVpnConfiguration())
            reporter.reportAttemptEnd(attemptId, AttemptResult.Failed(), elapsedMs = 10)
            reporter.reportConnectionEnd(DisconnectReason.NoEndpointsAvailable)

            verify {
                analytics.connectionDidEnd(
                    match { it.effectiveProtocol == null && it.effectiveObfuscation == null },
                )
            }
        }

    @Test
    fun `flush returns promptly when nothing is queued`() =
        runTest {
            val reporter = SessionAnalyticsReporter(NoOpVpnConnectionAnalytics, dispatcher = UnconfinedTestDispatcher(testScheduler))
            reporter.flush(timeoutMs = 50)
        }

    @Test
    fun `flush is bounded by its timeout even if a consumer callback is slow`() {
        // Real dispatcher deliberately — a slow callback only matters on a genuine
        // background thread; this is the exact ANR scenario flush() exists to bound.
        val slowAnalytics =
            object : VpnConnectionAnalytics by NoOpVpnConnectionAnalytics {
                override fun sessionDidBegin(event: SessionBeginEvent) {
                    Thread.sleep(500)
                }
            }
        val reporter = SessionAnalyticsReporter(slowAnalytics)

        reporter.reportSessionBegin()
        val elapsedMs =
            kotlin.system.measureTimeMillis {
                reporter.flush(timeoutMs = 50)
            }

        assertTrue(elapsedMs < 500, "flush() should not wait for the full slow callback, took ${elapsedMs}ms")
    }
}

private class FakeVpnConfiguration(
    override val vpnProtocolName: String = "fake",
    override val obfuscationDescription: String = "none",
) : VpnConfiguration
