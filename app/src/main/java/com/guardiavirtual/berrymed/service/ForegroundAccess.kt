// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import android.content.pm.ServiceInfo

/**
 * Con qué tipos va el servicio en primer plano. Siempre `connectedDevice`
 * (arranca así al encender la tablet). Cámara y micrófono los da Android
 * sólo si se piden mientras la app tiene una pantalla visible: desde segundo
 * plano tira `SecurityException` (Android 14) y `BOOT_COMPLETED` no da esa
 * excepción. Por eso se suman sólo con una pantalla de Berry Med adelante y,
 * una vez puestos, cada `startForeground` repite los mismos: nunca menos.
 * Sin Android, para probarlo solo (las constantes se copian al compilar).
 */
object ForegroundAccess {
    const val CONNECTED_DEVICE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    const val CAMERA = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
    const val MICROPHONE = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    /**
     * Los tipos para el próximo `startForeground`, a partir de los de ahora.
     * Sólo con [visible] se suman cámara y micrófono, cada uno si su permiso
     * está otorgado. Nunca se saca ninguno.
     */
    fun next(current: Int, visible: Boolean, cameraGranted: Boolean, microphoneGranted: Boolean): Int {
        var types = current or CONNECTED_DEVICE
        if (!visible) return types
        if (cameraGranted) types = types or CAMERA
        if (microphoneGranted) types = types or MICROPHONE
        return types
    }

    fun hasCamera(types: Int): Boolean = types and CAMERA != 0

    fun hasMicrophone(types: Int): Boolean = types and MICROPHONE != 0

    fun describe(types: Int): String = buildList {
        if (types and CONNECTED_DEVICE != 0) add("connectedDevice")
        if (hasCamera(types)) add("camera")
        if (hasMicrophone(types)) add("microphone")
    }.joinToString("|").ifEmpty { "ninguno" }
}
