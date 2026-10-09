// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.config

/**
 * Los entornos de OnDoctor. Elegir uno completa la dirección de la API y la
 * clave y el cluster de Pusher; los tres se pueden cambiar a mano después.
 */
enum class Environment(
    val key: String,
    val apiBaseUrl: String,
    val pusherKey: String,
    val pusherCluster: String,
) {
    Prod(
        key = "prod",
        apiBaseUrl = "https://portal.ondoctor365.com/api/v1/webhooks/totem",
        pusherKey = "b5b07bcf4eb9935b29c7",
        pusherCluster = "sa1",
    ),
    Dev(
        key = "dev",
        apiBaseUrl = "https://portal-dev.ondoctor365.com/api/v1/webhooks/totem",
        pusherKey = "37b143a0f816ccba0eb2",
        pusherCluster = "sa1",
    ),
    ;

    companion object {
        fun fromKey(key: String?): Environment = entries.firstOrNull { it.key == key } ?: Prod

        /**
         * Claves que la app traía por defecto y resultaron ser de otra app de
         * Pusher: se reemplazan por las del entorno al cargar la configuración.
         * adac7a549b509b95c930 venía de los .env locales de los fronts y no es
         * la app en la que publica el backend de dev.
         */
        private val outdatedKeys = mapOf("adac7a549b509b95c930" to Dev)

        fun replacementFor(pusherKey: String): Environment? = outdatedKeys[pusherKey.trim()]

        /** Una configuración guardada antes del selector no tiene entorno: se deduce de su dirección. */
        fun guess(apiBaseUrl: String?): Environment =
            if (apiBaseUrl.orEmpty().lowercase().contains("portal-dev.")) Dev else Prod
    }
}

/** OnDoctor manda las órdenes de cada tótem por el canal público `totem.<external_id>`. */
fun defaultChannel(totemId: String): String {
    val id = totemId.trim()
    return if (id.isEmpty()) "" else "totem.$id"
}
