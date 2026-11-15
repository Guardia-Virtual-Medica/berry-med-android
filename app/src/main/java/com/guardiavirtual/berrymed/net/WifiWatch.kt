// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * El Wi-Fi para el panel lateral: la red por defecto, si va por Wi-Fi, si
 * llega a internet, su nombre y la señal. Escucha a Android con su propio
 * `NetworkCallback` mientras vive el proceso; no cambia nada de la red.
 *
 * El nombre necesita el permiso de ubicación: sin él Android lo esconde y el
 * panel dice «Wi-Fi». Desde Android 12 el nombre y la señal llegan en las
 * capacidades de la red (con [ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO]);
 * antes se leen de [WifiManager].
 */
class WifiWatch(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    @Volatile var state: WifiState = WifiState()
        private set

    private val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Watch(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
    } else {
        Watch()
    }

    fun start() {
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // Sin el permiso de estado de red o con demasiados callbacks: el panel dice "Sin Wi-Fi".
        }
    }

    private fun read(capabilities: NetworkCapabilities): WifiState {
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return WifiState()
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        var ssid: String? = null
        var rssi: Int? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val info = capabilities.transportInfo as? WifiInfo
            ssid = WifiState.cleanSsid(info?.ssid)
            rssi = capabilities.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
                ?: info?.rssi
        } else {
            @Suppress("DEPRECATION")
            val info = appContext.getSystemService(WifiManager::class.java)?.connectionInfo
            ssid = WifiState.cleanSsid(info?.ssid)
            @Suppress("DEPRECATION")
            rssi = info?.rssi?.takeIf { it != INVALID_RSSI }
        }
        return WifiState(connected = true, validated = validated, ssid = ssid, rssi = rssi)
    }

    private inner class Watch : ConnectivityManager.NetworkCallback {
        constructor() : super()

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int) : super(flags)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            state = read(capabilities)
        }

        override fun onLost(network: Network) {
            state = WifiState()
        }
    }

    private companion object {
        /** Lo que devuelve `WifiInfo.getRssi()` sin señal. */
        const val INVALID_RSSI = -127
    }
}
