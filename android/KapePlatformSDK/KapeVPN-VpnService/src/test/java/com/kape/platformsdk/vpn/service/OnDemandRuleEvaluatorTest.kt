package com.kape.platformsdk.vpn.service

import com.kape.platformsdk.vpn.service.models.KapeNetworkState
import com.kape.platformsdk.vpn.service.models.KapeNetworkTransport
import com.kape.platformsdk.vpn.service.models.KapeOnDemandAction
import com.kape.platformsdk.vpn.service.models.KapeOnDemandCondition
import com.kape.platformsdk.vpn.service.models.KapeOnDemandRule
import org.junit.Test
import kotlin.test.assertEquals

class OnDemandRuleEvaluatorTest {
    private fun wifi(
        ssid: String? = "Cafe",
        isSecure: Boolean? = true,
    ) = KapeNetworkState(transport = KapeNetworkTransport.Wifi, ssid = ssid, isSecure = isSecure)

    private fun cellular(carrier: String? = "O2") = KapeNetworkState(transport = KapeNetworkTransport.Cellular, carrier = carrier)

    private fun rule(
        condition: KapeOnDemandCondition,
        action: KapeOnDemandAction = KapeOnDemandAction.Connect,
    ) = KapeOnDemandRule(condition, action)

    private fun assertAction(
        expected: KapeOnDemandAction,
        actual: OnDemandDecision,
    ) = assertEquals(OnDemandDecision.Act(expected), actual)

    @Test
    fun `the first matching rule wins and later rules are not consulted`() {
        val rules =
            listOf(
                rule(KapeOnDemandCondition.Ssid("Home"), KapeOnDemandAction.Disconnect),
                rule(KapeOnDemandCondition.Wifi, KapeOnDemandAction.Connect),
            )

        assertAction(KapeOnDemandAction.Disconnect, evaluateOnDemandRules(rules, wifi(ssid = "Home")))
    }

    @Test
    fun `a later rule applies when the earlier one does not match`() {
        val rules =
            listOf(
                rule(KapeOnDemandCondition.Ssid("Home"), KapeOnDemandAction.Disconnect),
                rule(KapeOnDemandCondition.Wifi, KapeOnDemandAction.Connect),
            )

        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(rules, wifi(ssid = "Airport")))
    }

    @Test
    fun `each condition matches only its own network`() {
        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(listOf(rule(KapeOnDemandCondition.Wifi)), wifi()))
        assertAction(
            KapeOnDemandAction.Connect,
            evaluateOnDemandRules(listOf(rule(KapeOnDemandCondition.Cellular)), cellular()),
        )
        assertAction(
            KapeOnDemandAction.Connect,
            evaluateOnDemandRules(listOf(rule(KapeOnDemandCondition.Carrier("O2"))), cellular("O2")),
        )
        assertAction(
            KapeOnDemandAction.Connect,
            evaluateOnDemandRules(listOf(rule(KapeOnDemandCondition.SecuredWifi)), wifi(isSecure = true)),
        )
        assertAction(
            KapeOnDemandAction.Connect,
            evaluateOnDemandRules(listOf(rule(KapeOnDemandCondition.UnsecuredWifi)), wifi(isSecure = false)),
        )
        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(listOf(rule(KapeOnDemandCondition.Any)), cellular()))

        assertEquals(
            OnDemandDecision.NoMatch,
            evaluateOnDemandRules(listOf(rule(KapeOnDemandCondition.Carrier("Vodafone"))), cellular("O2")),
        )
        assertEquals(
            OnDemandDecision.NoMatch,
            evaluateOnDemandRules(listOf(rule(KapeOnDemandCondition.UnsecuredWifi)), wifi(isSecure = true)),
        )
    }

    @Test
    fun `Retain matches, stops evaluation, and shadows a broader rule below it`() {
        val rules =
            listOf(
                rule(KapeOnDemandCondition.Ssid("Office"), KapeOnDemandAction.Retain),
                rule(KapeOnDemandCondition.Wifi, KapeOnDemandAction.Connect),
            )

        assertAction(KapeOnDemandAction.Retain, evaluateOnDemandRules(rules, wifi(ssid = "Office")))
    }

    @Test
    fun `no rules and no matching rule both report NoMatch`() {
        assertEquals(OnDemandDecision.NoMatch, evaluateOnDemandRules(emptyList(), wifi()))
        assertEquals(
            OnDemandDecision.NoMatch,
            evaluateOnDemandRules(listOf(rule(KapeOnDemandCondition.Cellular)), wifi()),
        )
    }

    @Test
    fun `an offline device is never acted on`() {
        val rules = listOf(rule(KapeOnDemandCondition.Any, KapeOnDemandAction.Connect))

        assertEquals(OnDemandDecision.NoMatch, evaluateOnDemandRules(rules, KapeNetworkState.OFFLINE))
    }

    @Test
    fun `an allowlist expressed as named disconnects plus a catch-all connect`() {
        val rules =
            listOf(
                rule(KapeOnDemandCondition.Ssid("Home"), KapeOnDemandAction.Disconnect),
                rule(KapeOnDemandCondition.Carrier("O2"), KapeOnDemandAction.Disconnect),
                rule(KapeOnDemandCondition.Any, KapeOnDemandAction.Connect),
            )

        assertAction(KapeOnDemandAction.Disconnect, evaluateOnDemandRules(rules, wifi(ssid = "Home")))
        assertAction(KapeOnDemandAction.Disconnect, evaluateOnDemandRules(rules, cellular("O2")))
        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(rules, wifi(ssid = "Airport")))
        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(rules, cellular("Vodafone")))
    }

    @Test
    fun `an exact network rule takes precedence over a per-transport default`() {
        val rules =
            listOf(
                rule(KapeOnDemandCondition.Ssid("Home"), KapeOnDemandAction.Retain),
                rule(KapeOnDemandCondition.Wifi, KapeOnDemandAction.Connect),
                rule(KapeOnDemandCondition.Cellular, KapeOnDemandAction.Connect),
            )

        assertAction(KapeOnDemandAction.Retain, evaluateOnDemandRules(rules, wifi(ssid = "Home")))
        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(rules, wifi(ssid = "Cafe")))
        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(rules, cellular()))
    }

    @Test
    fun `an unidentifiable network stops every rule, not just the ones naming it`() {
        val rules =
            listOf(
                rule(KapeOnDemandCondition.Ssid("Home"), KapeOnDemandAction.Disconnect),
                rule(KapeOnDemandCondition.Wifi, KapeOnDemandAction.Connect),
            )

        // Failing open would connect on "Home" — the network the user marked as not needing it.
        assertEquals(OnDemandDecision.Unidentified, evaluateOnDemandRules(rules, wifi(ssid = null)))
    }

    @Test
    fun `an unidentifiable network still evaluates a rule set that names nothing`() {
        val rules = listOf(rule(KapeOnDemandCondition.Wifi, KapeOnDemandAction.Connect))

        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(rules, wifi(ssid = null)))
    }

    @Test
    fun `an unreadable carrier is unidentified only when a rule names a carrier`() {
        val named = listOf(rule(KapeOnDemandCondition.Carrier("O2"), KapeOnDemandAction.Connect))
        val broad = listOf(rule(KapeOnDemandCondition.Cellular, KapeOnDemandAction.Connect))

        assertEquals(OnDemandDecision.Unidentified, evaluateOnDemandRules(named, cellular(carrier = null)))
        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(broad, cellular(carrier = null)))
    }

    // A rule only blocks evaluation on the kind of network it actually inspects. Blocking every
    // transport left mobile data dead whenever the carrier was unreadable — below API 29 always.
    @Test
    fun `an SSID rule does not block evaluation on cellular`() {
        val rules =
            listOf(
                rule(KapeOnDemandCondition.Ssid("Home"), KapeOnDemandAction.Disconnect),
                rule(KapeOnDemandCondition.Any, KapeOnDemandAction.Connect),
            )

        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(rules, cellular(carrier = null)))
    }

    @Test
    fun `a carrier rule does not block evaluation on WiFi`() {
        val rules =
            listOf(
                rule(KapeOnDemandCondition.Carrier("O2"), KapeOnDemandAction.Disconnect),
                rule(KapeOnDemandCondition.Any, KapeOnDemandAction.Connect),
            )

        assertAction(KapeOnDemandAction.Connect, evaluateOnDemandRules(rules, wifi(ssid = null, isSecure = null)))
    }

    @Test
    fun `a security rule is undecidable when the network's security is unknown`() {
        val rules =
            listOf(
                rule(KapeOnDemandCondition.SecuredWifi, KapeOnDemandAction.Disconnect),
                rule(KapeOnDemandCondition.Any, KapeOnDemandAction.Connect),
            )

        assertEquals(
            OnDemandDecision.Unidentified,
            evaluateOnDemandRules(rules, wifi(ssid = "Cafe", isSecure = null)),
        )
        assertAction(
            KapeOnDemandAction.Connect,
            evaluateOnDemandRules(rules, wifi(ssid = "Cafe", isSecure = false)),
        )
    }

    @Test
    fun `a security rule does not block evaluation on cellular`() {
        val rules =
            listOf(
                rule(KapeOnDemandCondition.UnsecuredWifi, KapeOnDemandAction.Connect),
                rule(KapeOnDemandCondition.Cellular, KapeOnDemandAction.Disconnect),
            )

        assertAction(KapeOnDemandAction.Disconnect, evaluateOnDemandRules(rules, cellular(carrier = null)))
    }

    @Test
    fun `a secured-wifi rule makes an unreadable SSID unidentified`() {
        val rules = listOf(rule(KapeOnDemandCondition.SecuredWifi, KapeOnDemandAction.Connect))

        assertEquals(OnDemandDecision.Unidentified, evaluateOnDemandRules(rules, wifi(ssid = null, isSecure = null)))
    }
}
