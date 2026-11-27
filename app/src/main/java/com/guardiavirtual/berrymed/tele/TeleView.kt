// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import com.guardiavirtual.berrymed.i18n.Txt
import com.guardiavirtual.berrymed.otoscope.OtoscopePower

/** Lo que muestra Testing → Teleconsulta (LiveKit). */
data class TeleView(
    val status: TeleStatus,
    /** Los tipos del servicio en primer plano, como "connectedDevice|camera|microphone". */
    val serviceTypes: String,
    val serviceCamera: Boolean,
    val serviceMicrophone: Boolean,
    /** Por dónde llega la imagen mientras se publica el otoscopio (Camera2 o UVC); null si no se publica. */
    val videoPath: OtoscopePower.Path?,
    val videoSize: String?,
    val framesPerSecond: Double,
    /** El nivel del estetoscopio que sale, de 0 a 100. */
    val levelPercent: Int,
    /** Si Android le da silencio a la captura (otra app dueña del micrófono); null si no hay captura. */
    val silenced: Boolean?,
    /** La salida elegida para la videollamada y el dispositivo de comunicación actual. */
    val callAudio: Txt,
)
