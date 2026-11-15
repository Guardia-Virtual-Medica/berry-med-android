// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.guardiavirtual.berrymed.service.AppPermission
import com.guardiavirtual.berrymed.service.AppPermissions
import com.guardiavirtual.berrymed.service.MonitorService

/**
 * Los permisos de la app en una pantalla: todos juntos al abrirla
 * ([askMissingOnOpen]) y uno a pedido, al tocar una fila del panel lateral
 * que dice «Falta permiso» ([askFromTap]). Si Android ya no deja pedirlo
 * (rechazado dos veces), tocar la fila abre los ajustes de la app. Qué pedir
 * y cuándo ir a los ajustes lo dice [AppPermissions].
 *
 * Se crea como propiedad de la actividad: registra su pedido antes de que
 * arranque. [onResult] corre después de cada respuesta.
 */
class AppPermissionAsker(private val activity: ComponentActivity, private val onResult: () -> Unit = {}) {
    private var fromTap = false

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        // Recién otorgados: el servicio suma cámara y micrófono ahora, con la pantalla adelante.
        MonitorService.promote(activity)
        val deniedBlocked = results.filterValues { granted -> !granted }.keys.map { permission ->
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
        if (AppPermissions.openSettingsAfter(fromTap, deniedBlocked)) openAppSettings()
        fromTap = false
        onResult()
    }

    fun askMissingOnOpen() {
        val missing = AppPermissions.missing(Build.VERSION.SDK_INT, ::granted)
        if (missing.isEmpty()) return
        fromTap = false
        launcher.launch(missing.toTypedArray())
    }

    fun askFromTap(permission: AppPermission) {
        fromTap = true
        launcher.launch(AppPermissions.manifest(permission).toTypedArray())
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
            )
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
