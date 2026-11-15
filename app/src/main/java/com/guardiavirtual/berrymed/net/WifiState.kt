// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.net

/**
 * La red por defecto de la tablet, como la ve [WifiWatch]. Sin Android, para
 * que las reglas del panel lateral se prueben solas.
 */
data class WifiState(
    /** Si la red por defecto va por Wi-Fi. */
    val connected: Boolean = false,
    /** Si Android comprobó que esa red llega a internet. */
    val validated: Boolean = false,
    /** El nombre de la red, sin comillas; null si Android no lo da (sin el permiso de ubicación). */
    val ssid: String? = null,
    /** La señal en dBm; null si no se sabe. */
    val rssi: Int? = null,
) {
    /** Las barras de la señal, de 0 (sin dato) a 4. */
    val level: Int get() = rssi?.let(::bars) ?: 0

    companion object {
        /**
         * Las barras de la señal: 4 desde −60 dBm, 3 hasta −70, 2 hasta −80 y
         * 1 por debajo. Son los umbrales del diseño, a confirmar en la tablet.
         */
        fun bars(rssi: Int): Int = when {
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -80 -> 2
            else -> 1
        }

        /** El SSID de Android sin comillas; null si viene vacío o como `<unknown ssid>` (sin permiso de ubicación). */
        fun cleanSsid(raw: String?): String? {
            val ssid = raw?.trim()?.removeSurrounding("\"")?.trim()
            if (ssid.isNullOrEmpty() || ssid == UNKNOWN_SSID) return null
            return ssid
        }

        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
