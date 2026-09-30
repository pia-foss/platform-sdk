package com.kape.platformsdk.vpn.service

import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.kape.platformsdk.vpn.service.models.KapeKillSwitchMode
import com.kape.platformsdk.vpn.service.models.KapeSplitTunnelAppMode
import com.kape.platformsdk.vpn.service.models.isIpv4
import com.kape.platformsdk.vpn.service.models.normalizedDnsServers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val NETWORK_LOCK_SESSION_NAME = "KapeNetworkLock"

class KapeSystemTunnel(
    private val vpnService: VpnService,
    private val logger: VpnServiceLogger = NoOpVpnServiceLogger,
    var killSwitchMode: KapeKillSwitchMode = KapeKillSwitchMode.Standard,
    var splitTunnelAppMode: KapeSplitTunnelAppMode = KapeSplitTunnelAppMode.Off,
    /**
     * Pre-armed so [openNetworkLockTunnel] produces byte-equal [TunnelSettings] and [establish] can
     * reuse the interface. Custom DNS only — the filtering resolver needs a fetch, which is too late.
     */
    var customDnsServers: List<String> = emptyList(),
) {
    private val mutex = Mutex()
    private var current: Pair<TunnelSettings, ParcelFileDescriptor>? = null

    fun newBuilder(): KapeTunnelBuilder = KapeTunnelBuilder(this)

    /**
     * Establishes a TUN interface that claims every IPv4/IPv6 route but is never read from — the
     * kernel's bounded TUN queue simply fills and drops further packets once nothing drains it,
     * exactly the "capture and discard" behaviour [KapeSessionController] wants to close the leak
     * window before the first real connection succeeds. Idempotent: if a tunnel with these exact
     * settings is already active (including one a real Lightway connection is using — see below),
     * this is a no-op that doesn't touch it.
     *
     * Deliberately uses the exact same settings as Lightway's fixed tunnel ([LIGHTWAY_LOCAL_IP]
     * etc.) — since Lightway's settings never vary by server/location, [establish] recognizes a
     * Lightway connect() as requesting the *same* tunnel this establishes, reusing it instead of
     * tearing it down and recreating it. No such reuse happens for WireGuard/OpenVPN, whose
     * settings are server-provided and essentially never match.
     *
     * There is no corresponding `closeNetworkLockTunnel()` — [KapeSystemTunnel] only ever retains
     * one tunnel at a time, so the next real [establish] call (or [closeCurrentTunnel] at genuine
     * session end) supersedes or closes whatever this opened, same as any other caller.
     *
     * No-op under [KapeKillSwitchMode.Off] — see [killSwitchMode].
     */
    internal suspend fun openNetworkLockTunnel(): Boolean {
        if (killSwitchMode == KapeKillSwitchMode.Off) {
            logger.info("[tunnel] kill switch is off — openNetworkLockTunnel() is a no-op")
            return true
        }

        // Some Android TV boxes and custom ROMs disable IPv6 on new interfaces, so the kernel
        // rejects the IPv6 address and establish() throws. Retry without it: the "::/0" route alone
        // still captures IPv6 traffic — the same shape WireGuard/OpenVPN tunnels use. That tunnel
        // no longer matches Lightway's settings, so a Lightway connect establishes for real.
        val pfd =
            establishNetworkLock(includeIpv6Address = true)
                ?: run {
                    logger.warning("[tunnel] retrying network lock tunnel without the IPv6 address")
                    establishNetworkLock(includeIpv6Address = false)
                }
        if (pfd == null) {
            logger.error("[tunnel] openNetworkLockTunnel establish() returned null")
            return false
        }
        // Nothing reads from this tunnel directly — KapeSystemTunnel's own establish() already
        // retained the master descriptor, so this dup is just an unneeded extra fd; close it
        // immediately rather than leaking it (a leaked dup would keep the OS-level VPN
        // registration alive even after closeCurrentTunnel() later closes the retained master).
        runCatching { pfd.close() }
        return true
    }

    private suspend fun establishNetworkLock(includeIpv6Address: Boolean): ParcelFileDescriptor? {
        val builder =
            newBuilder()
                .setSession(NETWORK_LOCK_SESSION_NAME)
                .addAddress(LIGHTWAY_LOCAL_IP, 32)
                .addRoute("0.0.0.0", 0)
                .apply { if (includeIpv6Address) addAddress(LIGHTWAY_LOCAL_IPV6, 128) }
                .addRoute("::", 0)
                .apply {
                    customDnsServers.normalizedDnsServers().ifEmpty { listOf(LIGHTWAY_DNS_IP) }.forEach { addDnsServer(it) }
                }.setMtu(LIGHTWAY_MTU)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        return try {
            builder.establish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("[tunnel] openNetworkLockTunnel establish() threw: ${e::class.simpleName}")
            null
        }
    }

    fun protect(socket: Int): Boolean = vpnService.protect(socket)

    fun protect(socket: java.net.Socket): Boolean = vpnService.protect(socket)

    fun protect(socket: java.net.DatagramSocket): Boolean = vpnService.protect(socket)

    /**
     * Called by [KapeTunnelBuilder.establish] once it has accumulated [settings].
     *
     * Under [KapeKillSwitchMode.Standard] (the default): if [settings] match the currently-active
     * tunnel, no new [VpnService.Builder.establish] call is made at all — this hands back a
     * [ParcelFileDescriptor.dup] of the retained descriptor instead, so the underlying TUN
     * interface is never superseded/recreated. Otherwise, a new interface is established for
     * [settings] *first*, and only once that succeeds is whatever was previously active closed —
     * a second [VpnService.Builder.establish] call on the same [VpnService] atomically supersedes
     * the OS-level interface, so there's never a gap where no interface exists at all, shrinking
     * the leak window versus closing the old one first. It also means a failed establish leaves
     * the previous tunnel untouched and still usable, instead of having already destroyed it. A
     * dup of the new descriptor is returned either way. Either way, the caller always gets its own
     * independent descriptor — safe to detach and hand off to native code exactly as before;
     * [KapeSystemTunnel] never hands out the descriptor it itself retains.
     *
     * Under [KapeKillSwitchMode.Off]: no retention/reuse at all — always a fresh, real establish(),
     * and the real (non-dup()'d) descriptor is handed straight to the caller. Whoever closes it
     * tears the interface down for real, letting traffic leak outside the VPN — see
     * [killSwitchMode].
     */
    internal suspend fun establish(
        settings: TunnelSettings,
        session: String?,
    ): ParcelFileDescriptor? =
        mutex.withLock {
            if (killSwitchMode == KapeKillSwitchMode.Off) {
                logger.info("[tunnel] kill switch is off — establishing without retaining/dup()'ing")
                return@withLock buildAndEstablish(settings, session)
            }

            current?.let { (existingSettings, pfd) ->
                if (existingSettings == settings) {
                    logger.info("[tunnel] reusing current tunnel (settings match) — no new establish() call")
                    return@withLock pfd.dup()
                }
            }

            logger.info("[tunnel] settings changed (or nothing current) — calling real establish()")
            val pfd = buildAndEstablish(settings, session) ?: return@withLock null
            // Close the previous tunnel now that the new one is up
            // We still expect micro leaks when the OS switches the network interface
            closeCurrentLocked()
            current = settings to pfd
            pfd.dup()
        }

    private fun buildAndEstablish(
        settings: TunnelSettings,
        session: String?,
    ): ParcelFileDescriptor? {
        val builder = vpnService.newVpnServiceBuilder()
        session?.let { builder.setSession(it) }
        settings.addresses.forEach { builder.addAddress(it.address, it.prefixLength) }
        // Include/exclude some IP ranges depending on the killswitch mode. Off and Standard modes excludes the local IP ranges
        // In order to exclude some routes, we include all ranges apart from the ones we want to exclude
        // Note: builder.excludeRoute() would be cleaner but is only available on API level 33
        settings.routes.forEach { route ->
            route.expandForKillSwitch(killSwitchMode).forEach { builder.addRoute(it.address, it.prefixLength) }
        }
        settings.dnsServers.forEach { builder.addDnsServer(it) }
        if (settings.dnsServers.isNotEmpty()) warnIfPrivateDnsOverrides()
        // A DNS server on a private/local range (e.g. a VPN server's own 10.x.x.x resolver) would
        // otherwise be unreachable through the tunnel once that range is excluded from the routes
        // above under Off/Standard — add an explicit host route so it's always reachable
        // regardless of kill switch mode, since a more specific route always wins.
        settings.dnsServers.forEach { builder.addRoute(it, if (it.isIpv4()) 32 else 128) }
        if (settings.dnsServers.isNotEmpty()) {
            // The only record of what the OS received. The count is logged separately because the
            // production logger redacts IP addresses.
            logger.info(
                "[tunnel] installing ${settings.dnsServers.size} DNS resolver(s)=${settings.dnsServers} " +
                    "hostRoutes=${settings.dnsServers.size}",
            )
            warnIfPrivateRangeDnsServer(settings.dnsServers)
        }
        builder.setMtu(settings.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(settings.metered)
        }
        // A package that isn't installed throws PackageManager.NameNotFoundException — skip just
        // that one rather than letting it prevent the rest of the list (or the tunnel) from being
        // established.
        when (val mode = splitTunnelAppMode) {
            KapeSplitTunnelAppMode.Off -> {}
            is KapeSplitTunnelAppMode.Allow ->
                mode.packages.distinct().forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                        .onFailure { logger.error("[tunnel] split tunnel: unknown package '$pkg', skipping") }
                }
            is KapeSplitTunnelAppMode.Disallow ->
                mode.packages.distinct().forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                        .onFailure { logger.error("[tunnel] split tunnel: unknown package '$pkg', skipping") }
                }
        }
        return builder.establish()
    }

    /**
     * The host route makes a private resolver reachable, but one on the user's own LAN is then routed
     * into the tunnel, where nothing answers. Unconditional anyway — skipping it would be a DNS leak.
     */
    private fun warnIfPrivateRangeDnsServer(dnsServers: List<String>) {
        val private =
            dnsServers.filter { server ->
                server.isIpv4() &&
                    (
                        server.startsWith("10.") ||
                            server.startsWith("192.168.") ||
                            server.startsWith("169.254.") ||
                            server.substringBefore(".").toIntOrNull() == 172 &&
                            server.split(".").getOrNull(1)?.toIntOrNull() in 16..31
                    )
            }
        if (private.isEmpty()) return
        logger.warning(
            "[tunnel] DNS resolver(s) $private are private addresses — the tunnel host-routes them, so a " +
                "resolver on the local network (e.g. a Pi-hole) will not answer while connected",
        )
    }

    /**
     * A Private DNS hostname wins over the resolvers a VPN installs: the platform resolves over TLS
     * to that provider and never queries ours, so custom DNS is unused and Advanced Protection
     * filters nothing. No [VpnService.Builder] API overrides this, so only log it. Automatic mode is
     * unaffected — it falls back to cleartext when our resolver doesn't answer on 853.
     */
    private fun warnIfPrivateDnsOverrides() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val linkProperties =
            runCatching {
                val manager = vpnService.getSystemService(ConnectivityManager::class.java)
                manager?.getLinkProperties(manager.activeNetwork)
            }.getOrNull() ?: return
        if (linkProperties.isPrivateDnsActive && linkProperties.privateDnsServerName != null) {
            logger.warning(
                "[tunnel] Private DNS is set to a specific provider — the system resolves over TLS " +
                    "to it and ignores the tunnel's DNS servers, so custom DNS servers are not used " +
                    "and Advanced Protection will not filter",
            )
        }
    }

    /**
     * Unconditionally closes whatever tunnel is currently retained, if any. For genuine session
     * end only — every caller of [establish] holds its own independent [ParcelFileDescriptor.dup],
     * so this doesn't disrupt anything still actively using one of those; it only releases the
     * copy [KapeSystemTunnel] itself has been holding onto to serve future reuse requests, which
     * is what actually keeps the underlying TUN interface registered at the OS level.
     */
    internal suspend fun closeCurrentTunnel() =
        mutex.withLock {
            logger.info("[tunnel] closing current tunnel for good")
            closeCurrentLocked()
        }

    private fun closeCurrentLocked() {
        current?.second?.let { runCatching { it.close() } }
        current = null
    }
}

// Extension function creates VpnService.Builder using vpnService as the outer class instance.
// VpnService.Builder is a non-static inner class; calling Builder() inside an extension function
// on VpnService correctly binds the enclosing instance to the receiver.
private fun VpnService.newVpnServiceBuilder(): VpnService.Builder = Builder()
