// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Display
import com.guardiavirtual.berrymed.R

/**
 * La escala de la UI. Las pantallas están diseñadas a 1280×800 dp, pero la
 * tablet (1920×1080 a 160 dpi) se presenta como 1920×1080 dp y todo se ve un
 * 35 % más chico. Se sube la densidad para que el lado corto de la pantalla
 * mida [DESIGN_SHORT_DP]: en esa tablet queda en 216 dpi, 1422×800 dp. En una
 * pantalla que ya mide 800 dp o menos no cambia nada.
 *
 * La pantalla principal, Testing y el Panel Local la aplican enteros (en
 * attachBaseContext). En el kiosco la página web no se toca: sólo el panel
 * lateral y los diálogos que van encima, con [wrap].
 */
object UiScale {
    const val DESIGN_SHORT_DP = 800

    /** La densidad con la que [shortPx] mide [DESIGN_SHORT_DP]; nunca menos que [currentDpi]. */
    fun targetDpi(shortPx: Int, currentDpi: Int): Int =
        maxOf(currentDpi, shortPx * DisplayMetrics.DENSITY_DEFAULT / DESIGN_SHORT_DP)

    /** [context] con la densidad de diseño, o el mismo si ya la tiene. Para las Activities. */
    fun scaled(context: Context): Context {
        val config = override(context) ?: return context
        return context.createConfigurationContext(config)
    }

    /**
     * Para inflar vistas escaladas dentro de una pantalla que no lo está (el
     * kiosco). Sigue siendo un envoltorio de [context], así que la Activity se
     * alcanza igual; el tema es el del Panel Local.
     */
    fun wrap(context: Context): Context {
        val config = override(context) ?: return context
        return ContextThemeWrapper(context, R.style.Theme_BerryMed_Panel).apply { applyOverrideConfiguration(config) }
    }

    private fun override(context: Context): Configuration? {
        val current = context.resources.configuration
        val target = targetDpi(shortSidePx(context) ?: return null, current.densityDpi)
        if (target == current.densityDpi) return null
        val factor = current.densityDpi.toFloat() / target
        return Configuration(current).apply {
            densityDpi = target
            screenWidthDp = (current.screenWidthDp * factor).toInt()
            screenHeightDp = (current.screenHeightDp * factor).toInt()
            smallestScreenWidthDp = (current.smallestScreenWidthDp * factor).toInt()
        }
    }

    /**
     * El lado corto de la pantalla entera, barras incluidas. Desde el contexto
     * de la aplicación: en attachBaseContext la Activity todavía no tiene
     * pantalla y el DisplayManager de su contexto falla.
     */
    @Suppress("DEPRECATION")
    private fun shortSidePx(context: Context): Int? {
        val app = context.applicationContext ?: context
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
        return minOf(metrics.widthPixels, metrics.heightPixels).takeIf { it > 0 }
    }
}
