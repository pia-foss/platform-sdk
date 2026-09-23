package com.kape.platformsdk.vpn.service.analytics

/** What started a VPN session. Carried on [SessionBeginEvent] and [SessionEndEvent]. */
enum class KapeConnectSource {
    Manual,
    OnDemand,
    Startup,
}
