// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.config.CALL_AUDIO_HEADSET
import com.guardiavirtual.berrymed.config.CALL_AUDIO_SPEAKER
import com.guardiavirtual.berrymed.databinding.ItemStatusDeviceBinding
import com.guardiavirtual.berrymed.databinding.ViewStatusPanelBinding
import com.guardiavirtual.berrymed.databinding.ViewStatusRailBinding
import com.guardiavirtual.berrymed.i18n.resolve

/**
 * El panel lateral de estado: abierto (296 dp) o en el riel (64 dp). Sólo
 * dibuja un [StatusPanelState] y avisa los toques; qué hacer con ellos lo
 * decide la pantalla. Fija su propio ancho, así quien lo contiene se acomoda.
 */
class StatusPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val panel = ViewStatusPanelBinding.inflate(LayoutInflater.from(context), this, true)
    private val rail = ViewStatusRailBinding.inflate(LayoutInflater.from(context), this, true)
    private val rows: List<ItemStatusDeviceBinding> = with(panel) {
        listOf(device0, device1, device2, device3, device4, device5, device6)
    }
    private var state: StatusPanelState? = null

    var open: Boolean = true
        private set

    var onSwitchMode: () -> Unit = {}
    var onAudioChoice: (String) -> Unit = {}
    /** Tocar la fila del Wi-Fi (o su ícono en el riel) o una fila que dice «Falta permiso». */
    var onAction: (StatusAction) -> Unit = {}
    var onToggle: (open: Boolean) -> Unit = {}
    /** La casita, en el panel y en el riel: ir a Inicio. Qué pedir antes lo decide la pantalla. */
    var onHome: () -> Unit = {}

    init {
        panel.closeButton.setOnClickListener { toggle(false) }
        rail.root.setOnClickListener { toggle(true) }
        rail.openButton.setOnClickListener { toggle(true) }
        panel.modeButton.setOnClickListener { onSwitchMode() }
        panel.homeButton.setOnClickListener { onHome() }
        rail.railHome.setOnClickListener { onHome() }
        // Con red o sin red: abre el panel de Wi-Fi, o pide la ubicación si es lo que falta.
        panel.wifiArea.setOnClickListener { state?.wifi?.let { onAction(it.action) } }
        rail.railWifi.setOnClickListener { state?.wifi?.let { onAction(it.action) } }
        rows.forEachIndexed { index, row ->
            row.root.setOnClickListener { state?.devices?.getOrNull(index)?.action?.let(onAction) }
        }
        panel.headsetSegment.setOnClickListener { onAudioChoice(CALL_AUDIO_HEADSET) }
        panel.tabletSegment.setOnClickListener { onAudioChoice(CALL_AUDIO_SPEAKER) }
        setOpen(true)
    }

    /** Abre o cierra sin avisar: para dejarlo como lo guarda el runtime al volver a la pantalla. */
    fun setOpen(open: Boolean) {
        this.open = open
        panel.root.visibility = if (open) View.VISIBLE else View.GONE
        rail.root.visibility = if (open) View.GONE else View.VISIBLE
        val width = dp(if (open) OPEN_DP else RAIL_DP)
        layoutParams?.let { params ->
            if (params.width != width) {
                params.width = width
                layoutParams = params
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // El ancho del XML o del código se reemplaza por el del estado.
        setOpen(open)
    }

    private fun toggle(open: Boolean) {
        setOpen(open)
        onToggle(open)
    }

    fun render(s: StatusPanelState) {
        state = s
        renderWifi(s.wifi)
        renderMode(s)
        renderDevices(s)
        renderAudio(s)
    }

    private fun renderWifi(w: WifiRow) {
        val icon = when (w.icon) {
            WifiIcon.WIFI -> R.drawable.ic_wifi
            WifiIcon.WEAK -> R.drawable.ic_network_wifi_1_bar
            WifiIcon.OFF -> R.drawable.ic_wifi_off
        }
        panel.wifiIcon.setImageResource(icon)
        panel.wifiIcon.setBackgroundResource(if (w.offline) R.drawable.status_icon_box_alarm else R.drawable.panel_icon_box)
        tint(panel.wifiIcon, if (w.offline) R.color.alarmText else R.color.text2)
        panel.wifiName.text = w.name.resolve(context)
        panel.wifiStatus.text = w.text.resolve(context)
        panel.wifiStatus.setTextColor(color(statusColor(w.tone)))
        panel.wifiBars.visibility = if (w.offline) View.GONE else View.VISIBLE
        // El chevron siempre: la fila siempre se toca. Con red, al lado de las barras.
        panel.wifiChevron.visibility = View.VISIBLE
        val on = color(if (w.tone == StatusTone.WARN) R.color.warn else R.color.text2)
        val off = color(R.color.line3)
        listOf(panel.bar1, panel.bar2, panel.bar3, panel.bar4).forEachIndexed { index, bar ->
            bar.backgroundTintList = ColorStateList.valueOf(if (index < w.bars) on else off)
        }
        val description = context.getString(
            if (w.action == StatusAction.LOCATION) R.string.status_ask_permission else R.string.status_wifi_settings,
        )
        panel.wifiArea.contentDescription = description
        rail.railWifi.contentDescription = description

        rail.railWifiIcon.setImageResource(icon)
        rail.railWifi.background = if (w.offline) drawable(R.drawable.status_icon_box_alarm) else null
        tint(rail.railWifiIcon, if (w.offline) R.color.alarmText else R.color.text2)
        dot(rail.railWifiDot, w.tone)
    }

    private fun renderMode(s: StatusPanelState) {
        // En Inicio no hay modo ni casita: ya se está ahí.
        val home = s.mode == PanelMode.HOME
        val shown = if (home) View.GONE else View.VISIBLE
        panel.modeSection.visibility = shown
        panel.modeDivider.visibility = shown
        panel.homeButton.visibility = shown
        rail.railMode.visibility = shown
        rail.railHomeBox.visibility = shown
        if (home) return
        val remote = s.mode == PanelMode.REMOTE
        val boxColor = ColorStateList.valueOf(color(if (remote) R.color.ondoctor_icon else R.color.local_icon))
        val icon = if (remote) R.drawable.ic_video_call else R.drawable.ic_stethoscope
        panel.modeIcon.setImageResource(icon)
        panel.modeIcon.backgroundTintList = boxColor
        rail.railModeIcon.setImageResource(icon)
        rail.railModeIcon.backgroundTintList = boxColor
        panel.modeName.setText(if (remote) R.string.status_remote else R.string.status_local)
        panel.modeSub.setText(
            when (s.mode) {
                PanelMode.REMOTE -> R.string.status_remote_sub
                PanelMode.LOCAL -> R.string.status_local_sub
                PanelMode.LOCAL_LOCKED -> R.string.status_local_locked_sub
                PanelMode.HOME -> R.string.status_local_sub
            },
        )
        panel.modeButtonText.setText(
            when (s.mode) {
                PanelMode.REMOTE -> R.string.status_to_local
                PanelMode.LOCAL -> R.string.status_to_remote
                PanelMode.LOCAL_LOCKED -> R.string.status_back_to_remote
                PanelMode.HOME -> R.string.status_to_local
            },
        )
        val blocked = s.modeBlocked
        panel.modeButton.isEnabled = blocked == null
        panel.modeButtonIcon.setImageResource(if (blocked == null) R.drawable.ic_swap_horiz else R.drawable.ic_lock)
        panel.modeBlocked.visibility = if (blocked == null) View.GONE else View.VISIBLE
        panel.modeBlockedText.text = blocked?.resolve(context).orEmpty()
    }

    private fun renderDevices(s: StatusPanelState) {
        rows.forEachIndexed { index, row ->
            val device = s.devices.getOrNull(index)
            if (device == null) {
                row.root.visibility = View.GONE
                return@forEachIndexed
            }
            row.root.visibility = View.VISIBLE
            val bad = device.tone == StatusTone.BAD
            row.root.background = if (bad) drawable(R.drawable.status_row_bad) else null
            row.deviceName.text = device.name.resolve(context)
            row.deviceName.setTextColor(color(if (bad) R.color.text else R.color.text2))
            row.deviceStatus.text = device.text.resolve(context)
            row.deviceStatus.setTextColor(color(statusColor(device.tone)))
            dot(row.deviceDot, device.tone)
            // Sólo se tocan las que dicen «Falta permiso».
            row.root.isClickable = device.action != null
            row.root.contentDescription =
                if (device.action != null) {
                    context.getString(
                        R.string.status_device_action,
                        device.name.resolve(context),
                        context.getString(R.string.status_ask_permission),
                    )
                } else {
                    null
                }
        }
        val count = context.getString(R.string.status_count, s.okCount, s.devices.size)
        val bad = s.countTone == StatusTone.BAD
        panel.deviceCount.text = count
        panel.deviceCount.setBackgroundResource(if (bad) R.drawable.status_count_bad else R.drawable.status_count)
        panel.deviceCount.setTextColor(color(countColor(s.countTone)))

        rail.railCount.text = count
        rail.railCount.setTextColor(color(if (bad) R.color.alarmText else R.color.text2))
        tint(rail.railDevicesIcon, if (bad) R.color.alarmText else R.color.text2)
        rail.railDevices.background = if (bad) drawable(R.drawable.status_icon_box_alarm) else null
        dot(rail.railDevicesDot, s.countTone)
    }

    private fun renderAudio(s: StatusPanelState) {
        val headset = s.callRoute == CallRoute.HEADSET
        // Sin nada en el jack, Auricular no se puede tocar y se ve elegido Altavoz.
        panel.headsetSegment.isEnabled = s.jackPresent
        panel.headsetSegment.isSelected = headset
        panel.tabletSegment.isSelected = !headset
        panel.callHelp.text = s.callHelp.resolve(context)
        rail.railAudioIcon.setImageResource(if (headset) R.drawable.ic_headset_mic else R.drawable.ic_volume_up)
    }

    /** Verde y ámbar, punto lleno; rojo, aro hueco. */
    private fun dot(view: View, tone: StatusTone) {
        if (tone == StatusTone.BAD) {
            view.setBackgroundResource(R.drawable.status_ring)
            view.backgroundTintList = null
        } else {
            view.setBackgroundResource(R.drawable.panel_dot)
            view.backgroundTintList = ColorStateList.valueOf(color(if (tone == StatusTone.OK) R.color.ok else R.color.warn))
        }
    }

    private fun statusColor(tone: StatusTone): Int = when (tone) {
        StatusTone.OK -> R.color.muted
        StatusTone.WARN -> R.color.warn
        StatusTone.BAD -> R.color.alarmText
    }

    private fun countColor(tone: StatusTone): Int = when (tone) {
        StatusTone.OK -> R.color.ok
        StatusTone.WARN -> R.color.warn
        StatusTone.BAD -> R.color.alarmText
    }

    private fun tint(view: ImageView, colorRes: Int) =
        ImageViewCompat.setImageTintList(view, ColorStateList.valueOf(color(colorRes)))

    private fun color(res: Int): Int = ContextCompat.getColor(context, res)

    private fun drawable(res: Int) = ContextCompat.getDrawable(context, res)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val OPEN_DP = 296
        const val RAIL_DP = 64
    }
}
