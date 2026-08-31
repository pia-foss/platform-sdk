package com.kape.platformsdk.vpn.openvpn

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.ParcelFileDescriptor
import com.kape.openvpn.data.models.OpenVpnServerPeerInformation
import com.kape.openvpn.presenters.OpenVpnAPI
import com.kape.openvpn.presenters.OpenVpnCallback
import com.kape.openvpn.presenters.OpenVpnProcessEventHandler
import com.kape.openvpn.presenters.OpenVpnState
import com.kape.platformsdk.vpn.service.KapeSystemTunnel
import com.kape.platformsdk.vpn.service.KapeTunnelBuilder
import com.kape.platformsdk.vpn.service.models.KapeVpnTrafficStats
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OpenVpnConnectionControllerTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var systemTunnel: KapeSystemTunnel
    private lateinit var openVpnApi: OpenVpnAPI
    private lateinit var mockBuilder: KapeTunnelBuilder
    private lateinit var mockParcelFd: ParcelFileDescriptor
    private lateinit var eventHandlerSlot: CapturingSlot<OpenVpnProcessEventHandler>
    private lateinit var startCallbackSlot: CapturingSlot<OpenVpnCallback>
    private lateinit var paramsSlot: CapturingSlot<List<String>>

    @Before
    fun setup() {
        context = mockk()
        systemTunnel = mockk()
        openVpnApi = mockk()
        mockBuilder = mockk()
        mockParcelFd = mockk()
        eventHandlerSlot = slot()
        startCallbackSlot = slot()
        paramsSlot = slot()

        val appInfo = ApplicationInfo()
        appInfo.dataDir = tmpDir.root.absolutePath
        every { context.applicationInfo } returns appInfo
        every { context.cacheDir } returns tmpDir.root

        every { systemTunnel.protect(any<Int>()) } returns true
        every { systemTunnel.newBuilder() } returns mockBuilder
        every { mockBuilder.addAddress(any<String>(), any()) } returns mockBuilder
        every { mockBuilder.addRoute(any<String>(), any()) } returns mockBuilder
        every { mockBuilder.addDnsServer(any<String>()) } returns mockBuilder
        every { mockBuilder.setMtu(any()) } returns mockBuilder
        coEvery { mockBuilder.establish() } returns mockParcelFd
        every { mockParcelFd.detachFd() } returns FAKE_FD

        every {
            openVpnApi.start(capture(paramsSlot), capture(eventHandlerSlot), capture(startCallbackSlot))
        } just Runs
        val stopCallbackSlot = slot<OpenVpnCallback>()
        every { openVpnApi.stop(capture(stopCallbackSlot)) } answers {
            stopCallbackSlot.captured.invoke(Result.success(Unit))
        }
    }

    private fun TestScope.createController() =
        OpenVpnConnectionController(
            context = context,
            systemTunnel = systemTunnel,
            coroutineScope = this,
            openVpnApi = openVpnApi,
        )

    // ── connect() ──────────────────────────────────────────────────────────────

    @Test
    fun `connect suspends until processConnected is called`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            assertFalse(connectJob.isCompleted)

            // cleanup
            triggerProcessConnected()
            runCurrent()
        }

    @Test
    fun `connect returns true when processConnected is called`() =
        runTest {
            val controller = createController()
            var result: Boolean? = null
            val connectJob = launch { result = controller.connect(fakeConfig) }
            runCurrent()

            triggerProcessConnected()
            runCurrent()
            connectJob.join()

            assertEquals(true, result)
        }

    @Test
    fun `connect returns false when startup fails before connection`() =
        runTest {
            val controller = createController()
            var result: Boolean? = null
            val connectJob = launch { result = controller.connect(fakeConfig) }
            runCurrent()

            triggerStartupFailure()
            runCurrent()
            connectJob.join()

            assertEquals(false, result)
        }

    @Test
    fun `connect returns false on connection timeout`() =
        runTest {
            val controller = createController()
            var result: Boolean? = null
            val connectJob = launch { result = controller.connect(fakeConfig) }
            runCurrent()

            advanceTimeBy(CONNECTION_TIMEOUT_MS + 1)
            connectJob.join()

            assertEquals(false, result)
        }

    @Test
    fun `stop is called on connection timeout`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            advanceTimeBy(CONNECTION_TIMEOUT_MS + 1)
            connectJob.join()

            // once at start of connect() (stopInternal) + once after timeout
            verify(atLeast = 2) { openVpnApi.stop(any()) }
        }

    @Test
    fun `CancellationException during connect is rethrown and stop is called`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent() // suspend at connectDeferred.await()

            connectJob.cancel()
            connectJob.join()

            assertTrue(connectJob.isCancelled)
            verify(atLeast = 1) { openVpnApi.stop(any()) }
        }

    @Test
    fun `stateUpdated Exiting during connect phase returns false`() =
        runTest {
            val controller = createController()
            var result: Boolean? = null
            val connectJob = launch { result = controller.connect(fakeConfig) }
            runCurrent()

            eventHandlerSlot.captured.stateUpdated(OpenVpnState.Exiting)
            runCurrent()
            connectJob.join()

            assertEquals(false, result)
        }

    // ── runVPN() ───────────────────────────────────────────────────────────────

    @Test
    fun `runVPN returns ProcessDied when process dies post-connection`() =
        runTest {
            val controller = createController()
            connectAndSuccess(controller)

            var result: Throwable? = null
            val runVpnJob = launch { result = controller.runVPN() }
            runCurrent()

            triggerProcessDied()
            runCurrent()
            runVpnJob.join()

            assertTrue(result is OpenVpnConnectionError.ProcessDied)
        }

    @Test
    fun `stateUpdated Exiting post-connection completes runVPN with ProcessDied`() =
        runTest {
            val controller = createController()
            connectAndSuccess(controller)

            var result: Throwable? = null
            val runVpnJob = launch { result = controller.runVPN() }
            runCurrent()

            eventHandlerSlot.captured.stateUpdated(OpenVpnState.Exiting)
            runCurrent()
            runVpnJob.join()

            assertTrue(result is OpenVpnConnectionError.ProcessDied)
        }

    @Test
    fun `stateUpdated Reconnecting stops OpenVPN and signals run loop to reconnect`() =
        runTest {
            val controller = createController()
            connectAndSuccess(controller)

            var result: Throwable? = null
            val runVpnJob = launch { result = controller.runVPN() }
            runCurrent()

            eventHandlerSlot.captured.stateUpdated(OpenVpnState.Reconnecting)
            runCurrent()
            runVpnJob.join()

            assertTrue(result is OpenVpnConnectionError.ConnectionDropped)
            verify(atLeast = 1) { openVpnApi.stop(any()) }
        }

    @Test
    fun `runVPN returns null on stop`() =
        runTest {
            val controller = createController()
            connectAndSuccess(controller)

            val results = mutableListOf<Throwable?>()
            val runVpnJob = launch { results.add(controller.runVPN()) }
            runCurrent()

            controller.stop()
            runCurrent()
            runVpnJob.join()

            assertEquals(1, results.size)
            assertNull(results.first())
        }

    // ── stop() ─────────────────────────────────────────────────────────────────

    @Test
    fun `stop calls openVpnApi stop`() =
        runTest {
            val controller = createController()
            controller.stop()

            verify { openVpnApi.stop(any()) }
        }

    @Test
    fun `config file is deleted on stop`() =
        runTest {
            val controller = createController()
            connectAndSuccess(controller)

            val ovpnFilesBeforeStop = tmpDir.root.listFiles { f -> f.name.endsWith(".ovpn") } ?: emptyArray()
            assertTrue(ovpnFilesBeforeStop.isNotEmpty())

            controller.stop()

            val ovpnFilesAfterStop = tmpDir.root.listFiles { f -> f.name.endsWith(".ovpn") } ?: emptyArray()
            assertTrue(ovpnFilesAfterStop.isEmpty())
        }

    // ── getTrafficStats() ──────────────────────────────────────────────────────

    @Test
    fun `getTrafficStats returns null before connect`() =
        runTest {
            val controller = createController()
            assertNull(controller.getTrafficStats())
        }

    @Test
    fun `getTrafficStats returns stats reported by processByteCountReceived`() =
        runTest {
            val controller = createController()
            connectAndSuccess(controller)

            eventHandlerSlot.captured.processByteCountReceived(tx = 5678, rx = 1234)

            assertEquals(KapeVpnTrafficStats(bytesReceived = 1234, bytesSent = 5678), controller.getTrafficStats())
        }

    @Test
    fun `getTrafficStats returns null after stop`() =
        runTest {
            val controller = createController()
            connectAndSuccess(controller)
            eventHandlerSlot.captured.processByteCountReceived(tx = 5678, rx = 1234)

            controller.stop()

            assertNull(controller.getTrafficStats())
        }

    // ── event handler delegation ───────────────────────────────────────────────

    @Test
    fun `serviceProtect delegates to systemTunnel`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            val result = eventHandlerSlot.captured.serviceProtect(FAKE_FD)

            assertTrue(result.isSuccess)
            assertEquals(true, result.getOrNull())
            verify { systemTunnel.protect(FAKE_FD) }

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `serviceProtect returns false result when socket protection fails`() =
        runTest {
            val controller = createController()
            every { systemTunnel.protect(any<Int>()) } returns false
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            val result = eventHandlerSlot.captured.serviceProtect(FAKE_FD)

            assertTrue(result.isSuccess)
            assertEquals(false, result.getOrNull())

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `serviceEstablish returns fd from tunnel builder`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            val result = eventHandlerSlot.captured.serviceEstablish(fakePeerInfo)

            assertTrue(result.isSuccess)
            assertEquals(FAKE_FD, result.getOrNull())

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `serviceEstablish falls back to peer gateway when config has no dns servers`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            eventHandlerSlot.captured.serviceEstablish(fakePeerInfo)

            verify(exactly = 1) { mockBuilder.addDnsServer(fakePeerInfo.gateway) }

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `serviceEstablish uses configured dns servers instead of peer gateway`() =
        runTest {
            val controller = createController()
            val config = fakeConfig.copy(dnsServers = listOf("10.0.0.243", "10.0.0.242"))
            val connectJob = launch { controller.connect(config) }
            runCurrent()

            eventHandlerSlot.captured.serviceEstablish(fakePeerInfo)

            verify(exactly = 1) { mockBuilder.addDnsServer("10.0.0.243") }
            verify(exactly = 1) { mockBuilder.addDnsServer("10.0.0.242") }
            verify(exactly = 0) { mockBuilder.addDnsServer(fakePeerInfo.gateway) }

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `serviceEstablish returns failure when tunnel establish returns null`() =
        runTest {
            val controller = createController()
            coEvery { mockBuilder.establish() } returns null
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            val result = eventHandlerSlot.captured.serviceEstablish(fakePeerInfo)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is OpenVpnConnectionError.TunnelEstablishFailed)

            // cleanup
            triggerStartupFailure()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `serviceEstablish registers a slash-32 host route for a v4 gateway`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            eventHandlerSlot.captured.serviceEstablish(fakePeerInfo)

            verify(exactly = 1) { mockBuilder.addRoute(fakePeerInfo.gateway, 32) }

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `serviceEstablish registers a slash-128 host route for a v6 gateway`() =
        runTest {
            val controller = createController()
            val v6PeerInfo = fakePeerInfo.copy(gateway = "fe80::1")
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            eventHandlerSlot.captured.serviceEstablish(v6PeerInfo)

            verify(exactly = 1) { mockBuilder.addRoute("fe80::1", 128) }

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `getUserCredentials returns configured username and password`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            val result = eventHandlerSlot.captured.getUserCredentials()

            assertTrue(result.isSuccess)
            assertEquals(FAKE_USERNAME, result.getOrNull()?.username)
            assertEquals(FAKE_PASSWORD, result.getOrNull()?.password)

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    // ── command line params ────────────────────────────────────────────────────

    @Test
    fun `connect passes xor scramble params when xorValue is set`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig.copy(xorValue = 0xABL)) }
            runCurrent()

            val params = paramsSlot.captured
            assertTrue(params.contains("--scramble"))
            assertTrue(params.contains("xormask"))
            assertTrue(params.contains("ab"))

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `connect does not include scramble params when xorValue is null`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig) }
            runCurrent()

            assertFalse(paramsSlot.captured.contains("--scramble"))

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    // ── config sanitization ────────────────────────────────────────────────────

    @Test
    fun `removed directives are filtered from config file`() =
        runTest {
            val controller = createController()
            val dirtyConfig = "client\nkeysize 256\nncp-disable\nns-cert-type server\nroute-method exe"
            val connectJob =
                launch {
                    controller.connect(fakeConfig.copy(ovpnConfiguration = dirtyConfig))
                }
            runCurrent()

            val content = readConfigFileContent()
            assertFalse(content.contains("keysize"))
            assertFalse(content.contains("ncp-disable"))
            assertFalse(content.contains("ns-cert-type"))
            assertFalse(content.contains("route-method"))

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `PKI directives are replaced with inline blocks in config file`() =
        runTest {
            val controller = createController()
            val configWithPkiPaths = "client\nca /tmp/ca.crt\ncert /tmp/client.crt\nkey /tmp/client.key"
            val connectJob =
                launch {
                    controller.connect(fakeConfig.copy(ovpnConfiguration = configWithPkiPaths))
                }
            runCurrent()

            val content = readConfigFileContent()
            // File-path directives must be stripped
            assertFalse(content.contains("ca /tmp/ca.crt"))
            assertFalse(content.contains("cert /tmp/client.crt"))
            assertFalse(content.contains("key /tmp/client.key"))
            // Inline blocks must be present
            assertTrue(content.contains("<ca>"))
            assertTrue(content.contains("<cert>"))
            assertTrue(content.contains("<key>"))

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `PKI certificate and key content is written into the correct inline blocks`() =
        runTest {
            val controller = createController()
            val config =
                fakeConfig.copy(
                    caCertificate = "CA_CERT_CONTENT",
                    clientCertificate = "CLIENT_CERT_CONTENT",
                    clientKey = "CLIENT_KEY_CONTENT",
                )
            val connectJob = launch { controller.connect(config) }
            runCurrent()

            val content = readConfigFileContent()
            assertInlineBlock(content, "ca", "CA_CERT_CONTENT")
            assertInlineBlock(content, "cert", "CLIENT_CERT_CONTENT")
            assertInlineBlock(content, "key", "CLIENT_KEY_CONTENT")

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `tls-auth inline block is written with key-direction when tlsAuthKey is set`() =
        runTest {
            val controller = createController()
            val connectJob =
                launch {
                    controller.connect(fakeConfig.copy(tlsAuthKey = FAKE_TLS_AUTH_KEY))
                }
            runCurrent()

            val content = readConfigFileContent()
            assertTrue(content.contains("key-direction 1"))
            assertTrue(content.contains("<tls-auth>"))
            assertTrue(content.contains(FAKE_TLS_AUTH_KEY))

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `tls-auth block is omitted when tlsAuthKey is empty`() =
        runTest {
            val controller = createController()
            val connectJob = launch { controller.connect(fakeConfig) } // fakeConfig has empty tlsAuthKey
            runCurrent()

            val content = readConfigFileContent()
            assertFalse(content.contains("<tls-auth>"))
            assertFalse(content.contains("key-direction"))

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    @Test
    fun `CertDN is added to the config name verification`() =
        runTest {
            val controller = createController()
            val config =
                fakeConfig.copy(
                    certDn = "domainToTest.com",
                )
            val connectJob = launch { controller.connect(config) }
            runCurrent()

            val params = paramsSlot.captured
            assertTrue(params.contains("--verify-x509-name"))
            assertTrue(params.contains("domainToTest.com"))
            assertTrue(params.contains("name"))

            // cleanup
            triggerProcessConnected()
            runCurrent()
            connectJob.join()
        }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun triggerProcessConnected() {
        eventHandlerSlot.captured.stateUpdated(OpenVpnState.Connected)
    }

    private fun triggerStartupFailure() {
        startCallbackSlot.captured.invoke(Result.failure(RuntimeException("startup failed")))
    }

    private fun triggerProcessDied() {
        startCallbackSlot.captured.invoke(Result.failure(RuntimeException("process died")))
    }

    private fun assertInlineBlock(
        config: String,
        tag: String,
        content: String,
    ) {
        assertTrue(
            config.contains("<$tag>\n${content.trim()}\n</$tag>"),
            "Expected inline <$tag> block containing \"$content\"",
        )
    }

    private fun readConfigFileContent(): String {
        val configIdx = paramsSlot.captured.indexOf("--config")
        return File(paramsSlot.captured[configIdx + 1]).readText()
    }

    private fun TestScope.connectAndSuccess(controller: OpenVpnConnectionController) {
        launch { controller.connect(fakeConfig) }
        runCurrent()
        triggerProcessConnected()
        runCurrent()
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val FAKE_FD = 10
        private const val FAKE_USERNAME = "test_user"
        private const val FAKE_PASSWORD = "test_pass"
        private const val FAKE_TLS_AUTH_KEY = "fake-tls-key-content"

        private val fakePeerInfo =
            OpenVpnServerPeerInformation(
                address = "10.0.0.2/24",
                gateway = "10.0.0.1",
            )

        private val fakeConfig =
            OpenVpnConfiguration(
                host = "1.2.3.4",
                port = 1194,
                transport = OpenVpnTransport.UDP,
                ovpnConfiguration = "client",
                xorValue = null,
                mtu = 1500,
                certDn = "test.example.com",
                username = FAKE_USERNAME,
                password = FAKE_PASSWORD,
                caCertificate = "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----",
                clientCertificate = "-----BEGIN CERTIFICATE-----\ntest\n-----END CERTIFICATE-----",
                clientKey = "-----BEGIN PRIVATE KEY-----\ntest\n-----END PRIVATE KEY-----",
                tlsAuthKey = "",
            )
    }
}
