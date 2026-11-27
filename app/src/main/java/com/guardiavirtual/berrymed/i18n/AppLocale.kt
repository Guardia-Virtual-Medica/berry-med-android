// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.i18n

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
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
            skipAppCompatSync(context)
            if (AppLanguage.match(manager.applicationLocales.toLanguageTags()) == null) {
                manager.applicationLocales = LocaleList.forLanguageTags(AppLanguage.DEFAULT.tag)
            }
        }
    }

    /**
     * La primera vez que abre una pantalla en Android 13 o más, AppCompat copia
     * al sistema el idioma que guardó él (`autoStoreLocales`, para Android 12 o
     * menos) y después habilita su servicio para no volver a hacerlo. Al
     * actualizar desde la 1.4.0 no guardó nada: copiaba «ningún idioma» encima
     * del español recién fijado y la app pasaba al idioma de la tablet (se
     * probó en el emulador). Habilitar el servicio antes es decirle que ya está.
     */
    private fun skipAppCompatSync(context: Context) {
        val holder = ComponentName(context, APP_COMPAT_LOCALES_SERVICE)
        val packages = context.packageManager
        if (packages.getComponentEnabledSetting(holder) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return
        packages.setComponentEnabledSetting(holder, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    }

    /** El servicio de AppCompat que declara `autoStoreLocales` en el manifest. */
    private const val APP_COMPAT_LOCALES_SERVICE = "androidx.appcompat.app.AppLocalesMetadataHolderService"

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
     * Para `attachBaseContext` de cada pantalla: [base] en el idioma en uso si
     * Android lo armó en otro, y si no el mismo. Pasa una sola vez, en Android
     * 13 o más: la primera pantalla después de que [init] fija el español la
     * arma el sistema antes de que arranque el proceso, todavía sin idioma de
     * la app (en el emulador, con la tablet en inglés, Inicio salía en inglés).
     * Las pantallas que se abren después ya llegan en español.
     */
    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context): Context {
        val wanted = current()
        if (AppLanguage.match(base.resources.configuration.locales.toLanguageTags()) == wanted) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(wanted.tag))
        return base.createConfigurationContext(config)
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
