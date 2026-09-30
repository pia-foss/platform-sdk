package com.kape.platformsdk.vpn.service

private const val REDACTED = "<redacted>"

private val IPV4_REGEX = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

private val IPV6_REGEX =
    Regex(
        """(?<![0-9a-fA-F:])(?:""" +
            """(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}""" +
            """|(?:[0-9a-fA-F]{1,4}:){1,7}:""" +
            """|(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}""" +
            """|(?:[0-9a-fA-F]{1,4}:){1,5}(?::[0-9a-fA-F]{1,4}){1,2}""" +
            """|(?:[0-9a-fA-F]{1,4}:){1,4}(?::[0-9a-fA-F]{1,4}){1,3}""" +
            """|(?:[0-9a-fA-F]{1,4}:){1,3}(?::[0-9a-fA-F]{1,4}){1,4}""" +
            """|(?:[0-9a-fA-F]{1,4}:){1,2}(?::[0-9a-fA-F]{1,4}){1,5}""" +
            """|[0-9a-fA-F]{1,4}:(?::[0-9a-fA-F]{1,4}){1,6}""" +
            """|:(?::[0-9a-fA-F]{1,4}){1,7}""" +
            """|::""" +
            """)(?![0-9a-fA-F:])""",
    )

/**
 * Replaces every public IPv4/IPv6 address in this string with `<redacted>`, keeping private,
 * loopback, link-local and CGNAT addresses (e.g. a server's 10.x gateway or 100.64.x tunnel IP)
 * readable — those identify nothing outside the device or tunnel and are what diagnostics need.
 *
 * Unlike the SDK's own `KapeLogger`, a host-app [VpnServiceLogger] isn't guaranteed to redact
 * anything, so messages built from untrusted text (exception messages, server responses) should
 * go through this before being logged.
 */
fun String.redactPublicIps(): String =
    replace(IPV4_REGEX) { if (it.value.isNonPublicIpv4()) it.value else REDACTED }
        .replace(IPV6_REGEX) { if (it.value.isNonPublicIpv6()) it.value else REDACTED }

private fun String.isNonPublicIpv4(): Boolean {
    val octets = split(".").map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return when (a) {
        0, 10, 127 -> true // "this" network, private, loopback
        100 -> b in 64..127 // CGNAT
        169 -> b == 254 // link-local
        172 -> b in 16..31
        192 -> b == 168
        else -> false
    }
}

private fun String.isNonPublicIpv6(): Boolean {
    val lower = lowercase()
    if (lower == "::" || lower == "::1") return true
    val firstGroup = lower.substringBefore(":").toIntOrNull(16) ?: return false
    return when {
        firstGroup and 0xfe00 == 0xfc00 -> true // unique local fc00::/7
        firstGroup and 0xffc0 == 0xfe80 -> true // link-local fe80::/10
        else -> false
    }
}
