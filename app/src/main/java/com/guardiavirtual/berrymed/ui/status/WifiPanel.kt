// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * El panel de Wi-Fi de Android: se abre encima de Berry Med con la lista de
 * redes y la clave. Antes de Android 10, la configuración de Wi-Fi. Un
 * selector propio necesitaría que la app sea administradora del dispositivo.
 * Lo usan la fila del Wi-Fi del panel lateral y el aviso «Sin conexión».
 */
object WifiPanel {
    fun intent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
}
