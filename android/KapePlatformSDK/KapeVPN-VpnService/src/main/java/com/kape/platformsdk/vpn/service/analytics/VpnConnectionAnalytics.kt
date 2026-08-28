package com.kape.platformsdk.vpn.service.analytics

/**
 * Observes VPN connection reliability events from `KapeSessionController`'s run loop.
 *
 * A session (`start()`→`stop()`) spans one or more connections — each a "try to
 * (re)establish and stay up" span — which spans one or more attempts (one configuration
 * trial each).
 *
 * Calls arrive on a private single-threaded dispatcher, off the run loop — a slow
 * implementation delays only its own later events, never the run loop.
 */
interface VpnConnectionAnalytics {
    fun sessionDidBegin(event: SessionBeginEvent)

    fun sessionDidEnd(event: SessionEndEvent)

    fun connectionDidBegin(event: ConnectionBeginEvent)

    fun connectionDidEnd(event: ConnectionEndEvent)

    fun attemptDidBegin(event: AttemptBeginEvent)

    fun attemptDidEnd(event: AttemptEndEvent)
}

object NoOpVpnConnectionAnalytics : VpnConnectionAnalytics {
    override fun sessionDidBegin(event: SessionBeginEvent) = Unit

    override fun sessionDidEnd(event: SessionEndEvent) = Unit

    override fun connectionDidBegin(event: ConnectionBeginEvent) = Unit

    override fun connectionDidEnd(event: ConnectionEndEvent) = Unit

    override fun attemptDidBegin(event: AttemptBeginEvent) = Unit

    override fun attemptDidEnd(event: AttemptEndEvent) = Unit
}
