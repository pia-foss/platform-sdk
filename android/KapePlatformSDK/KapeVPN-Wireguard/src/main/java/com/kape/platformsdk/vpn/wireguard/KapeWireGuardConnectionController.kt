package com.kape.platformsdk.vpn.wireguard

import android.os.Build
import android.os.ParcelFileDescriptor
import com.kape.platformsdk.vpn.service.KapeSystemTunnel
import com.kape.platformsdk.vpn.service.NoOpVpnServiceLogger
import com.kape.platformsdk.vpn.service.VpnServiceLogger
import com.kape.platformsdk.vpn.service.analytics.AttemptResult
import com.kape.platformsdk.vpn.service.interfaces.ConnectionAttemptReporting
import com.kape.platformsdk.vpn.service.interfaces.ConnectionController
import com.kape.platformsdk.vpn.service.models.IpAddress
import com.kape.platformsdk.vpn.service.models.KapeVpnTrafficStats
import com.kape.platformsdk.vpn.service.redactPublicIps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.amnezia.awg.AwgLogger
import org.amnezia.awg.config.Config
import org.amnezia.awg.config.InetNetwork
import org.amnezia.awg.config.Interface
import org.amnezia.awg.config.Peer
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KClass

private const val HANDSHAKE_SUCCESS_LOG = "Received handshake response"
private const val HANDSHAKE_FAILURE_LOG = "Handshake did not complete"

// Timeout value for the connection to wait for a successful handshake from the Wireguard server
private const val HANDSHAKE_TIMEOUT_MS = 10_000L

// persistentKeepAlive = 15 to ensure a handshake is always initiated.
// Without it, WireGuard-Go waits for outbound traffic before sending the first
// handshake packet, which causes ~50% silent connection failures.
private const val PERSISTENT_KEEPALIVE_SECONDS = 15

private const val WIREGUARD_SESSION_NAME = "WgSession"

class KapeWireGuardConnectionController(
    private val systemTunnel: KapeSystemTunnel,
    private val authenticator: WireGuardAuthenticator,
    private val logger: VpnServiceLogger = NoOpVpnServiceLogger,
    private val wireguardClient: WireguardClient = GoBackendWireguardClient(),
) : ConnectionController<WireGuardVpnConfiguration> {
    override val configurationClass: KClass<WireGuardVpnConfiguration> = WireGuardVpnConfiguration::class
    override var attemptReporter: ConnectionAttemptReporting? = null

    @Volatile
    private var handle: Int? = null

    @Volatile
    private var currentGateway: IpAddress? = null

    // Completed once the first handshake succeeds, or exceptionally on failure/timeout.
    // Null outside of connect().
    private val handshakeDeferred = AtomicReference<CompletableDeferred<Unit>?>(null)

    // Drives runVPN(): completed with null on clean stop, or an error on post-connection failure.
    private val runVpnDeferred = AtomicReference<CompletableDeferred<Throwable?>?>(null)

    override suspend fun connect(configuration: WireGuardVpnConfiguration): Boolean {
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

    private suspend fun doConnect(configuration: WireGuardVpnConfiguration): Boolean {
        if (handle != null) logger.debug("Stopping existing tunnel before reconnect")
        handle?.let { wireguardClient.turnOff(it) }
        handle = null

        return try {
            logger.debug("Authenticating — endpoint=${configuration.host}:${configuration.port}")
            val authConfig =
                try {
                    authenticator.authenticate(configuration.endpointConfiguration)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e.toAuthFailureLog())
                    throw e
                }
            logger.debug("Authentication succeeded — starting WireGuard tunnel")
            val authenticated =
                configuration.copy(
                    serverPublicKeyBase64 = authConfig.serverPublicKey,
                    clientPrivateKeyBase64 = authConfig.clientPrivateKey,
                    presharedKeyBase64 = authConfig.psk,
                    internalIp = authConfig.internalIp,
                    // Pre-seeded DNS (custom DNS / Advanced Protection) wins over the auth response.
                    dnsServers = configuration.dnsServers.ifEmpty { authConfig.dnsServers },
                    gatewayIp = configuration.gatewayIp ?: authConfig.gatewayIp,
                    obfuscation = authConfig.obfuscation ?: configuration.obfuscation,
                )
            val dnsServers = authenticated.resolvedDnsServers
            if (dnsServers.isEmpty()) {
                logger.error("No DNS resolver could be derived — cannot connect")
                return false
            }

            val config = buildConfig(authenticated, dnsServers)
            val fd = establishTunnel(config, authenticated.gatewayIp)

            val handshake = CompletableDeferred<Unit>()
            val runDeferred = CompletableDeferred<Throwable?>()
            handshakeDeferred.set(handshake)
            runVpnDeferred.set(runDeferred)

            // The logger fires on WireGuard-Go's internal goroutine thread.
            // AtomicReference.getAndSet ensures each deferred is completed at most once.
            wireguardClient.setLogger(
                AwgLogger { _, _, msg ->
                    when {
                        msg.contains(HANDSHAKE_SUCCESS_LOG) -> {
                            logger.info("[wg] Handshake received")
                            handshakeDeferred.getAndSet(null)?.complete(Unit)
                        }

                        // WireGuard-Go's final "giving up" message (no "retrying" substring).
                        msg.contains(HANDSHAKE_FAILURE_LOG) && !msg.contains("retrying") -> {
                            if (handshakeDeferred.get() != null) {
                                logger.error("[wg] Handshake did not complete — failing fast")
                            } else {
                                logger.error("[wg] Connection lost — handshake failure")
                            }
                            handshakeDeferred
                                .getAndSet(null)
                                ?.completeExceptionally(WireGuardConnectionError.FirstHandshakeFailed())
                                ?: runDeferred.complete(WireGuardConnectionError.ConnectionLost())
                        }

                        // Per-attempt timeout (~5 s). During initial connect the UDP socket is
                        // rebound after setTunnelNetworkSettings, so the first retry typically
                        // succeeds — ignore it and let the backup timer handle a truly unreachable
                        // server. Post-connect, any retry timeout means the tunnel has dropped.
                        msg.contains(HANDSHAKE_FAILURE_LOG) && msg.contains("retrying") -> {
                            if (handshakeDeferred.get() == null) {
                                logger.error("[wg] Handshake timed out — connection lost")
                                runDeferred.complete(WireGuardConnectionError.ConnectionLost())
                            }
                        }
                    }
                },
            )

            val currentHandle = wireguardClient.turnOn(WIREGUARD_SESSION_NAME, fd, config.toAwgUserspaceString())
            if (currentHandle < 0) {
                logger.error("Failed to start WireGuard backend. Return code: $currentHandle")
                wireguardClient.resetLogger()
                ParcelFileDescriptor.adoptFd(fd).close()
                return false
            }

            if (!systemTunnel.protect(wireguardClient.socketV4(currentHandle)) ||
                !systemTunnel.protect(wireguardClient.socketV6(currentHandle))
            ) {
                logger.error("Failed to protect socket")
                wireguardClient.turnOff(currentHandle)
                wireguardClient.resetLogger()
                return false
            }

            handle = currentHandle

            logger.info("Waiting for handshake (timeout ${HANDSHAKE_TIMEOUT_MS}ms)")
            // withTimeoutOrNull returns null on timeout; handshake.await() may throw on failure.
            // Both cases return false; only a completed handshake returns true.
            val handshakeResult = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { handshake.await() }
            if (handshakeResult == null) {
                wireguardClient.turnOff(currentHandle)
                wireguardClient.resetLogger()
                handle = null
                logger.error("Handshake timed out after ${HANDSHAKE_TIMEOUT_MS}ms")
            } else {
                // Connection has been successful
                currentGateway = authenticated.gatewayIp
            }
            handshakeResult != null
        } catch (e: CancellationException) {
            handle?.let {
                wireguardClient.turnOff(it)
                wireguardClient.resetLogger()
            }
            handle = null
            throw e
        } catch (e: Exception) {
            handle?.let {
                wireguardClient.turnOff(it)
                wireguardClient.resetLogger()
            }
            handle = null
            logger.error("connect() failed: ${e::class.simpleName}")
            false
        }
    }

    override suspend fun runVPN(): Throwable? = runVpnDeferred.get()?.await()

    override suspend fun stop() {
        logger.debug("stop() invoked")
        handle?.let {
            wireguardClient.turnOff(it)
            wireguardClient.resetLogger()
        }
        handle = null
        currentGateway = null
        handshakeDeferred.getAndSet(null)?.cancel()
        runVpnDeferred.getAndSet(null)?.complete(null)
    }

    override fun getGateway(): IpAddress? = currentGateway

    override fun getTrafficStats(): KapeVpnTrafficStats? {
        val currentHandle = handle ?: return null
        return wireguardClient.getConfig(currentHandle)?.let { parseTrafficStats(it) }
    }

    private fun parseTrafficStats(config: String): KapeVpnTrafficStats {
        var bytesReceived = 0L
        var bytesSent = 0L
        config.lineSequence().forEach { line ->
            when {
                line.startsWith("rx_bytes=") -> bytesReceived += line.substringAfter("=").trim().toLongOrNull() ?: 0L
                line.startsWith("tx_bytes=") -> bytesSent += line.substringAfter("=").trim().toLongOrNull() ?: 0L
            }
        }
        return KapeVpnTrafficStats(bytesReceived = bytesReceived, bytesSent = bytesSent)
    }

    private fun buildConfig(
        configuration: WireGuardVpnConfiguration,
        dnsServers: List<String>,
    ): Config {
        val peer =
            Peer
                .Builder()
                .addAllowedIps(configuration.allowedIPs.map { InetNetwork.parse(it) })
                .parseEndpoint("${configuration.host}:${configuration.port}")
                .apply { configuration.presharedKeyBase64?.let { parsePreSharedKey(it) } }
                .parsePublicKey(configuration.serverPublicKeyBase64)
                .setPersistentKeepalive(PERSISTENT_KEEPALIVE_SECONDS)
                .build()

        val iface =
            Interface
                .Builder()
                .parseAddresses(configuration.internalIp)
                // Comma-separated so a multi-resolver list survives.
                .parseDnsServers(dnsServers.joinToString(","))
                .setMtu(configuration.mtu)
                .parsePrivateKey(configuration.clientPrivateKeyBase64)
                .apply {
                    (configuration.obfuscation as? WireGuardObfuscation.Amnezia)?.let { amnezia ->
                        setInitPacketJunkSize(amnezia.initPacketJunkSize.toInt())
                        setResponsePacketJunkSize(amnezia.responsePacketJunkSize.toInt())
                        setJunkPacketCount(amnezia.junkPacketCount.toInt())
                        setJunkPacketMinSize(amnezia.junkPacketMinSize.toInt())
                        setJunkPacketMaxSize(amnezia.junkPacketMaxSize.toInt())
                        // Magic headers take a String since AmneziaWG 0.3.3 — stored verbatim, so
                        // the decimal rendering is the same value the Long overload wrote before.
                        setInitPacketMagicHeader(amnezia.initPacketMagicHeader.toString())
                        setResponsePacketMagicHeader(amnezia.responsePacketMagicHeader.toString())
                        setUnderloadPacketMagicHeader(amnezia.underloadPacketMagicHeader.toString())
                        setTransportPacketMagicHeader(amnezia.transportPacketMagicHeader.toString())
                    }
                }.build()

        return Config
            .Builder()
            .setInterface(iface)
            .addPeer(peer)
            .build()
    }

    private suspend fun establishTunnel(
        config: Config,
        gatewayIp: IpAddress?,
    ): Int {
        val iface = config.getInterface()
        val builder = systemTunnel.newBuilder()

        iface.addresses.forEach { builder.addAddress(it.address, it.mask) }
        iface.dnsServers.forEach { builder.addDnsServer(it) }
        config.peers.forEach { peer ->
            peer.allowedIps.forEach { builder.addRoute(it.address, it.mask) }
        }
        // The gateway is used by PIA to trigger port forwarding once connected. It's a private
        // IP inside the VPN server's own subnet, so under Off/Standard kill switch mode it would
        // otherwise fall into the excluded local-IP ranges and be unreachable through the tunnel.
        // An explicit host route is more specific than those ranges and always wins.
        when (gatewayIp) {
            is IpAddress.V4 -> builder.addRoute(gatewayIp.value, 32)
            is IpAddress.V6 -> builder.addRoute(gatewayIp.value, 128)
            null -> {}
        }
        iface.mtu.ifPresent { builder.setMtu(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        return builder.establish()?.detachFd()
            ?: throw WireGuardConnectionError.TunnelEstablishFailed()
    }
}

// The host-app logger may not redact, and an authenticator's message typically embeds the auth
// server's public IP — strip that, but keep private IPs and the rest of the message for diagnosis.
internal fun Exception.toAuthFailureLog(): String {
    val status = (this as? WireGuardAuthenticationException)?.httpStatus?.toString() ?: "n/a"
    val detail = message?.redactPublicIps() ?: "no message"
    return "Authentication failed: ${this::class.simpleName} httpStatus=$status message=$detail"
}

sealed class WireGuardConnectionError : Exception() {
    data class BadParameters(
        override val message: String,
    ) : WireGuardConnectionError()

    class FirstHandshakeFailed : WireGuardConnectionError()

    class ConnectionLost : WireGuardConnectionError()

    class FailedToStartBackend : WireGuardConnectionError()

    class TunnelEstablishFailed : WireGuardConnectionError()
}
