package com.kape.platformsdk.vpn.service.models

sealed class IpAddress {
    data class V4(
        val value: String,
    ) : IpAddress()

    data class V6(
        val value: String,
    ) : IpAddress()
}

private val IPV4_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

fun String.isIpv4(): Boolean = matches(IPV4_REGEX)

fun String.toIpAddress(): IpAddress = if (isIpv4()) IpAddress.V4(this) else IpAddress.V6(this)

/**
 * Stricter than [isIpv4], which accepts out-of-range octets and also drives route prefix selection:
 * `addDnsServer` throws on a non-literal address, failing the connect. ASCII-only because
 * `Char.isDigit()` accepts non-ASCII digits that `toInt()` then parses.
 */
fun String.isDottedQuadIpv4(): Boolean {
    val octets = split(".")
    if (octets.size != 4) return false
    return octets.all { octet ->
        octet.length in 1..3 &&
            octet.all { it in '0'..'9' } &&
            (octet.length == 1 || !octet.startsWith("0")) &&
            (octet.toIntOrNull() ?: return@all false) <= 255
    }
}

/** Order is load-bearing: `TunnelSettings` equality drives tunnel reuse, so every DNS list site
 * must normalise identically. */
fun List<String>.normalizedDnsServers(): List<String> = map { it.trim() }.filter { it.isNotEmpty() }.distinct()
