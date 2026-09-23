package com.kape.platformsdk.vpn.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import com.kape.platformsdk.vpn.service.interfaces.NetworkIdentityReader

private const val UNKNOWN_SSID = WifiManager.UNKNOWN_SSID

/**
 * Reads the connected WiFi SSID, its security, and the carrier name.
 *
 * The SDK does not declare the location permissions — the integrator does, since only SSID-based
 * rules need them. Re-checked on every read, as the grant can be revoked at runtime.
 */
class KapeNetworkIdentityReader(
    context: Context,
    private val logger: VpnServiceLogger = NoOpVpnServiceLogger,
) : NetworkIdentityReader {
    private val context: Context = context.applicationContext

    @SuppressLint("MissingPermission")
    override fun wifiIdentity(capabilities: NetworkCapabilities?): NetworkIdentityReader.WifiIdentity? {
        if (!hasLocationAccess()) {
            logger.debug("[identity] SSID unavailable — location permission or services missing")
            return null
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                readWifiFromTransportInfo(capabilities)
            } else {
                readWifiFromWifiManager()
            }
        }.getOrNull()
    }

    // networkOperatorName needs no permission and has existed since API 1.
    override fun carrierName(): String? =
        context
            .getSystemService(TelephonyManager::class.java)
            ?.networkOperatorName
            ?.takeIf { it.isNotBlank() }

    // The flag applies per callback, so these must be the delivered capabilities: a fresh read from
    // ConnectivityManager is redacted regardless of permissions.
    private fun readWifiFromTransportInfo(capabilities: NetworkCapabilities?): NetworkIdentityReader.WifiIdentity? {
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val wifiInfo = capabilities.transportInfo as? WifiInfo ?: return null
        val ssid = wifiInfo.ssid.normalizedSsid() ?: return null
        return NetworkIdentityReader.WifiIdentity(ssid = ssid, isSecure = wifiInfo.isSecure())
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun readWifiFromWifiManager(): NetworkIdentityReader.WifiIdentity? {
        val wifiManager = context.getSystemService(WifiManager::class.java) ?: return null
        val info = wifiManager.connectionInfo ?: return null
        val ssid = info.ssid.normalizedSsid() ?: return null

        // API 29-30 reads security off the scan result; below 29, off the saved configuration.
        val isSecure =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val matching = wifiManager.scanResults?.filter { it.SSID == ssid }.orEmpty()
                if (matching.isEmpty()) null else matching.all { it.capabilities?.contains("WPA") == true }
            } else {
                wifiManager.configuredNetworks
                    ?.firstOrNull { it.networkId == info.networkId }
                    ?.let { !it.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) }
            }

        // Unknown security reads as insecure rather than dropping the SSID, which most rules key on.
        return NetworkIdentityReader.WifiIdentity(ssid = ssid, isSecure = isSecure ?: false)
    }

    // getSSID() quotes the name; the redaction sentinel comes back unquoted.
    private fun String?.normalizedSsid(): String? =
        this
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

    private fun WifiInfo.isSecure(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            currentSecurityType != WifiInfo.SECURITY_TYPE_OPEN &&
                currentSecurityType != WifiInfo.SECURITY_TYPE_WEP
        } else {
            false
        }

    /** From API 29 the platform redacts the SSID unless all three of these hold. */
    private fun hasLocationAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return false
        if (!isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return false
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return false
        return locationManager.isLocationEnabled
    }

    private fun isGranted(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
