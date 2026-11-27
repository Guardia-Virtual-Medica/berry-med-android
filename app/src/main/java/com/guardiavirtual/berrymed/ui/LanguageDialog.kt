// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.button.MaterialButton
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.databinding.DialogLanguageBinding
import com.guardiavirtual.berrymed.i18n.AppLanguage

/**
 * El selector de idioma de Inicio (`dialog_language.xml`), con el estilo del
 * diálogo de cambio de modo: los idiomas con su nombre propio, el que está en
 * uso marcado, y Cancelar. Tocar fuera de la tarjeta también lo cierra.
 * Elegir otro idioma llama a [onPick]; el mismo lo cierra sin más.
 */
class LanguageDialog private constructor(private val binding: DialogLanguageBinding) {
    fun dismiss() {
        (binding.root.parent as? ViewGroup)?.removeView(binding.root)
    }

    companion object {
        fun show(activity: Activity, current: AppLanguage, onPick: (AppLanguage) -> Unit): LanguageDialog {
            val content = activity.findViewById<FrameLayout>(android.R.id.content)
            val inflater = LayoutInflater.from(UiScale.wrap(activity))
            val binding = DialogLanguageBinding.inflate(inflater, content, false)
            val dialog = LanguageDialog(binding)
            for (language in AppLanguage.entries) {
                val row = inflater.inflate(R.layout.item_language, binding.languageList, false) as MaterialButton
                row.setText(language.nativeName)
                if (language == current) {
                    row.setIconResource(R.drawable.ic_check)
                    row.iconTint = ColorStateList.valueOf(activity.getColor(R.color.primary))
                    row.strokeColor = ColorStateList.valueOf(activity.getColor(R.color.primary))
                    row.isSelected = true
                }
                row.setOnClickListener {
                    dialog.dismiss()
                    if (language != current) onPick(language)
                }
                binding.languageList.addView(row)
            }
            binding.root.setOnClickListener { dialog.dismiss() }
            binding.languageCancel.setOnClickListener { dialog.dismiss() }
            content.addView(binding.root)
            return dialog
        }
    }
}
