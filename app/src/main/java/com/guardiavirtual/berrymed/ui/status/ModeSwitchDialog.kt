// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.status

import android.app.Activity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import com.guardiavirtual.berrymed.databinding.DialogModeSwitchBinding

/**
 * El diálogo de cambio de modo (`dialog_mode_switch.xml`), con el estilo del
 * de Finalizar. Va arriba de toda la pantalla, panel lateral incluido.
 * Cancelar y tocar fuera de la tarjeta lo cierran.
 */
class ModeSwitchDialog private constructor(private val binding: DialogModeSwitchBinding) {
    val showing: Boolean get() = binding.root.parent != null

    fun dismiss() {
        (binding.root.parent as? ViewGroup)?.removeView(binding.root)
    }

    companion object {
        fun show(activity: Activity, title: Int, body: Int, action: Int, onConfirm: () -> Unit): ModeSwitchDialog {
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            val binding = DialogModeSwitchBinding.inflate(LayoutInflater.from(activity), content, false)
            val dialog = ModeSwitchDialog(binding)
            binding.modeTitle.setText(title)
            binding.modeBody.setText(body)
            binding.modeConfirm.setText(action)
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
