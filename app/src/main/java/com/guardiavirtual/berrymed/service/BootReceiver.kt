// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** Arranca el tótem al encender la tablet y después de instalar una versión nueva. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in STARTING_ACTIONS) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        } catch (error: Exception) {
            Log.e(TotemRuntime.TAG, "[SERVICIO] No se pudo arrancar tras ${intent?.action}: $error")
        }
    }

    companion object {
        private val STARTING_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
