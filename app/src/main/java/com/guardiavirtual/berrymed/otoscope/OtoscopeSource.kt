// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import android.graphics.Bitmap

/**
 * Cómo se ve la imagen del otoscopio en la tarjeta. Depende de la tablet:
 * si Android lo lista como cámara externa va por Camera2 ([CameraXOtoscope]);
 * si no, por UVC directo sobre USB ([UvcOtoscope]). La tarjeta es la misma.
 */
interface OtoscopeSource {
    /** Lo que dice la tarjeta: "por Camera2" o "por UVC". */
    val label: String

    /** Si este camino ve el otoscopio enchufado ahora. */
    fun isAvailable(): Boolean

    /** Abre la imagen en la tarjeta. [done] llega en el hilo de la pantalla: null si abrió, o el motivo. */
    fun open(done: (String?) -> Unit)

    /** Suelta la cámara. [done] llega, en cualquier hilo, cuando ya está suelta: recién ahí se apaga el puerto. */
    fun close(done: () -> Unit = {})

    /** La imagen de este momento, para Congelar; null si todavía no hay. */
    fun snapshot(): Bitmap?
}
