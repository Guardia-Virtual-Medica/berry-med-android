// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import com.guardiavirtual.berrymed.databinding.DialogModeSwitchBinding
import com.guardiavirtual.berrymed.ui.UiScale

/**
 * El diálogo de cambio de modo (`dialog_mode_switch.xml`), con el estilo del
 * de Finalizar. Va arriba de toda la pantalla, panel lateral incluido. Lo
 * usa también el aviso «Sin conexión» ([NoWifiNotice]).
 * Cancelar y tocar fuera de la tarjeta lo cierran. Se infla a la escala de
 * diseño ([UiScale]), también encima del kiosco.
 */
class ModeSwitchDialog private constructor(private val binding: DialogModeSwitchBinding) {
    val showing: Boolean get() = binding.root.parent != null

    fun dismiss() {
        (binding.root.parent as? ViewGroup)?.removeView(binding.root)
    }

    companion object {
        /** [cancel] cambia el texto de Cancelar: el aviso «Sin conexión» dice «Ahora no». */
        fun show(
            activity: Activity,
            title: Int,
            body: Int,
            action: Int,
            cancel: Int? = null,
            onConfirm: () -> Unit,
        ): ModeSwitchDialog {
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            val binding = DialogModeSwitchBinding.inflate(LayoutInflater.from(UiScale.wrap(activity)), content, false)
            val dialog = ModeSwitchDialog(binding)
            binding.modeTitle.setText(title)
            binding.modeBody.setText(body)
            binding.modeConfirm.setText(action)
            if (cancel != null) binding.modeCancel.setText(cancel)
            binding.root.setOnClickListener { dialog.dismiss() }
            binding.modeCancel.setOnClickListener { dialog.dismiss() }
            binding.modeConfirm.setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
            content.addView(binding.root)
            return dialog
        }
    }
}
