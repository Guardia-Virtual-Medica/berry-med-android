// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

/** Los permisos de la app que muestra el panel lateral, cada uno en su fila. */
enum class AppPermission {
    /** El otoscopio (Camera2 y UVC) y la cámara de la tablet. */
    CAMERA,

    /** El estetoscopio. */
    MICROPHONE,

    /** Sólo para ver el nombre de la red Wi-Fi. */
    LOCATION,
}

/**
 * Los permisos de la app, sin Android: cuáles se piden al abrir el kiosco,
 * el Panel Local o la pantalla principal, todos juntos, y qué hacer cuando
 * alguien toca una fila que dice que falta uno.
 */
object AppPermissions {
    const val CAMERA = "android.permission.CAMERA"
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /** Android 13 (API 33), desde donde las notificaciones piden permiso. */
    private const val NOTIFICATIONS_SDK = 33

    /**
     * Lo que se pide por [permission]. La ubicación va precisa y aproximada
     * juntas: desde Android 12 un pedido sólo de la precisa se ignora.
     */
    fun manifest(permission: AppPermission): List<String> = when (permission) {
        AppPermission.CAMERA -> listOf(CAMERA)
        AppPermission.MICROPHONE -> listOf(RECORD_AUDIO)
        AppPermission.LOCATION -> listOf(FINE_LOCATION, COARSE_LOCATION)
    }

    /**
     * Los que faltan, para pedirlos todos juntos al abrir una pantalla:
     * cámara, micrófono, ubicación y, desde Android 13, notificaciones. Con
     * la precisa sin dar va también la aproximada, aunque esté dada.
     */
    fun missing(sdkInt: Int, granted: (String) -> Boolean): List<String> {
        val wanted = buildList {
            add(CAMERA)
            add(RECORD_AUDIO)
            add(FINE_LOCATION)
            add(COARSE_LOCATION)
            if (sdkInt >= NOTIFICATIONS_SDK) add(NOTIFICATIONS)
        }
        return wanted.filter { permission ->
            if (permission == COARSE_LOCATION) !granted(FINE_LOCATION) else !granted(permission)
        }
    }

    /** Los que el panel lateral muestra como faltantes. La ubicación cuenta sólo con la precisa: sin ella no hay nombre de red. */
    fun missingForPanel(granted: (String) -> Boolean): Set<AppPermission> =
        AppPermission.entries.filterNot { granted(manifest(it).first()) }.toSet()

    /**
     * Si, después de un pedido, hay que abrir los ajustes de la app. Sólo
     * cuando el pedido salió de tocar una fila y alguno quedó rechazado sin
     * que Android lo deje volver a pedir ([deniedBlocked]: rechazado y sin
     * explicación pendiente, que es rechazado dos veces o «No volver a
     * preguntar»). El pedido de al abrir nunca manda a los ajustes.
     */
    fun openSettingsAfter(fromTap: Boolean, deniedBlocked: List<Boolean>): Boolean =
        fromTap && deniedBlocked.any { it }
}
