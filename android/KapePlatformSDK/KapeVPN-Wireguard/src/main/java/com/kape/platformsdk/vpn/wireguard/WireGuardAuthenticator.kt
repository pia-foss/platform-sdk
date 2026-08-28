package com.kape.platformsdk.vpn.wireguard

interface WireGuardAuthenticator {
    suspend fun authenticate(endpointConfiguration: WireGuardEndpointConfiguration): WireGuardAuthConfiguration
}
