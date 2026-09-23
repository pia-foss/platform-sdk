package com.kape.platformsdk.vpn.service

import com.kape.platformsdk.vpn.service.models.KapeOnDemandAction

/** The outcome of evaluating a rule list against a network. */
sealed class OnDemandDecision {
    data class Act(
        val action: KapeOnDemandAction,
    ) : OnDemandDecision()

    data object NoMatch : OnDemandDecision()

    /**
     * The network is unidentified and a rule needs to know which network it is, so nothing is
     * applied. Fails closed: letting a broader rule through would connect on the very network a
     * user marked as not needing it.
     */
    data object Unidentified : OnDemandDecision()
}
