// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

data class TotemState(
    val paragraph: String = "Conectá la plaqueta por USB.",
    val heartRate: String = "—",
    val respRate: String = "—",
    val spo2Pulse: String = "—",
    val temperature: String = "—",
    val nibp: String = "—",
    val lastPost: String = "Todavía no se envió",
    val notification: String = "Esperando la plaqueta",
    val berryUp: Boolean = false,
)
