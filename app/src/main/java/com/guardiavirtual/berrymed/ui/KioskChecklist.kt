// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

/**
 * Lo que la pantalla junta de Android para la lista de puesta en marcha del
 * paso Kiosco. Sólo booleanos y la versión, así la lista se prueba sin Android.
 */
data class KioskChecklistInput(
    val cameraGranted: Boolean,
    val microphoneGranted: Boolean,
    /** Si la app de inicio de la tablet resuelve a Berry Med. */
    val berryMedIsHome: Boolean,
    val sdkInt: Int,
    /** Los tipos del servicio en primer plano, de [com.guardiavirtual.berrymed.service.TotemRuntime.foregroundTypes]. */
    val serviceCamera: Boolean,
    val serviceMicrophone: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    /** La URL del kiosco escrita es válida, no está vacía y es la guardada. */
    val kioskUrlSaved: Boolean,
)

enum class KioskCheck { PERMISSIONS, HOME, SERVICE, BATTERY, TOTEM }

/**
 * Un ítem de la lista. [done] es null cuando Berry Med no lo puede saber (la
 * sesión del tótem vive en la página del kiosco). [action] dice si va el botón
 * y [actionEnabled] si se puede tocar.
 */
data class KioskCheckItem(
    val check: KioskCheck,
    val done: Boolean?,
    val action: Boolean,
    val actionEnabled: Boolean = action,
)

object KioskChecklist {
    /** Desde Android 11 el servicio necesita los tipos camera y microphone; antes, no existían. */
    private const val SDK_FOREGROUND_TYPES = 30

    fun items(input: KioskChecklistInput): List<KioskCheckItem> {
        val permissions = input.cameraGranted && input.microphoneGranted
        val service = input.sdkInt < SDK_FOREGROUND_TYPES || (input.serviceCamera && input.serviceMicrophone)
        return listOf(
            KioskCheckItem(KioskCheck.PERMISSIONS, permissions, action = !permissions),
            KioskCheckItem(KioskCheck.HOME, input.berryMedIsHome, action = !input.berryMedIsHome),
            // Sin botón: se resuelve solo con una pantalla de Berry Med adelante y los permisos dados.
            KioskCheckItem(KioskCheck.SERVICE, service, action = false),
            KioskCheckItem(KioskCheck.BATTERY, input.ignoringBatteryOptimizations, action = !input.ignoringBatteryOptimizations),
            // Abrir el kiosco con una URL sin guardar mostraría la vieja (o ninguna).
            KioskCheckItem(KioskCheck.TOTEM, null, action = true, actionEnabled = input.kioskUrlSaved),
        )
    }
}
