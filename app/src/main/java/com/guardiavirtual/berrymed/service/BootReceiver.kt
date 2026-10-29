// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.guardiavirtual.berrymed.ui.MainActivity

/**
 * Arranca el tótem al encender la tablet y después de instalar una versión nueva.
 *
 * Así el servicio arranca sólo como `connectedDevice`: Android no le da
 * cámara ni micrófono desde acá. Si la app tiene el permiso "Mostrar sobre
 * otras apps" (se otorga a mano en Ajustes), además abre la pantalla
 * principal, que le suma cámara y micrófono y se manda atrás enseguida.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in STARTING_ACTIONS) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        } catch (error: Exception) {
            Log.e(TotemRuntime.TAG, "[SERVICIO] No se pudo arrancar tras ${intent?.action}: $error")
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.i(TotemRuntime.TAG, "[SERVICIO] Sin \"Mostrar sobre otras apps\": la teleconsulta espera a que alguien abra Berry Med")
            return
        }
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_FROM_BOOT, true),
            )
            Log.i(TotemRuntime.TAG, "[SERVICIO] Se abre Berry Med un momento para habilitar cámara y micrófono")
        } catch (error: Exception) {
            Log.e(TotemRuntime.TAG, "[SERVICIO] No se pudo abrir Berry Med tras ${intent?.action}: $error")
        }
    }

    companion object {
        private val STARTING_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
