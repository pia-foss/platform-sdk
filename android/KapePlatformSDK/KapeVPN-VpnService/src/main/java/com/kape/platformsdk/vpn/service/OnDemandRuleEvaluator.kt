package com.kape.platformsdk.vpn.service

import com.kape.platformsdk.vpn.service.models.KapeNetworkState
import com.kape.platformsdk.vpn.service.models.KapeNetworkTransport
import com.kape.platformsdk.vpn.service.models.KapeOnDemandCondition
import com.kape.platformsdk.vpn.service.models.KapeOnDemandRule

/**
 * Evaluates [rules] against [network] in order; the first match wins. A rule set that names no
 * specific network still evaluates normally when the identity is missing.
 */
fun evaluateOnDemandRules(
    rules: List<KapeOnDemandRule>,
    network: KapeNetworkState,
): OnDemandDecision {
    if (network.transport == null) return OnDemandDecision.NoMatch

    // Fail closed only on what this network actually leaves undecidable: an SSID rule says nothing
    // about a cellular network, so it must not block one.
    if (rules.any { !it.condition.isDecidableOn(network) }) return OnDemandDecision.Unidentified

    val match = rules.firstOrNull { it.condition.matches(network) } ?: return OnDemandDecision.NoMatch
    return OnDemandDecision.Act(match.action)
}

/** False when [network] is of the kind this condition inspects but the value it needs is unreadable. */
private fun KapeOnDemandCondition.isDecidableOn(network: KapeNetworkState): Boolean =
    when (this) {
        is KapeOnDemandCondition.Ssid ->
            network.transport != KapeNetworkTransport.Wifi || network.ssid != null

        is KapeOnDemandCondition.Carrier ->
            network.transport != KapeNetworkTransport.Cellular || network.carrier != null

        KapeOnDemandCondition.UnsecuredWifi, KapeOnDemandCondition.SecuredWifi ->
            network.transport != KapeNetworkTransport.Wifi || network.isSecure != null

        KapeOnDemandCondition.Wifi, KapeOnDemandCondition.Cellular, KapeOnDemandCondition.Any -> true
    }

private fun KapeOnDemandCondition.matches(network: KapeNetworkState): Boolean =
    when (this) {
        is KapeOnDemandCondition.Ssid -> network.transport == KapeNetworkTransport.Wifi && network.ssid == name
        is KapeOnDemandCondition.Carrier -> network.transport == KapeNetworkTransport.Cellular && network.carrier == name
        KapeOnDemandCondition.Wifi -> network.transport == KapeNetworkTransport.Wifi
        KapeOnDemandCondition.Cellular -> network.transport == KapeNetworkTransport.Cellular
        KapeOnDemandCondition.UnsecuredWifi -> network.transport == KapeNetworkTransport.Wifi && network.isSecure == false
        KapeOnDemandCondition.SecuredWifi -> network.transport == KapeNetworkTransport.Wifi && network.isSecure == true
        KapeOnDemandCondition.Any -> true
    }
