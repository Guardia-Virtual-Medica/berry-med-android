// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import android.app.Activity
import com.guardiavirtual.berrymed.service.AppPermission
import com.guardiavirtual.berrymed.service.TotemRuntime
import com.guardiavirtual.berrymed.ui.AppPermissionAsker

/**
 * Lo que hace cada toque del panel lateral que no es cambiar de modo ni el
 * sonido, igual en el kiosco y en el Panel Local.
 */
object StatusPanelActions {
    fun handle(activity: Activity, runtime: TotemRuntime, permissions: AppPermissionAsker, action: StatusAction) {
        when (action) {
            StatusAction.OPEN_WIFI -> activity.startActivity(WifiPanel.intent())
            StatusAction.USB_PERMISSION -> runtime.requestUsbPermissions()
            StatusAction.OTOSCOPE_PERMISSION -> runtime.askOtoscopePermission()
            StatusAction.CAMERA -> permissions.askFromTap(AppPermission.CAMERA)
            StatusAction.MICROPHONE -> permissions.askFromTap(AppPermission.MICROPHONE)
            StatusAction.LOCATION -> permissions.askFromTap(AppPermission.LOCATION)
        }
    }
}
