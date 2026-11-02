// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

/**
 * El user agent con el que el `WebView` del kiosco se presenta como Chrome para
 * Android. Zoom Video SDK elige cómo mostrar el video según el navegador que lee
 * acá: con el "; wv" o el "Version/4.0" de WebView lo toma por el navegador viejo
 * de Android, avisa "please use Chrome" y dibuja el video del médico por un camino
 * de respaldo que en la tablet queda en negro. Como Chrome, usa el mismo camino
 * que en Chrome, que en la tablet sí anda.
 */
object KioskUserAgent {
    private val webViewVersion = Regex(""" Version/[\d.]+""")

    fun asChrome(webView: String): String = webView.replace("; wv", "").replace(webViewVersion, "")
}
