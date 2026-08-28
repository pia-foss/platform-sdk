package com.kape.platformsdk.vpn.service.analytics

/** Why a new [ConnectionBeginEvent] began. */
sealed class ConnectReason {
    /** First connection of the session. */
    object Initial : ConnectReason()

    /** Mid-session drop; the run loop is retrying. */
    object NetworkLoss : ConnectReason()

    /** Network path change. Unreachable — no path-reconnector yet. */
    object PathChange : ConnectReason()

    /** Resume after a pause. */
    object Resumed : ConnectReason()
}
