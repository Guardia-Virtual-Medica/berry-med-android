// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

import com.guardiavirtual.berrymed.config.YKUSH_PORTS

/**
 * Cómo volver a pedir el permiso USB del otoscopio a propósito (el botón
 * Pedir permiso USB, el Panel Local o la teleconsulta). Berry Med no se lo pide
 * solo: el único pedido es el de Android por `USB_DEVICE_ATTACHED`, que trae
 * «Usar de forma predeterminada». Para que vuelva a aparecer, se apaga y se
 * enciende el puerto del otoscopio y Android lo ve como recién conectado. Sólo
 * sin hub se cae a `requestPermission`, que no trae la casilla. Sin Android,
 * para probar la regla sola.
 */
object OtoscopePermission {
    enum class Step {
        /** Apagar y encender el puerto del otoscopio en el YKUSH3. */
        CYCLE_PORT,

        /** Sin hub que lo pueda hacer: `requestPermission`, sin casilla. */
        REQUEST,

        /** No está enchufado o ya tiene permiso. */
        NOTHING,
    }

    /**
     * [present]: el otoscopio está enchufado. [granted]: ya tiene permiso, y
     * entonces nunca se le apaga el puerto (puede estar publicando o abierto en
     * el Panel Local). [hubUsable]: hay YKUSH3 con permiso. [port]: el puerto
     * del otoscopio en la configuración.
     */
    fun next(present: Boolean, granted: Boolean, hubUsable: Boolean, port: Int?): Step = when {
        !present || granted -> Step.NOTHING
        hubUsable && port != null && port in 1..YKUSH_PORTS -> Step.CYCLE_PORT
        else -> Step.REQUEST
    }
}
