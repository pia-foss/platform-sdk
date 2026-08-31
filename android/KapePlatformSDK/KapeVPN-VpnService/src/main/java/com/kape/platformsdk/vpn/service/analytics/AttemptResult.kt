package com.kape.platformsdk.vpn.service.analytics

/** Outcome of a single endpoint connect attempt within the run loop. */
sealed class AttemptResult {
    /** Tunnel handshake completed; proceed to `runVPN()`. */
    object Connected : AttemptResult()

    /** Transient error; caller advances. `cause` is null when `connect()` just returned false. */
    data class Failed(
        val cause: Throwable? = null,
    ) : AttemptResult()

    /** Coroutine cancelled mid-attempt; the caller stops the run loop. */
    object Cancelled : AttemptResult()
}
