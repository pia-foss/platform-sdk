package com.kape.platformsdk.vpn.wireguard

interface WireGuardAuthenticator {
    suspend fun authenticate(endpointConfiguration: WireGuardEndpointConfiguration): WireGuardAuthConfiguration
}

/**
 * Thrown by a [WireGuardAuthenticator] whose auth request reached the server but was rejected, so
 * [KapeWireGuardConnectionController] can log the server's HTTP status alongside the message.
 * Any other exception is still logged, just without a status.
 */
class WireGuardAuthenticationException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
