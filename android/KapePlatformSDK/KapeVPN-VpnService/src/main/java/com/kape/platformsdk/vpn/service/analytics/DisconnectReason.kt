package com.kape.platformsdk.vpn.service.analytics

/** Why a [SessionEndEvent] or [ConnectionEndEvent] ended. */
sealed class DisconnectReason {
    /** `disconnect()` was called. */
    object UserInitiated : DisconnectReason()

    /** `onRevoke()` — the system or another VPN app revoked VPN permission. */
    object Revoked : DisconnectReason()

    /** The configuration generator yielded no endpoints on the first fetch. */
    object NoEndpointsAvailable : DisconnectReason()

    /** `connect()` or `runVPN()` failed. */
    data class ConnectionError(
        val cause: Throwable?,
    ) : DisconnectReason()

    /** The run loop coroutine was cancelled mid-attempt. */
    object Cancelled : DisconnectReason()

    /** `pause()` was called. */
    object Paused : DisconnectReason()
}
