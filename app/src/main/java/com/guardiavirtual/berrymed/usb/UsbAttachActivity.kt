// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.service.TotemRuntime

/**
 * Lo que Android abre al conectarse la plaqueta, el termómetro, el hub YKUSH3
 * o el otoscopio. No muestra nada y se cierra enseguida: adelante tiene que
 * seguir Chrome con la plataforma médica, o el Panel Local si se está
 * encendiendo el otoscopio. Con "Usar de forma predeterminada", Android da el
 * permiso USB antes de abrirla, así que no hace falta nada más que existir.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        } catch (error: Exception) {
            Log.w(TotemRuntime.TAG, "[USB] No se pudo arrancar el servicio al conectar un dispositivo: $error")
        }
        finish()
    }
}
