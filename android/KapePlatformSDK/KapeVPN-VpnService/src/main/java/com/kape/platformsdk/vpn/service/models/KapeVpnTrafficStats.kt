package com.kape.platformsdk.vpn.service.models

data class KapeVpnTrafficStats(
    val bytesReceived: Long,
    val bytesSent: Long,
) {
    companion object {
        val ZERO = KapeVpnTrafficStats(bytesReceived = 0, bytesSent = 0)
    }
}
