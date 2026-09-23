package com.kape.platformsdk.vpn.service.models

/** What Connect on Demand is doing, for the integrator to render. The SDK ships no UI for it. */
sealed class KapeOnDemandState {
    /** Disabled, or not licensed. */
    data object Off : KapeOnDemandState()

    data class Watching(
        val network: KapeNetworkState?,
    ) : KapeOnDemandState()

    /** No rule is being applied because the network cannot be identified — usually a missing permission. */
    data object NetworkIdentityUnavailable : KapeOnDemandState()
}
