package com.kape.platformsdk.vpn.service

interface VpnServiceLogger {
    fun trace(message: String)

    fun debug(message: String)

    fun info(message: String)

    fun warning(message: String)

    fun error(message: String)
}

object NoOpVpnServiceLogger : VpnServiceLogger {
    override fun trace(message: String) = Unit

    override fun debug(message: String) = Unit

    override fun info(message: String) = Unit

    override fun warning(message: String) = Unit

    override fun error(message: String) = Unit
}
