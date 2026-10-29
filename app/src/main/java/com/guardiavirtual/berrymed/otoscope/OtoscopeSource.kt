// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.graphics.Bitmap
import livekit.org.webrtc.VideoFrame

/**
 * Cómo se ve la imagen del otoscopio en la tarjeta. Depende de la tablet:
 * si Android lo lista como cámara externa va por Camera2 ([CameraXOtoscope]);
 * si no, por UVC directo sobre USB ([UvcOtoscope]). La tarjeta es la misma.
 */
interface OtoscopeSource {
    /** Lo que dice la tarjeta: "por Camera2" o "por UVC". */
    val label: String

    /** Abre la imagen en la tarjeta. [done] llega en el hilo de la pantalla: null si abrió, o el motivo. */
    fun open(done: (String?) -> Unit)

    /** Suelta la cámara. [done] llega, en cualquier hilo, cuando ya está suelta: recién ahí se apaga el puerto. */
    fun close(done: () -> Unit = {})

    /** La imagen de este momento, para Congelar; null si todavía no hay. */
    fun snapshot(): Bitmap?
}

/**
 * El otoscopio sin vista, para la teleconsulta: entrega cada cuadro a quien
 * lo publica en LiveKit. Los mismos dos caminos que [OtoscopeSource]
 * ([CameraXOtoscopeFrames] y [UvcOtoscopeFrames]), elegidos con la misma
 * regla ([OtoscopePower]).
 */
interface OtoscopeFrames {
    /** "por Camera2" o "por UVC". */
    val label: String

    /**
     * Abre el otoscopio y empieza a mandar cuadros a [sink], desde un hilo de
     * trabajo. Cada cuadro vale sólo durante la llamada: quien lo quiera
     * guardar le hace `retain()`. [done] llega una vez, en cualquier hilo:
     * null si abrió, o el motivo.
     */
    fun start(sink: (VideoFrame) -> Unit, done: (String?) -> Unit)

    /** Suelta el otoscopio. [done] llega, en cualquier hilo, cuando ya está suelto: recién ahí se apaga el puerto. */
    fun stop(done: () -> Unit = {})
}
