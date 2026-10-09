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
        pusherCluster = "us2",
    ),
    Dev(
        key = "dev",
        apiBaseUrl = "https://portal-dev.ondoctor365.com/api/v1/webhooks/totem",
        pusherKey = "adac7a549b509b95c930",
        pusherCluster = "us2",
    ),
    ;

    companion object {
        fun fromKey(key: String?): Environment = entries.firstOrNull { it.key == key } ?: Prod

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
