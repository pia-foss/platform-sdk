package com.kape.platformsdk.vpn.openvpn

import android.content.Context
import com.kape.openvpn.data.models.OpenVpnServerPeerInformation
import com.kape.openvpn.domain.usecases.IOpenVpnMtuTestResultAnnouncer
import com.kape.openvpn.presenters.OpenVpnAPI
import com.kape.openvpn.presenters.OpenVpnBuilder
import com.kape.openvpn.presenters.OpenVpnProcessEventHandler
import com.kape.openvpn.presenters.OpenVpnState
import com.kape.openvpn.presenters.OpenVpnUserCredentials
import com.kape.platformsdk.vpn.service.KapeSystemTunnel
import com.kape.platformsdk.vpn.service.NoOpVpnServiceLogger
import com.kape.platformsdk.vpn.service.VpnServiceLogger
import com.kape.platformsdk.vpn.service.analytics.AttemptResult
import com.kape.platformsdk.vpn.service.interfaces.ConnectionAttemptReporting
import com.kape.platformsdk.vpn.service.interfaces.ConnectionController
import com.kape.platformsdk.vpn.service.models.IpAddress
import com.kape.platformsdk.vpn.service.models.KapeVpnTrafficStats
import com.kape.platformsdk.vpn.service.models.isIpv4
import com.kape.platformsdk.vpn.service.models.toIpAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.reflect.KClass

class OpenVpnConnectionController(
    private val context: Context,
    private val systemTunnel: KapeSystemTunnel,
    private val coroutineScope: CoroutineScope,
    private val logger: VpnServiceLogger = NoOpVpnServiceLogger,
    openVpnApi: OpenVpnAPI? = null,
) : ConnectionController<OpenVpnConfiguration> {
    override val configurationClass: KClass<OpenVpnConfiguration> = OpenVpnConfiguration::class
    override var attemptReporter: ConnectionAttemptReporting? = null

    private val openVpnApi: OpenVpnAPI =
        openVpnApi ?: OpenVpnBuilder()
            .setContext(context)
            .setClientCoroutineContext(coroutineScope.coroutineContext)
            .setOpenVpnMtuTestResultAnnouncer(
                object : IOpenVpnMtuTestResultAnnouncer {
                    override fun onMtuTestResult(
                        localToRemote: Int,
                        remoteToLocal: Int,
                    ) {
                        logger.debug("MTU test: local→remote=$localToRemote remote→local=$remoteToLocal")
                    }
                },
            ).build()
    private val runVpnDeferredRef = AtomicReference<CompletableDeferred<Throwable?>?>()
    private var currentConfigFile: File? = null

    @Volatile
    private var currentGateway: IpAddress? = null

    @Volatile
    private var currentTrafficStats: KapeVpnTrafficStats? = null

    override suspend fun connect(configuration: OpenVpnConfiguration): Boolean {
        val attemptId = attemptReporter?.reportAttemptBegin(configuration)
        val startMs = System.currentTimeMillis()
        val connected =
            try {
                doConnect(configuration)
            } catch (e: CancellationException) {
                attemptId?.let {
                    attemptReporter?.reportAttemptEnd(it, AttemptResult.Cancelled, System.currentTimeMillis() - startMs)
                }
                throw e
            }
        attemptId?.let {
            val result = if (connected) AttemptResult.Connected else AttemptResult.Failed()
            attemptReporter?.reportAttemptEnd(it, result, System.currentTimeMillis() - startMs, configuration)
        }
        return connected
    }

    private suspend fun doConnect(configuration: OpenVpnConfiguration): Boolean {
        stopInternal()

        val configFile = createConfigFile(configuration)
        currentConfigFile = configFile

        val connectDeferred = CompletableDeferred<Boolean>()
        val runVpnDeferred = CompletableDeferred<Throwable?>()
        runVpnDeferredRef.set(runVpnDeferred)

        val params = buildCommandLineParams(configuration, configFile)
        logger.debug("Starting OpenVPN — host=${configuration.host} port=${configuration.port} transport=${configuration.transport}")

        val eventHandler =
            object : OpenVpnProcessEventHandler {
                override fun serviceProtect(fd: Int): Result<Boolean> {
                    val protected = systemTunnel.protect(fd)
                    if (!protected) logger.error("Failed to protect socket fd=$fd")
                    return Result.success(protected)
                }

                // Invoked from OpenVPN's own management thread, not the main thread.
                override fun serviceEstablish(serverPeerInformation: OpenVpnServerPeerInformation): Result<Int> =
                    try {
                        val fd =
                            runBlocking {
                                establishTunnel(serverPeerInformation, configuration.mtu, configuration.dnsServers)
                            }
                        logger.debug("Tunnel established — address=${serverPeerInformation.address}")
                        currentGateway = serverPeerInformation.gateway.toIpAddress()
                        Result.success(fd)
                    } catch (e: Exception) {
                        logger.error("Failed to establish tunnel: ${e.message}")
                        Result.failure(e)
                    }

                override fun getUserCredentials(): Result<OpenVpnUserCredentials> =
                    Result.success(OpenVpnUserCredentials(configuration.username, configuration.password))

                override fun stateUpdated(state: OpenVpnState): Result<Unit> {
                    logger.debug("New OpenVPN state: $state")
                    when (state) {
                        is OpenVpnState.Connected -> {
                            logger.info("OpenVPN handshake complete")
                            connectDeferred.complete(true)
                        }

                        is OpenVpnState.Exiting -> {
                            logger.info("OpenVPN is exiting — completing connect()/runVpn()")
                            if (!connectDeferred.complete(false)) {
                                runVpnDeferredRef.get()?.complete(OpenVpnConnectionError.ProcessDied(null))
                            }
                        }

                        is OpenVpnState.Reconnecting -> {
                            // OpenVPN is able to reconnect on its own to the same endpoint however if we are encountering a case where a server
                            // is going out of rotation then we might get stuck trying to reconnect
                            // By force stopping the connection, we can go back to the session controller run-loop and get potentially fresh
                            // endpoints to connect to
                            // This has the side effect to also put the session controller's connection status to reconnecting as expected
                            logger.info("OpenVPN wants to auto-reconnect — forcing to connection to stop to maybe get new endpoints")
                            openVpnApi.stop { runVpnDeferredRef.get()?.complete(OpenVpnConnectionError.ConnectionDropped()) }
                        }

                        else -> {}
                    }
                    return Result.success(Unit)
                }

                // tx = bytes transmitted (sent) by the client, rx = bytes received — reported by
                // the OpenVPN management interface roughly once per second while connected.
                override fun processByteCountReceived(
                    tx: Long,
                    rx: Long,
                ): Result<Unit> {
                    currentTrafficStats = KapeVpnTrafficStats(bytesReceived = rx, bytesSent = tx)
                    return Result.success(Unit)
                }

                override fun openVpnProcessOutputLineReceived(line: String): Result<Unit> {
                    if (line.startsWith(">BYTECOUNT:")) {
                        // BYTECOUNT returns how many bytes are going through the tunnel and is quite noisy
                        // We skip those messages
                        return Result.success(Unit)
                    }
                    logger.debug(line)
                    return Result.success(Unit)
                }
            }

        openVpnApi.start(params, eventHandler) { result ->
            result.onFailure { error ->
                // complete(false) returns true if the connect phase was still in progress,
                // meaning this is a startup failure. If it returns false, we're already
                // connected and this is a post-connection process death.
                if (!connectDeferred.complete(false)) {
                    logger.error("OpenVPN process died post-connection: ${error.message}")
                    runVpnDeferred.complete(OpenVpnConnectionError.ProcessDied(error))
                } else {
                    logger.error("OpenVPN process failed to start: ${error.message}")
                }
            }
        }

        return try {
            val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { connectDeferred.await() }
            if (connected == null) {
                logger.error("OpenVPN connection timed out after ${CONNECTION_TIMEOUT_MS}ms")
                stopInternal()
            }
            connected ?: false
        } catch (e: CancellationException) {
            stopInternal()
            throw e
        }
    }

    override suspend fun runVPN(): Throwable? = runVpnDeferredRef.get()?.await()

    override suspend fun stop() {
        logger.debug("Stopping OpenVPN connection")
        stopInternal()
    }

    override fun getGateway(): IpAddress? = currentGateway

    override fun getTrafficStats(): KapeVpnTrafficStats? = currentTrafficStats

    private suspend fun stopInternal() {
        suspendCancellableCoroutine { cont ->
            openVpnApi.stop { cont.resume(Unit) }
        }
        runVpnDeferredRef.getAndSet(null)?.complete(null)
        currentConfigFile?.delete()
        currentConfigFile = null
        currentGateway = null
        currentTrafficStats = null
    }

    private fun createConfigFile(configuration: OpenVpnConfiguration): File =
        File.createTempFile("openvpn_", ".ovpn", context.cacheDir).also {
            it.writeText(sanitizeConfig(configuration))
        }

    private fun sanitizeConfig(configuration: OpenVpnConfiguration): String {
        val filteredLines =
            configuration.ovpnConfiguration
                .lines()
                .filter { line ->
                    val directive =
                        line
                            .trim()
                            .lowercase()
                            .split(Regex("\\s+"))
                            .firstOrNull() ?: return@filter true
                    directive !in REMOVED_DIRECTIVES && directive !in PKI_FILE_DIRECTIVES
                }.joinToString("\n")

        return buildString {
            append(filteredLines)
            inlineBlock("ca", configuration.caCertificate)
            inlineBlock("cert", configuration.clientCertificate)
            inlineBlock("key", configuration.clientKey)
            if (configuration.tlsAuthKey.isNotEmpty()) {
                appendLine()
                appendLine("key-direction 1")
                appendLine("<tls-auth>")
                appendLine(configuration.tlsAuthKey.trim())
                append("</tls-auth>")
            }
        }
    }

    private fun StringBuilder.inlineBlock(
        tag: String,
        content: String,
    ) {
        if (content.isEmpty()) return
        appendLine()
        appendLine("<$tag>")
        appendLine(content.trim())
        append("</$tag>")
    }

    private fun buildCommandLineParams(
        configuration: OpenVpnConfiguration,
        configFile: File,
    ): List<String> =
        buildList {
            add("--config")
            add(configFile.absolutePath)
            add("--tmp-dir")
            add(context.cacheDir.absolutePath)
            // Management interface — required by the PIA OpenVPN library to handle
            // credentials, protect, establish, and state transitions via its socket handler.
            add("--management")
            add("${context.applicationInfo.dataDir}/management")
            add("unix")
            add("--management-query-passwords")
            add("--management-forget-disconnect")
            add("--management-hold")
            add("--status-version")
            add("3")
            add("--machine-readable-output")
            add("--auth-user-pass")
            add("--remote")
            add(configuration.host)
            add(configuration.port.toString())
            add(if (configuration.transport == OpenVpnTransport.TCP) "tcp" else "udp")
            if (configuration.mtu > 0) {
                add("--tun-mtu")
                add(configuration.mtu.toString())
            }
            configuration.xorValue?.let {
                add("--scramble")
                add("xormask")
                add(it.toString(16))
            }
            if (configuration.certDn.isNotEmpty()) {
                add("--verify-x509-name")
                add(configuration.certDn)
                add("name")
            }
        }

    private suspend fun establishTunnel(
        peerInfo: OpenVpnServerPeerInformation,
        mtu: Int,
        dnsServers: List<String>,
    ): Int {
        val (ip, prefix) = parseAddressWithPrefix(peerInfo.address)
        val builder =
            systemTunnel
                .newBuilder()
                .addAddress(ip, prefix)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
        // peerInfo.gateway is the OpenVPN tunnel gateway, not necessarily a DNS resolver —
        // fall back to it only if the config didn't supply real resolvers.
        dnsServers.ifEmpty { listOf(peerInfo.gateway) }.forEach { builder.addDnsServer(it) }
        // The gateway is used by PIA to trigger port forwarding once connected. It's a private
        // IP inside the VPN server's own subnet, so under Off/Standard kill switch mode it would
        // otherwise fall into the excluded local-IP ranges and be unreachable through the tunnel,
        // regardless of whether it was also added above as a DNS server. An explicit host route
        // is more specific than those ranges and always wins.
        builder.addRoute(peerInfo.gateway, if (peerInfo.gateway.isIpv4()) 32 else 128)
        return builder
            .also { if (mtu > 0) it.setMtu(mtu) }
            .establish()
            ?.detachFd()
            ?: throw OpenVpnConnectionError.TunnelEstablishFailed()
    }

    private fun parseAddressWithPrefix(address: String): Pair<String, Int> {
        val idx = address.indexOf('/')
        return if (idx >= 0) {
            address.substring(0, idx) to address.substring(idx + 1).toInt()
        } else {
            if (address.isIpv4()) {
                address to 32
            } else {
                // Assume IPv6
                address to 128
            }
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 10_000L

        // Directives removed in OpenVPN 2.6+ or vendor-specific — cause fatal parse errors on 2.7
        private val REMOVED_DIRECTIVES =
            setOf(
                "keysize", // removed in 2.6
                "ncp-disable", // removed in 2.6
                "expressvpn", // vendor-specific tag
                "route-method", // Windows-only
                "ns-cert-type", // deprecated since 2.4; replaced by remote-cert-tls
            )

        // PKI directives that reference file paths we replace with inline blocks
        private val PKI_FILE_DIRECTIVES = setOf("ca", "cert", "key", "tls-auth")
    }
}

sealed class OpenVpnConnectionError(
    cause: Throwable? = null,
) : Exception(cause) {
    class TunnelEstablishFailed : OpenVpnConnectionError()

    class ProcessDied(
        cause: Throwable?,
    ) : OpenVpnConnectionError(cause)

    class ConnectionDropped : OpenVpnConnectionError()
}
