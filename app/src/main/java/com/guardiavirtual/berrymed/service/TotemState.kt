// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

data class TotemState(
    val heartRate: String = "—",
    val respRate: String = "—",
    val spo2Pulse: String = "—",
    val temperature: String = "—",
    val nibp: String = "—",
    val berryUp: Boolean = false,
    val lastData: String = "Último dato: sin datos",
    val pusherUp: Boolean = false,
    /** Los chips de la pantalla principal: conectado, y si ya midió. */
    val thermoConnected: Boolean = false,
    val thermoMeasured: Boolean = false,
    val others: String = "Termómetro sin conectar. Hub YKUSH3 sin conectar. La sesión está detenida.",
    val lastPost: String = "Todavía no se envió",
    val notification: String = "Esperando la plaqueta",
)
