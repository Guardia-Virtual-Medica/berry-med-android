// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.i18n.Txt
import com.guardiavirtual.berrymed.i18n.join
import com.guardiavirtual.berrymed.i18n.plural
import com.guardiavirtual.berrymed.i18n.txt
import com.guardiavirtual.berrymed.net.PostOutcome

/** Los valores de los vitales son datos («72», «—»); los textos de estado, [Txt] que resuelve la pantalla. */
data class TotemState(
    val heartRate: String = "—",
    val respRate: String = "—",
    val spo2Pulse: String = "—",
    val temperature: String = "—",
    val nibp: String = "—",
    val berryUp: Boolean = false,
    val lastData: Txt = TotemStatus.lastData(null),
    val pusherUp: Boolean = false,
    /** Los chips de la pantalla principal: conectado, y si ya midió. */
    val thermoConnected: Boolean = false,
    val thermoMeasured: Boolean = false,
    val others: Txt = TotemStatus.others(
        thermoConnected = false,
        thermoMeasured = false,
        hubPlugged = false,
        active = false,
        missing = emptyList(),
        permissionMissing = false,
    ),
    val lastPost: Txt = TotemStatus.NOT_SENT,
    val notification: Txt = TotemStatus.WAITING_BOARD,
)

/**
 * Los textos de estado de la pantalla principal y de la notificación, sin
 * Android: el runtime junta los datos y estas reglas arman el [Txt].
 */
object TotemStatus {
    val WAITING_BOARD: Txt = txt(R.string.notif_waiting_board)
    val NOT_SENT: Txt = txt(R.string.rt_post_none)

    /** La notificación del servicio. */
    fun notification(permissionMissing: Boolean, berryUp: Boolean, localMode: Boolean, active: Boolean): Txt = when {
        permissionMissing -> txt(R.string.notif_usb_permission_missing)
        !berryUp -> WAITING_BOARD
        localMode -> txt(R.string.notif_local_open)
        active -> txt(R.string.notif_sending)
        else -> txt(R.string.notif_stopped)
    }

    /** Cuánto hace que llegó el último dato de la plaqueta, en segundos; null si nunca. */
    fun lastData(ageSeconds: Double?): Txt =
        if (ageSeconds == null) txt(R.string.rt_last_data_none) else txt(R.string.rt_last_data_ago, ageSeconds)

    /** El renglón de estado: frases enteras, una al lado de la otra. */
    fun others(
        thermoConnected: Boolean,
        thermoMeasured: Boolean,
        hubPlugged: Boolean,
        active: Boolean,
        missing: List<Txt>,
        permissionMissing: Boolean,
    ): Txt {
        val parts = ArrayList<Txt>()
        parts += when {
            !thermoConnected -> txt(R.string.rt_thermo_disconnected)
            thermoMeasured -> txt(R.string.rt_thermo_connected)
            else -> txt(R.string.rt_thermo_no_reading)
        }
        parts += txt(if (hubPlugged) R.string.rt_hub_present else R.string.rt_hub_absent)
        parts += txt(if (active) R.string.rt_session_sending else R.string.rt_session_stopped)
        if (missing.isNotEmpty()) parts += txt(R.string.rt_missing_config, join(missing, ", "))
        if (permissionMissing) parts += txt(R.string.rt_usb_permission_hint)
        return join(parts, " ")
    }

    /** Cómo le fue al último POST; [active] es si había consulta (sin consulta, un 404 es esperar). */
    fun lastPost(outcome: PostOutcome, active: Boolean): Txt = when {
        outcome == PostOutcome.Ok -> txt(R.string.rt_post_ok)
        outcome is PostOutcome.Http && outcome.code == 404 && !active -> txt(R.string.rt_post_waiting_take)
        outcome is PostOutcome.Http && outcome.code == 404 -> txt(R.string.rt_post_not_open)
        outcome is PostOutcome.Http -> txt(R.string.rt_post_http, outcome.code)
        outcome is PostOutcome.Failed -> txt(R.string.rt_post_failed, outcome.message)
        else -> txt(R.string.rt_post_failed_plain)
    }

    /** Si la cola tiró paquetes en esta consulta, el último envío lo dice. */
    fun withDropped(text: Txt, dropped: Long): Txt =
        if (dropped == 0L) text else plural(R.plurals.rt_post_dropped, dropped.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), text, dropped)
}
