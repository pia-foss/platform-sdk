package com.kape.platformsdk.vpn.service.models

/**
 * Per-app split tunneling, backed by `VpnService.Builder.addAllowedApplication` /
 * `addDisallowedApplication`. See `docs/platform-sdks/android/vpn/network-protection.md`.
 *
 * - [Off]: every app's traffic is routed through the VPN (default).
 * - [Allow]: only [Allow.packages] are routed through the VPN; every other app bypasses it. An
 *   empty list has no effect: `VpnService.Builder` tunnels every app unless
 *   `addAllowedApplication` is called at least once, so [Allow] with no packages yet behaves the
 *   same as [Off] — every app is tunnelled — until packages are added.
 * - [Disallow]: [Disallow.packages] bypass the VPN; every other app is routed through it.
 */
sealed class KapeSplitTunnelAppMode {
    data object Off : KapeSplitTunnelAppMode()

    data class Allow(
        val packages: List<String>,
    ) : KapeSplitTunnelAppMode()

    data class Disallow(
        val packages: List<String>,
    ) : KapeSplitTunnelAppMode()
}

/**
 * Adds [packageName] to the current [KapeSplitTunnelAppMode.Allow]/[KapeSplitTunnelAppMode.Disallow]
 * list. Returns `null` if this is [KapeSplitTunnelAppMode.Off] — there's no list to add to.
 */
fun KapeSplitTunnelAppMode.addingPackage(packageName: String): KapeSplitTunnelAppMode? =
    when (this) {
        is KapeSplitTunnelAppMode.Allow -> copy(packages = (packages + packageName).distinct())
        is KapeSplitTunnelAppMode.Disallow -> copy(packages = (packages + packageName).distinct())
        KapeSplitTunnelAppMode.Off -> null
    }

/**
 * Removes [packageName] from the current [KapeSplitTunnelAppMode.Allow]/[KapeSplitTunnelAppMode.Disallow]
 * list. Returns `null` if this is [KapeSplitTunnelAppMode.Off] — there's no list to remove from.
 */
fun KapeSplitTunnelAppMode.removingPackage(packageName: String): KapeSplitTunnelAppMode? =
    when (this) {
        is KapeSplitTunnelAppMode.Allow -> copy(packages = packages - packageName)
        is KapeSplitTunnelAppMode.Disallow -> copy(packages = packages - packageName)
        KapeSplitTunnelAppMode.Off -> null
    }

/**
 * Clears the current [KapeSplitTunnelAppMode.Allow]/[KapeSplitTunnelAppMode.Disallow] list, keeping
 * the mode itself. Returns `null` if this is [KapeSplitTunnelAppMode.Off] — there's no list to reset.
 */
fun KapeSplitTunnelAppMode.resettingPackages(): KapeSplitTunnelAppMode? =
    when (this) {
        is KapeSplitTunnelAppMode.Allow -> copy(packages = emptyList())
        is KapeSplitTunnelAppMode.Disallow -> copy(packages = emptyList())
        KapeSplitTunnelAppMode.Off -> null
    }
