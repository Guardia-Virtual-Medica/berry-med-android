// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * El idioma elegido para Berry Med, aparte del de la tablet. Mientras nadie
 * elija, es el español ([AppLanguage.DEFAULT]): la app no sigue el idioma de
 * la tablet, así una tablet en inglés no pasa a inglés al actualizar.
 *
 * En Android 13 o más lo guarda el sistema (`LocaleManager`), lo aplica a
 * todo el proceso (pantallas, servicio y contexto de la aplicación) y lo
 * muestra en Ajustes → Apps → Berry Med → Idioma. En los anteriores lo guarda
 * AppCompat (`autoStoreLocales` en el manifest) y lo aplica sólo a las
 * pantallas, por eso lo que no es pantalla pasa por [localized].
 */
object AppLocale {
    /** El `LocaleManager` del sistema, en Android 13 o más (`Any` para no nombrar la clase en los anteriores). */
    private var localeManager: Any? = null

    /**
     * Desde `BerryMedApp.onCreate`. En Android 13 o más, sin idioma elegido,
     * fija el español en el sistema antes de que se abra ninguna pantalla
     * (`AppCompatDelegate` no puede: todavía no hay ninguna).
     */
    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            localeManager = manager
            if (AppLanguage.match(manager.applicationLocales.toLanguageTags()) == null) {
                manager.applicationLocales = LocaleList.forLanguageTags(AppLanguage.DEFAULT.tag)
            }
        }
    }

    /**
     * Android 12 o menos, desde la primera pantalla después de `super.onCreate`
     * (cuando AppCompat ya leyó lo guardado): sin idioma elegido, fija el
     * español. Si la tablet no está en español la pantalla se recrea una vez,
     * sólo la primera vez que se abre la app.
     */
    fun ensureChosen() {
        if (Build.VERSION.SDK_INT >= 33) return
        if (AppLanguage.match(AppCompatDelegate.getApplicationLocales().toLanguageTags()) == null) set(AppLanguage.DEFAULT)
    }

    /** El idioma elegido, o null si nadie eligió. */
    private fun chosen(): AppLanguage? {
        val manager = localeManager
        val tags = if (Build.VERSION.SDK_INT >= 33 && manager is LocaleManager) {
            manager.applicationLocales.toLanguageTags()
        } else {
            AppCompatDelegate.getApplicationLocales().toLanguageTags()
        }
        return AppLanguage.match(tags)
    }

    /** El idioma en uso: el elegido o, sin elegir, el español. */
    fun current(): AppLanguage = chosen() ?: AppLanguage.DEFAULT

    /** Cambia el idioma: Android lo recuerda y recrea las pantallas abiertas con los textos nuevos. */
    fun set(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }

    /**
     * [context] con el idioma en uso, para lo que no es una pantalla (el
     * servicio, la notificación). En Android 13 o más da los mismos textos
     * que el contexto: el sistema ya lo aplicó.
     */
    // No cambia el idioma de la app: es un contexto aparte, sólo para leer los textos.
    @SuppressLint("AppBundleLocaleChanges")
    fun localized(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(current().tag))
        return context.createConfigurationContext(config)
    }
}
