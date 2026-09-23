package com.kape.platformsdk.vpn.service.models

/**
 * A Connect on Demand rule. Rules are evaluated in order and the first match wins.
 *
 * Android exposes no SSID-triggered VPN rules to apps, so the SDK evaluates these itself and can
 * only act while its process is alive.
 */
data class KapeOnDemandRule(
    val condition: KapeOnDemandCondition,
    val action: KapeOnDemandAction,
)

/** The network a [KapeOnDemandRule] applies to. */
sealed class KapeOnDemandCondition {
    data class Ssid(
        val name: String,
    ) : KapeOnDemandCondition()

    /** By network operator name. */
    data class Carrier(
        val name: String,
    ) : KapeOnDemandCondition()

    data object Wifi : KapeOnDemandCondition()

    data object Cellular : KapeOnDemandCondition()

    /** No encryption, or WEP. */
    data object UnsecuredWifi : KapeOnDemandCondition()

    /** WPA or better. */
    data object SecuredWifi : KapeOnDemandCondition()

    /** Catch-all, so "everything except these" is expressible. */
    data object Any : KapeOnDemandCondition()
}

enum class KapeOnDemandAction {
    Connect,

    Disconnect,

    /** Match and stop, changing nothing. Shadows any broader rule below it. */
    Retain,
}

/** How narrowly a condition matches; lower ranks are evaluated first. Used by [withOnDemandRule]. */
val KapeOnDemandCondition.specificity: Int
    get() =
        when (this) {
            is KapeOnDemandCondition.Ssid, is KapeOnDemandCondition.Carrier -> 0
            KapeOnDemandCondition.UnsecuredWifi, KapeOnDemandCondition.SecuredWifi -> 1
            KapeOnDemandCondition.Wifi, KapeOnDemandCondition.Cellular -> 2
            KapeOnDemandCondition.Any -> 3
        }

/**
 * Inserts [rule] at its [specificity] rank, appending among equals so an existing order survives.
 * An equal condition is replaced in place — a duplicate could never be reached.
 */
fun List<KapeOnDemandRule>.withOnDemandRule(rule: KapeOnDemandRule): List<KapeOnDemandRule> {
    val existing = indexOfFirst { it.condition == rule.condition }
    if (existing >= 0) return toMutableList().apply { this[existing] = rule }

    val insertAt = indexOfFirst { it.condition.specificity > rule.condition.specificity }
    return toMutableList().apply { add(if (insertAt >= 0) insertAt else size, rule) }
}

fun List<KapeOnDemandRule>.withoutOnDemandRule(condition: KapeOnDemandCondition): List<KapeOnDemandRule> =
    filterNot { it.condition == condition }
