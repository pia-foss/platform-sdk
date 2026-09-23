package com.kape.platformsdk.vpn.service

import com.kape.platformsdk.vpn.service.models.KapeOnDemandAction
import com.kape.platformsdk.vpn.service.models.KapeOnDemandCondition
import com.kape.platformsdk.vpn.service.models.KapeOnDemandRule
import com.kape.platformsdk.vpn.service.models.specificity
import com.kape.platformsdk.vpn.service.models.withOnDemandRule
import com.kape.platformsdk.vpn.service.models.withoutOnDemandRule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnDemandRuleOrderingTest {
    private fun connect(condition: KapeOnDemandCondition) = KapeOnDemandRule(condition, KapeOnDemandAction.Connect)

    private fun disconnect(condition: KapeOnDemandCondition) = KapeOnDemandRule(condition, KapeOnDemandAction.Disconnect)

    @Test
    fun `a named network is ranked ahead of a transport, which is ahead of the catch-all`() {
        assertTrue(KapeOnDemandCondition.Ssid("x").specificity < KapeOnDemandCondition.UnsecuredWifi.specificity)
        assertTrue(KapeOnDemandCondition.UnsecuredWifi.specificity < KapeOnDemandCondition.Wifi.specificity)
        assertTrue(KapeOnDemandCondition.Wifi.specificity < KapeOnDemandCondition.Any.specificity)
        assertEquals(KapeOnDemandCondition.Ssid("x").specificity, KapeOnDemandCondition.Carrier("y").specificity)
    }

    @Test
    fun `an SSID rule is inserted ahead of broader rules however the list started`() {
        val existing = listOf(connect(KapeOnDemandCondition.Wifi), connect(KapeOnDemandCondition.Any))

        val result = existing.withOnDemandRule(disconnect(KapeOnDemandCondition.Ssid("Home")))

        assertEquals(
            listOf(
                disconnect(KapeOnDemandCondition.Ssid("Home")),
                connect(KapeOnDemandCondition.Wifi),
                connect(KapeOnDemandCondition.Any),
            ),
            result,
        )
    }

    @Test
    fun `a broad rule lands before the catch-all but after the named ones`() {
        val existing =
            listOf(
                disconnect(KapeOnDemandCondition.Ssid("Home")),
                connect(KapeOnDemandCondition.Any),
            )

        val result = existing.withOnDemandRule(connect(KapeOnDemandCondition.Wifi))

        assertEquals(
            listOf(
                disconnect(KapeOnDemandCondition.Ssid("Home")),
                connect(KapeOnDemandCondition.Wifi),
                connect(KapeOnDemandCondition.Any),
            ),
            result,
        )
    }

    @Test
    fun `rules of equal rank append, so the order the user chose survives`() {
        val existing =
            listOf(
                disconnect(KapeOnDemandCondition.Ssid("Home")),
                disconnect(KapeOnDemandCondition.Ssid("Office")),
                connect(KapeOnDemandCondition.Any),
            )

        val result = existing.withOnDemandRule(disconnect(KapeOnDemandCondition.Ssid("Gym")))

        assertEquals(
            listOf(
                KapeOnDemandCondition.Ssid("Home"),
                KapeOnDemandCondition.Ssid("Office"),
                KapeOnDemandCondition.Ssid("Gym"),
                KapeOnDemandCondition.Any,
            ),
            result.map { it.condition },
        )
    }

    @Test
    fun `re-adding a condition replaces its action in place rather than appending an unreachable duplicate`() {
        val existing =
            listOf(
                disconnect(KapeOnDemandCondition.Ssid("Home")),
                disconnect(KapeOnDemandCondition.Ssid("Office")),
                connect(KapeOnDemandCondition.Any),
            )

        val result = existing.withOnDemandRule(connect(KapeOnDemandCondition.Ssid("Home")))

        assertEquals(3, result.size)
        assertEquals(connect(KapeOnDemandCondition.Ssid("Home")), result[0])
        assertEquals(disconnect(KapeOnDemandCondition.Ssid("Office")), result[1])
    }

    @Test
    fun `adding to an empty list just adds`() {
        assertEquals(
            listOf(connect(KapeOnDemandCondition.Any)),
            emptyList<KapeOnDemandRule>().withOnDemandRule(connect(KapeOnDemandCondition.Any)),
        )
    }

    @Test
    fun `removal matches on the condition, and is a no-op when absent`() {
        val existing =
            listOf(
                disconnect(KapeOnDemandCondition.Ssid("Home")),
                connect(KapeOnDemandCondition.Any),
            )

        assertEquals(
            listOf(connect(KapeOnDemandCondition.Any)),
            existing.withoutOnDemandRule(KapeOnDemandCondition.Ssid("Home")),
        )
        assertEquals(existing, existing.withoutOnDemandRule(KapeOnDemandCondition.Ssid("Elsewhere")))
    }

    // Building a trusted list one network at a time must land on a working order.
    @Test
    fun `building a trusted list incrementally produces an evaluable order`() {
        val rules =
            emptyList<KapeOnDemandRule>()
                .withOnDemandRule(connect(KapeOnDemandCondition.Any))
                .withOnDemandRule(disconnect(KapeOnDemandCondition.Ssid("Home")))
                .withOnDemandRule(disconnect(KapeOnDemandCondition.Carrier("O2")))

        assertEquals(KapeOnDemandCondition.Any, rules.last().condition)
        assertTrue(rules.dropLast(1).all { it.action == KapeOnDemandAction.Disconnect })
    }
}
