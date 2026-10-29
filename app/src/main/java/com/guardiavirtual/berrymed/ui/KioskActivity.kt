// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.config.pinAccepted
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.service.TotemRuntime
import com.guardiavirtual.berrymed.tele.KioskUrls
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * El kiosco de OnDoctor dentro de Berry Med, como lo hacía la app del
 * proveedor: un `WebView` a pantalla completa con la URL del kiosco. Es la app
 * de inicio de la tablet, así Android la abre al encender y con ella adelante
 * el servicio tiene cámara y micrófono para la teleconsulta.
 *
 * Cada dirección que carga el kiosco pasa por [TotemRuntime.onKioskUrl]: la
 * que trae `serverUrl` y `token` hace entrar a la sala del encuentro, y el
 * inicio o el login la hacen salir. El token no se escribe en ningún lado.
 *
 * Atrás no sale: el paciente no tiene que poder irse. Se sale a la
 * configuración con cinco toques en la esquina de arriba a la izquierda y el
 * PIN del Panel Local.
 */
class KioskActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private lateinit var root: FrameLayout
    private lateinit var empty: LinearLayout
    private var loadedUrl: String? = null
    private var cornerTaps = 0
    private var firstTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        setContentView(root)
        empty = buildEmptyView()
        root.addView(empty)
        root.addView(buildCorner())
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                // El paciente no sale del kiosco con atrás.
                override fun handleOnBackPressed() = Unit
            },
        )
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Con el kiosco adelante el servicio suma cámara y micrófono.
        MonitorService.promote(this)
        loadConfiguredUrl()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let {
            root.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun runtime() = (application as BerryMedApp).runtime

    /** La URL guardada; si cambió en la configuración, se vuelve a cargar. */
    private fun loadConfiguredUrl() {
        thread(name = "berry-kiosk-config") {
            val saved = try {
                runBlocking { runtime().store.load() }.kioskUrl.trim()
            } catch (error: Exception) {
                Log.e(TotemRuntime.TAG, "[KIOSCO] No se pudo leer la configuración: $error")
                runtime().config.kioskUrl.trim()
            }
            handler.post { if (!isDestroyed) show(saved) }
        }
    }

    private fun show(url: String) {
        if (url.isEmpty() || KioskUrls.origin(url) == null) {
            empty.visibility = View.VISIBLE
            webView?.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE
        val view = webView ?: createWebView().also { webView = it }
        view.visibility = View.VISIBLE
        if (url != loadedUrl) {
            loadedUrl = url
            runtime().log("[KIOSCO] Se abre ${KioskUrls.origin(url)}")
            view.loadUrl(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Zoom mira el navegador: sin el "; wv" de WebView se presenta como Chrome para Android.
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = KioskClient()
        view.webChromeClient = KioskChrome()
        root.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return view
    }

    /** Mira cada dirección del kiosco: cargas, navegaciones dentro de la SPA y el esquema del proveedor. */
    private inner class KioskClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (KioskUrls.isProviderScheme(url)) {
                // koplak://auth?serverUrl=…&token=…: sólo se toman las credenciales, no se navega.
                runtime().onKioskUrl(url)
                return true
            }
            val scheme = request.url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return true
            runtime().onKioskUrl(url)
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            runtime().onKioskUrl(url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            runtime().onKioskUrl(url)
        }

        /** Si el proceso del WebView se cae, se arma la pantalla de nuevo en lugar de cerrar la app. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            runtime().log("[KIOSCO] Se cayó el proceso del WebView: se vuelve a abrir")
            root.removeView(view)
            view.destroy()
            webView = null
            loadedUrl = null
            loadConfiguredUrl()
            return true
        }
    }

    private inner class KioskChrome : WebChromeClient() {
        /**
         * Cámara y micrófono sólo para el origen del kiosco y los de Zoom, y sólo
         * si Android ya se los dio a Berry Med. Nada más.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val origin = request.origin?.toString().orEmpty()
            val allowed = allowedOrigin(origin)
            val wanted = request.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> granted(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> granted(Manifest.permission.RECORD_AUDIO)
                    else -> false
                }
            }
            if (!allowed || wanted.isEmpty()) {
                runtime().log("[KIOSCO] Se niega ${request.resources.joinToString()} a ${KioskUrls.origin(origin) ?: "un origen desconocido"}")
                request.deny()
                return
            }
            request.grant(wanted.toTypedArray())
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                Log.w(TotemRuntime.TAG, "[KIOSCO] ${KioskUrls.redact(message.message())}")
            }
            return true
        }
    }

    private fun allowedOrigin(origin: String): Boolean {
        val page = KioskUrls.origin(origin) ?: return false
        if (page == KioskUrls.origin(loadedUrl.orEmpty())) return true
        val host = page.substringAfter("://").substringBefore(':')
        return ZOOM_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** Zoom y la teleconsulta necesitan cámara y micrófono; se piden antes de que la página los quiera. */
    private fun askPermissions() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filterNot(::granted)
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        MonitorService.promote(this)
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    /** Sin URL del kiosco: lo dice y deja ir a la configuración. */
    private fun buildEmptyView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(
            TextView(context).apply {
                setText(R.string.kiosk_missing_url)
                setTextColor(getColor(R.color.vital_on))
                textSize = 20f
                gravity = Gravity.CENTER
            },
        )
        addView(
            Button(context).apply {
                setText(R.string.kiosk_open_settings)
                setOnClickListener { startActivity(Intent(context, MainActivity::class.java)) }
            },
        )
    }

    /** La esquina de arriba a la izquierda: cinco toques en 3 s piden el PIN para ir a la configuración. */
    private fun buildCorner(): View = View(this).apply {
        val size = (CORNER_DP * resources.displayMetrics.density).toInt()
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)
        setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - firstTapAt > TAPS_WINDOW_MS) {
                firstTapAt = now
                cornerTaps = 0
            }
            cornerTaps++
            if (cornerTaps >= TAPS_TO_EXIT) {
                cornerTaps = 0
                askPin()
            }
        }
    }

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setHint(R.string.kiosk_pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.kiosk_exit_title)
            .setView(input)
            .setNegativeButton(R.string.kiosk_cancel, null)
            .setPositiveButton(R.string.kiosk_enter) { _, _ ->
                if (pinAccepted(input.text?.toString(), runtime().config.localPanelPin)) {
                    startActivity(Intent(this, MainActivity::class.java))
                }
            }
            .show()
    }

    companion object {
        private const val CORNER_DP = 64
        private const val TAPS_TO_EXIT = 5
        private const val TAPS_WINDOW_MS = 3_000L

        /** Los orígenes de Zoom Video SDK que pueden pedir cámara y micrófono además del kiosco. */
        private val ZOOM_HOSTS = listOf("zoom.us", "zoom.com", "zoomgov.com")
    }
}
