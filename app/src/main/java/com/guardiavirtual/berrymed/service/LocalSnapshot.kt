// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.otoscope.OtoscopePower
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
    /** Cuándo llegó esa lectura, en hora del reloj (milisegundos), o null si no midió. */
    val thermoAtMillis: Long?,
    val nibpRunning: Boolean,
    val nibp: LiveFeed.NibpState,
    /** Cuándo llegó la última presión, en hora del reloj, o null si no se sabe. */
    val nibpAtMillis: Long?,
    /** El adaptador del estetoscopio está enchufado (sin contar el YKUSH3). */
    val stethoPlugged: Boolean,
    /** El tile del estetoscopio se abre si está el adaptador o el YKUSH3 que lo enciende. */
    val stethoAvailable: Boolean,
    val stethoStarting: Boolean,
    val stethoRunning: Boolean,
    val stethoPlaying: Boolean,
    /** El otoscopio está enchufado por USB (sin contar el YKUSH3). */
    val otoscopePlugged: Boolean,
    /** El tile del otoscopio se abre si está el otoscopio o el YKUSH3 que lo enciende. */
    val otoscopeAvailable: Boolean,
    val otoscopeState: OtoscopePower.State,
)
