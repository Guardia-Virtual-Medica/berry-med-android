// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.i18n.Txt

/**
 * La respuesta a Medir o Parar la presión. [ok] falso: el pedido no se hizo
 * (el panel no está activo, la plaqueta no respondió, la prueba no corre…) y
 * quien lo pidió lo avisa. La pantalla decide por [ok], nunca por el texto.
 */
data class NibpReply(val ok: Boolean, val message: Txt)
