package com.kape.platformsdk.vpn.wireguard

import org.amnezia.awg.AwgLogger
import org.amnezia.awg.GoBackend

interface WireguardClient {
    fun turnOn(
        name: String,
        fd: Int,
        config: String,
    ): Int

    fun turnOff(handle: Int)

    fun setLogger(logger: AwgLogger)

    fun resetLogger()

    fun socketV4(handle: Int): Int

    fun socketV6(handle: Int): Int

    fun getConfig(handle: Int): String?
}

class GoBackendWireguardClient : WireguardClient {
    override fun turnOn(
        name: String,
        fd: Int,
        config: String,
    ) = GoBackend.awgTurnOn(name, fd, config)

    override fun turnOff(handle: Int) = GoBackend.awgTurnOff(handle)

    override fun setLogger(logger: AwgLogger) = GoBackend.awgSetLogger(logger)

    override fun resetLogger() = GoBackend.awgResetLogger()

    override fun socketV4(handle: Int) = GoBackend.awgGetSocketV4(handle)

    override fun socketV6(handle: Int) = GoBackend.awgGetSocketV6(handle)

    override fun getConfig(handle: Int): String? = GoBackend.awgGetConfig(handle)

    companion object {
        init {
            System.loadLibrary("wg-go")
        }
    }
}
