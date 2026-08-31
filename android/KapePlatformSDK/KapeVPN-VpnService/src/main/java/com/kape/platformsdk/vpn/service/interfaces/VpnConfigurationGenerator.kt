package com.kape.platformsdk.vpn.service.interfaces

import com.kape.platformsdk.vpn.service.models.KapeVpnTunnelError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

fun interface VpnConfigurationGenerator {
    suspend fun generateConfigurations(): List<VpnConfiguration>
}

/**
 * A never-ending flow of configurations to try. An empty batch throws [NoConfigurationException]
 * instead of looping straight back into [generateConfigurations].
 */
fun VpnConfigurationGenerator.configurations(): Flow<VpnConfiguration> =
    flow {
        while (currentCoroutineContext().isActive) {
            val configurations = generateConfigurations()
            if (configurations.isEmpty()) {
                throw NoConfigurationException()
            }
            configurations.forEach { emit(it) }
        }
    }

class NoConfigurationException : Exception("No VPN configuration was generated")

/**
 * An actionable failure while generating configurations. The session keeps retrying; the reason is
 * recorded in `VpnServiceState.lastTunnelError` for the app.
 */
class KapeVpnTunnelException(
    val error: KapeVpnTunnelError,
) : Exception("VPN configuration failed: $error")
