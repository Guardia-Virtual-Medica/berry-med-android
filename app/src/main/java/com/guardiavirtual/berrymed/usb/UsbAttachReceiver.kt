// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.service.TotemRuntime

class UsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Desde segundo plano Android puede negar el arranque si la app no está
        // excluida de la optimización de batería. No es motivo para cerrar la app.
        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        } catch (error: Exception) {
            Log.w(TotemRuntime.TAG, "[USB] No se pudo arrancar el servicio al conectar un dispositivo: $error")
        }
    }
}
