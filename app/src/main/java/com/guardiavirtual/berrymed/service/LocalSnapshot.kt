// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.protocol.EcgInfo
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.protocol.VitalSigns

/** Lo que el Panel Local muestra en cada refresco, aparte de las ondas, que lee del [LiveFeed]. */
data class LocalSnapshot(
    val berryUp: Boolean,
    val lastDataAge: Double?,
    val vitals: VitalSigns,
    val ecgInfo: EcgInfo,
    val thermoConnected: Boolean,
    /** La última lectura del termómetro infrarrojo, ya en °C, o null si no midió. */
    val thermoReading: String?,
    val thermoAge: Long?,
    val nibpRunning: Boolean,
    val nibp: LiveFeed.NibpState,
)
