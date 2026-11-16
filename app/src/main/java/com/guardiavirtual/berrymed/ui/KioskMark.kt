// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

/**
 * La marca con la que la web del kiosco sabe que corre adentro de Berry Med: el `WebView` la deja
 * en `window.BerryMed`. En Chrome, en otro tótem o en otra maleta no existe.
 *
 * Hoy la web la usa para una sola cosa: en esta maleta la cámara de la tablet y el otoscopio no
 * entran juntos en el USB (ver `docs/hardware.md`), así que mientras el otoscopio está encendido
 * la videollamada suelta la cámara. En cualquier otro equipo la web no la encuentra y no cambia nada.
 *
 * Es sólo un aviso: no tiene ningún método con `@JavascriptInterface`, así que la página no puede
 * llamar a nada de la app. No va en el user agent porque Zoom lo lee para elegir cómo dibujar el
 * video (ver [KioskUserAgent]).
 */
object KioskMark {
    const val NAME = "BerryMed"
}
