// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import android.app.Activity
import android.os.SystemClock
import com.guardiavirtual.berrymed.R

/**
 * El aviso «Sin conexión» de una pantalla, con el estilo del diálogo de
 * cambio de modo: «Elegir red» abre el panel de Wi-Fi de Android y «Ahora
 * no» lo cierra. Cuándo aparece y cuándo se va lo decide [NoWifiPrompt].
 * La pantalla llama a [start] en `onStart`, a [stop] en `onStop` y a
 * [update] en su refresco.
 */
class NoWifiNotice(private val activity: Activity) {
    private val prompt = NoWifiPrompt()
    private var dialog: ModeSwitchDialog? = null

    val showing: Boolean get() = dialog?.showing == true

    fun start() {
        prompt.restart()
    }

    fun stop() {
        dialog?.dismiss()
        dialog = null
    }

    /** [blocked]: hay otra cosa arriba (el PIN, otro diálogo) y el aviso espera. */
    fun update(connected: Boolean, blocked: Boolean) {
        when (prompt.next(SystemClock.elapsedRealtime(), connected, showing, blocked)) {
            NoWifiPrompt.Step.SHOW -> dialog = ModeSwitchDialog.show(
                activity,
                title = R.string.no_wifi_title,
                body = R.string.no_wifi_body,
                action = R.string.no_wifi_action,
                cancel = R.string.no_wifi_later,
            ) { activity.startActivity(WifiPanel.intent()) }
            NoWifiPrompt.Step.HIDE -> stop()
            NoWifiPrompt.Step.NOTHING -> Unit
        }
    }
}
