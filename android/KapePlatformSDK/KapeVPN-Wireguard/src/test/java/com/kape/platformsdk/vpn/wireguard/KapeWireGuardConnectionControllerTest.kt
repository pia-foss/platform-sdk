package com.kape.platformsdk.vpn.wireguard

import android.os.ParcelFileDescriptor
import com.kape.platformsdk.vpn.service.KapeSystemTunnel
import com.kape.platformsdk.vpn.service.KapeTunnelBuilder
import com.kape.platformsdk.vpn.service.models.IpAddress
import com.kape.platformsdk.vpn.service.models.KapeVpnTrafficStats
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.amnezia.awg.AwgLogger
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KapeWireGuardConnectionControllerTest {
    private lateinit var systemTunnel: KapeSystemTunnel
    private lateinit var authenticator: WireGuardAuthenticator
    private lateinit var wireguardClient: WireguardClient
    private lateinit var mockBuilder: KapeTunnelBuilder
    private lateinit var mockParcelFd: ParcelFileDescriptor
    private lateinit var loggerSlot: CapturingSlot<AwgLogger>
    private lateinit var dnsSlot: CapturingSlot<InetAddress>
    private lateinit var controller: KapeWireGuardConnectionController

    @Before
    fun setup() {
        systemTunnel = mockk()
        authenticator = mockk()
        wireguardClient = mockk()
        mockBuilder = mockk()
        mockParcelFd = mockk()
        loggerSlot = slot()
        dnsSlot = slot()

        coEvery { authenticator.authenticate(any()) } returns fakeAuthConfig

        every { systemTunnel.newBuilder() } returns mockBuilder
        every { systemTunnel.protect(any<Int>()) } returns true
        every { mockBuilder.addAddress(any<InetAddress>(), any()) } returns mockBuilder
        every { mockBuilder.addDnsServer(capture(dnsSlot)) } returns mockBuilder
        every { mockBuilder.addRoute(any<InetAddress>(), any()) } returns mockBuilder
        every { mockBuilder.addRoute(any<String>(), any()) } returns mockBuilder
        every { mockBuilder.setMtu(any()) } returns mockBuilder
        coEvery { mockBuilder.establish() } returns mockParcelFd
        every { mockParcelFd.detachFd() } returns FAKE_FD

        every { wireguardClient.setLogger(capture(loggerSlot)) } just Runs
        every { wireguardClient.turnOn(any(), any(), any()) } returns FAKE_HANDLE
        every { wireguardClient.socketV4(any()) } returns 1
        every { wireguardClient.socketV6(any()) } returns 2
        every { wireguardClient.turnOff(any()) } just Runs
        every { wireguardClient.resetLogger() } just Runs

        controller =
            KapeWireGuardConnectionController(
                systemTunnel = systemTunnel,
                authenticator = authenticator,
                wireguardClient = wireguardClient,
            )
    }

    // ── connect() ──────────────────────────────────────────────────────────────

    @Test
    fun `authenticate is called on connect`() =
        runTest {
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()
            triggerHandshakeSuccess()
            runCurrent()
            connectJob.join()

            coVerify { authenticator.authenticate(fakeConfig.endpointConfiguration) }
        }

    @Test
    fun `wireguard is turned on after authentication`() =
        runTest {
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()
            triggerHandshakeSuccess()
            runCurrent()
            connectJob.join()

            verify { wireguardClient.turnOn(any(), FAKE_FD, any()) }
        }

    @Test
    fun `connect suspends until a log message is received`() =
        runTest {
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            assertFalse(connectJob.isCompleted)

            // cleanup
            triggerHandshakeSuccess()
            runCurrent()
        }

    @Test
    fun `connect returns true when handshake success log is received`() =
        runTest {
            var result: Boolean? = null
            val connectJob = launch { result = controller.connect(fakeConfig) }
            runCurrent()
            assertFalse(connectJob.isCompleted)

            triggerHandshakeSuccess()
            runCurrent()

            assertTrue(connectJob.isCompleted)
            assertFalse(connectJob.isCancelled)
            assertEquals(result, true)
        }

    @Test
    fun `connect returns false when final failure log is received`() =
        runTest {
            var result: Boolean? = null
            val connectJob = launch { result = controller.connect(fakeConfig) }
            runCurrent()

            triggerHandshakeFailed()
            runCurrent()
            connectJob.join()

            assertTrue(result == false)
        }

    @Test
    fun `connect returns false on timeout`() =
        runTest {
            var result: Boolean? = null
            val connectJob = launch { result = controller.connect(fakeConfig) }
            runCurrent()

            advanceTimeBy(HANDSHAKE_TIMEOUT_MS + 1)
            connectJob.join()

            assertEquals(result, false)
        }

    @Test
    fun `retrying log during initial handshake is ignored`() =
        runTest {
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            triggerHandshakeRetrying()
            runCurrent()

            // connect is still suspended — the retry was ignored
            assertFalse(connectJob.isCompleted)

            // cleanup
            triggerHandshakeSuccess()
            runCurrent()
        }

    @Test
    fun `pre-seeded dnsServers survives authentication and is used as tunnel DNS`() =
        runTest {
            val configWithFilteringDns = fakeConfig.copy(dnsServers = listOf("10.20.30.1"))

            val connectJob = launch { controller.connect(configWithFilteringDns) }
            runCurrent()
            triggerHandshakeSuccess()
            runCurrent()
            connectJob.join()

            assertEquals("10.20.30.1", dnsSlot.captured.hostAddress)
        }

    @Test
    fun `v4 gateway is registered as a host route`() =
        runTest {
            val configWithGateway = fakeConfig.copy(gatewayIp = IpAddress.V4("10.20.30.1"))

            val connectJob = launch { controller.connect(configWithGateway) }
            runCurrent()
            triggerHandshakeSuccess()
            runCurrent()
            connectJob.join()

            verify { mockBuilder.addRoute("10.20.30.1", 32) }
        }

    @Test
    fun `v6 gateway is registered as a host route`() =
        runTest {
            val configWithGateway = fakeConfig.copy(gatewayIp = IpAddress.V6("fe80::1"))

            val connectJob = launch { controller.connect(configWithGateway) }
            runCurrent()
            triggerHandshakeSuccess()
            runCurrent()
            connectJob.join()

            verify { mockBuilder.addRoute("fe80::1", 128) }
        }

    // ── runVPN() ───────────────────────────────────────────────────────────────

    @Test
    fun `runVPN returns ConnectionLost when retrying log received after connection`() =
        runTest {
            connectAndHandshake()

            var result: Throwable? = null
            val runVpnJob = launch { result = controller.runVPN() }
            runCurrent()

            triggerHandshakeRetrying()
            runCurrent()
            runVpnJob.join()

            assertTrue(result is WireGuardConnectionError.ConnectionLost)
        }

    @Test
    fun `runVPN returns ConnectionLost when final failure log received after connection`() =
        runTest {
            connectAndHandshake()

            var result: Throwable? = null
            val runVpnJob = launch { result = controller.runVPN() }
            runCurrent()

            triggerHandshakeFailed()
            runCurrent()
            runVpnJob.join()

            assertTrue(result is WireGuardConnectionError.ConnectionLost)
        }

    @Test
    fun `runVPN returns null on stop`() =
        runTest {
            connectAndHandshake()

            val results = mutableListOf<Throwable?>()
            val runVpnJob = launch { results.add(controller.runVPN()) }
            runCurrent()

            controller.stop()
            runCurrent()
            runVpnJob.join()

            assertTrue(results.size == 1)
            assertNull(results.first())
        }

    // ── stop() ─────────────────────────────────────────────────────────────────

    @Test
    fun `stop turns off wireguard and resets logger`() =
        runTest {
            connectAndHandshake()
            controller.stop()

            verify { wireguardClient.turnOff(FAKE_HANDLE) }
            verify { wireguardClient.resetLogger() }
        }

    // ── getTrafficStats() ──────────────────────────────────────────────────────

    @Test
    fun `getTrafficStats returns null before connect`() =
        runTest {
            assertNull(controller.getTrafficStats())
        }

    @Test
    fun `getTrafficStats returns parsed stats after connect`() =
        runTest {
            connectAndHandshake()
            every { wireguardClient.getConfig(FAKE_HANDLE) } returns
                """
                private_key=0000000000000000000000000000000000000000000000000000000000000000
                listen_port=51820
                public_key=0000000000000000000000000000000000000000000000000000000000000000
                preshared_key=0000000000000000000000000000000000000000000000000000000000000000
                endpoint=1.2.3.4:51820
                last_handshake_time_sec=1700000000
                rx_bytes=1234
                tx_bytes=5678
                """.trimIndent()

            assertEquals(KapeVpnTrafficStats(bytesReceived = 1234, bytesSent = 5678), controller.getTrafficStats())
        }

    @Test
    fun `getTrafficStats sums bytes across multiple peers`() =
        runTest {
            connectAndHandshake()
            every { wireguardClient.getConfig(FAKE_HANDLE) } returns
                """
                private_key=0000000000000000000000000000000000000000000000000000000000000000
                listen_port=51820
                public_key=0000000000000000000000000000000000000000000000000000000000000000
                rx_bytes=100
                tx_bytes=200
                public_key=1111111111111111111111111111111111111111111111111111111111111111
                rx_bytes=300
                tx_bytes=400
                """.trimIndent()

            assertEquals(KapeVpnTrafficStats(bytesReceived = 400, bytesSent = 600), controller.getTrafficStats())
        }

    // ── cleanup on failure ─────────────────────────────────────────────────────

    @Test
    fun `connect returns false and cleans up when socketV4 protect fails`() =
        runTest {
            every { systemTunnel.protect(1) } returns false

            val result = controller.connect(fakeConfig)

            assertFalse(result)
            verify { wireguardClient.turnOff(FAKE_HANDLE) }
            verify { wireguardClient.resetLogger() }
        }

    @Test
    fun `connect returns false and cleans up when socketV6 protect fails`() =
        runTest {
            every { systemTunnel.protect(2) } returns false

            val result = controller.connect(fakeConfig)

            assertFalse(result)
            verify { wireguardClient.turnOff(FAKE_HANDLE) }
            verify { wireguardClient.resetLogger() }
        }

    @Test
    fun `turnOff and resetLogger are called on handshake timeout`() =
        runTest {
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            advanceTimeBy(HANDSHAKE_TIMEOUT_MS + 1)
            connectJob.join()

            verify { wireguardClient.turnOff(FAKE_HANDLE) }
            verify { wireguardClient.resetLogger() }
        }

    @Test
    fun `CancellationException during connect is rethrown and cleans up`() =
        runTest {
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent() // suspend at handshake.await()

            connectJob.cancel()
            connectJob.join()

            assertTrue(connectJob.isCancelled)
            verify { wireguardClient.turnOff(FAKE_HANDLE) }
            verify { wireguardClient.resetLogger() }
        }

    @Test
    fun `exception during handshake wait causes turnOff and resetLogger`() =
        runTest {
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            triggerHandshakeFailed()
            runCurrent()
            connectJob.join()

            verify { wireguardClient.turnOff(FAKE_HANDLE) }
            verify { wireguardClient.resetLogger() }
        }

    @Test
    fun `a failed connect clears the handle so the next connect does not turn off a dead tunnel`() =
        runTest {
            val first = launch { controller.connect(fakeConfig) }
            runCurrent()
            advanceTimeBy(HANDSHAKE_TIMEOUT_MS + 1)
            first.join()
            verify(exactly = 1) { wireguardClient.turnOff(FAKE_HANDLE) }

            val second = launch { controller.connect(fakeConfig) }
            runCurrent()
            triggerHandshakeSuccess()
            runCurrent()
            second.join()

            // Only the timed-out tunnel was turned off; the retry found no stale handle.
            verify(exactly = 1) { wireguardClient.turnOff(FAKE_HANDLE) }
        }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun triggerHandshakeSuccess() = loggerSlot.captured.onNewLog(0, "wg", HANDSHAKE_SUCCESS_LOG)

    private fun triggerHandshakeFailed() = loggerSlot.captured.onNewLog(0, "wg", HANDSHAKE_FAILURE_LOG)

    private fun triggerHandshakeRetrying() = loggerSlot.captured.onNewLog(0, "wg", "$HANDSHAKE_FAILURE_LOG retrying")

    // Runs connect() to completion with a successful handshake.
    // Uses runCurrent() — does not advance virtual time — so the backup timeout never fires.
    private fun TestScope.connectAndHandshake() {
        launch { controller.connect(fakeConfig) }
        runCurrent() // reach handshake.await()
        triggerHandshakeSuccess()
        runCurrent() // resume and complete connect()
    }

    companion object {
        private const val FAKE_HANDLE = 42
        private const val FAKE_FD = 10
        private const val HANDSHAKE_TIMEOUT_MS = 10_000L

        // Valid base64 encoding of 32 zero bytes — syntactically accepted by WireGuard key parsers
        private const val FAKE_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

        private const val HANDSHAKE_SUCCESS_LOG = "Received handshake response"
        private const val HANDSHAKE_FAILURE_LOG = "Handshake did not complete"

        private val fakeAuthConfig =
            WireGuardAuthConfiguration(
                psk = FAKE_KEY,
                serverPublicKey = FAKE_KEY,
                clientPrivateKey = FAKE_KEY,
                internalIp = "10.0.0.2/32",
            )

        private val fakeConfig =
            WireGuardVpnConfiguration(
                endpointConfiguration =
                    WireGuardEndpointConfiguration(
                        ip = IpAddress.V4("1.2.3.4"),
                        port = 51820,
                        authIp = IpAddress.V4("1.2.3.4"),
                        authPort = 443,
                        certDn = "test.example.com",
                        obfuscation = WireGuardObfuscation.None,
                    ),
                host = "1.2.3.4",
                port = 51820,
                obfuscation = WireGuardObfuscation.None,
            )
    }
}
