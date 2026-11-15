// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.guardiavirtual.berrymed.BerryMedApp
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.service.MonitorService
import com.guardiavirtual.berrymed.service.TotemRuntime
import com.guardiavirtual.berrymed.tele.KioskUrls
import com.guardiavirtual.berrymed.ui.status.ModeSwitchDialog
import com.guardiavirtual.berrymed.ui.status.NoWifiNotice
import com.guardiavirtual.berrymed.ui.status.PanelMode
import com.guardiavirtual.berrymed.ui.status.StatusPanelActions
import com.guardiavirtual.berrymed.ui.status.StatusPanelUi
import com.guardiavirtual.berrymed.ui.status.StatusPanelView
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * El kiosco de OnDoctor dentro de Berry Med, como lo hacía la app del
 * proveedor: un `WebView` a pantalla completa con la URL del kiosco. Es la app
 * de inicio de la tablet, así Android la abre al encender y con ella adelante
 * el servicio tiene cámara y micrófono para la teleconsulta.
 *
 * También se entra con el ícono ONDOCTOR365 de la tablet (un alias de esta
 * pantalla en el manifest) y con el botón ONDOCTOR365 de la pantalla
 * principal, sin PIN: es la pantalla del paciente. Tiene su propia tarea, así
 * el ícono de Berry Med muestra la configuración y volver al kiosco no lo
 * recrea ni recarga la página.
 *
 * Cada dirección que carga el kiosco pasa por [TotemRuntime.onKioskUrl]: la
 * que trae `serverUrl` y `token` hace entrar a la sala del encuentro, y el
 * inicio o el login la hacen salir. El token no se escribe en ningún lado.
 *
 * Atrás no sale: el paciente no tiene que poder irse. Se vuelve a Inicio
 * con la casita del panel lateral o cinco toques en la esquina de arriba a
 * la izquierda, confirmando (sin PIN: el PIN es sólo para la configuración).
 *
 * Si la página no carga (la tablet todavía sin wifi, el servidor sin
 * contestar), Berry Med lo dice en su propia pantalla y reintenta solo: al
 * volver la red, cada 10 s y al volver al kiosco. Desde la casita también se
 * puede recargar la URL del kiosco desde cero.
 *
 * A la derecha va el panel lateral de estado ([StatusPanelView]): el WebView
 * se achica a su lado y la página se acomoda sola. Desde ahí se pasa al Panel
 * Local, que pide su PIN.
 */
class KioskActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    /** El lado izquierdo: el WebView, `empty`, `offline` y la esquina. El panel lateral queda afuera. */
    private lateinit var root: FrameLayout
    private lateinit var statusPanel: StatusPanelView
    private var modeDialog: ModeSwitchDialog? = null
    private var homeDialog: AlertDialog? = null
    private val permissions = AppPermissionAsker(this)
    private val noWifi = NoWifiNotice(this)
    private lateinit var empty: LinearLayout
    private lateinit var offline: LinearLayout
    /** El cartel del otoscopio, arriba de la página: la teleconsulta espera su imagen. */
    private lateinit var otoscopeNotice: TextView
    private var loadedUrl: String? = null
    private var cornerTaps = 0
    private var firstTapAt = 0L

    /** La página principal no cargó: la pantalla de sin conexión queda puesta hasta que cargue. */
    private var failed = false
    private val retryLater = Runnable { retry() }
    private val loadLater = Runnable { loadConfiguredUrl() }

    /** El panel lateral se refresca cada medio segundo mientras el kiosco se ve. */
    private val statusTick = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, STATUS_MS)
        }
    }

    /** Android avisa en otro hilo; el reintento va al principal. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { retry() }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) handler.post { retry() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java))
        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        statusPanel = buildStatusPanel()
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(getColor(R.color.bg))
                addView(root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(statusPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
        empty = buildEmptyView()
        root.addView(empty)
        offline = buildOfflineView()
        // Arriba del WebView y debajo de la esquina: los cinco toques siguen andando con ella puesta.
        root.addView(offline)
        otoscopeNotice = buildOtoscopeNotice()
        root.addView(otoscopeNotice)
        root.addView(buildCorner())
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                // El paciente no sale del kiosco con atrás.
                override fun handleOnBackPressed() = Unit
            },
        )
        // Cámara y micrófono para Zoom y la teleconsulta, la ubicación para el nombre del Wi-Fi y las notificaciones.
        permissions.askMissingOnOpen()
    }

    override fun onStart() {
        super.onStart()
        // Con el kiosco adelante el otoscopio y el estetoscopio quedan encendidos: sólo energía, no transmiten.
        runtime().setKioskVisible(true)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        // Abierto o en el riel, como se dejó en el Panel Local.
        statusPanel.setOpen(runtime().statusPanelOpen)
        noWifi.start()
        handler.removeCallbacks(statusTick)
        statusTick.run()
    }

    override fun onStop() {
        // Con otra pantalla adelante no se reintenta: al volver, show() lo retoma.
        handler.removeCallbacks(retryLater)
        handler.removeCallbacks(loadLater)
        handler.removeCallbacks(statusTick)
        modeDialog?.dismiss()
        // Al volver al kiosco no tiene que quedar abierto un «¿Volver al inicio?» de antes.
        homeDialog?.dismiss()
        noWifi.stop()
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
            // No estaba registrado: nada que sacar.
        }
        runtime().setKioskVisible(false)
        super.onStop()
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

    // El panel lateral a la escala de diseño; la página web queda como está (ver UiScale).
    private fun buildStatusPanel(): StatusPanelView = StatusPanelView(UiScale.wrap(this)).apply {
        onToggle = { open ->
            runtime().statusPanelOpen = open
            renderStatus()
        }
        onAudioChoice = { choice -> runtime().setCallAudioOutput(choice) }
        onAction = { action -> StatusPanelActions.handle(this@KioskActivity, runtime(), permissions, action) }
        onSwitchMode = { askSwitchToLocal() }
        // La casita: a Inicio confirmando, como los cinco toques.
        onHome = { askHome() }
    }

    private fun renderStatus() {
        val snapshot = runtime().statusSnapshot()
        statusPanel.render(StatusPanelUi.build(snapshot, PanelMode.REMOTE))
        otoscopeNotice.visibility = if (runtime().tele.status.waitingImage) View.VISIBLE else View.GONE
        noWifi.update(
            connected = snapshot.wifi.connected,
            blocked = modeDialog?.showing == true || homeDialog?.isShowing == true,
        )
    }

    /**
     * Remoto → Local: con una consulta en curso el botón está bloqueado; si
     * no, se confirma y se abre el Panel Local, sin PIN.
     */
    private fun askSwitchToLocal() {
        if (runtime().modeSwitchBlock() != null) {
            renderStatus()
            return
        }
        modeDialog?.dismiss()
        modeDialog = ModeSwitchDialog.show(
            this,
            title = R.string.mode_to_local_title,
            body = R.string.mode_to_local_body,
            action = R.string.mode_to_local_action,
        ) {
            // La consulta pudo haber empezado con el diálogo abierto.
            if (runtime().modeSwitchBlock() == null) startActivity(Intent(this, LocalPanelActivity::class.java))
        }
    }

    /** La URL guardada; si cambió en la configuración, se vuelve a cargar. */
    private fun loadConfiguredUrl() {
        handler.removeCallbacks(loadLater)
        thread(name = "berry-kiosk-config") {
            val (saved, loaded) = try {
                Pair(runBlocking { runtime().store.load() }.kioskUrl.trim(), true)
            } catch (error: Exception) {
                Log.e(TotemRuntime.TAG, "[KIOSCO] No se pudo leer la configuración: $error")
                // Lo que tiene el runtime vale sólo si ya leyó la configuración: si no, son los valores de fábrica.
                Pair(runtime().config.kioskUrl.trim(), runtime().configLoaded)
            }
            handler.post { if (!isDestroyed) show(saved, loaded) }
        }
    }

    /**
     * El kiosco se muestra si está configurado; si no, pasa a la
     * configuración. Sin la configuración leída, se reintenta en un segundo
     * ([HomeTarget]).
     */
    private fun show(url: String, loaded: Boolean) {
        val target = HomeTarget.of(loaded, url)
        if (target != HomeTarget.KIOSK) {
            empty.visibility = if (target == HomeTarget.SETTINGS) View.VISIBLE else View.GONE
            webView?.visibility = View.GONE
            hideOffline()
            if (target == HomeTarget.WAIT) {
                handler.postDelayed(loadLater, CONFIG_RETRY_MS)
            } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                runtime().log("[KIOSCO] Sin URL del kiosco: se abre la configuración")
                startActivity(configIntent())
            }
            return
        }
        empty.visibility = View.GONE
        val view = webView ?: createWebView().also { webView = it }
        view.visibility = View.VISIBLE
        if (url != loadedUrl) {
            loadedUrl = url
            runtime().log("[KIOSCO] Se abre ${KioskUrls.origin(url)}")
            view.loadUrl(url)
        } else if (failed) {
            retry()
        }
    }

    /** Vuelve a pedir la dirección que falló, no la del inicio: si falló en medio del flujo, sigue ahí. */
    private fun retry() {
        val view = webView ?: return
        if (!failed) return
        runtime().log("[KIOSCO] Se reintenta")
        view.reload()
    }

    /** Recargar del diálogo del PIN: vuelve a empezar desde la URL guardada, como cambiarla y guardarla. */
    private fun reloadConfigured() {
        runtime().log("[KIOSCO] Se recarga a pedido")
        loadedUrl = null
        loadConfiguredUrl()
    }

    private fun hideOffline() {
        failed = false
        handler.removeCallbacks(retryLater)
        offline.visibility = View.GONE
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
            // Zoom mira el navegador: se presenta como Chrome para Android (ver KioskUserAgent).
            userAgentString = KioskUserAgent.asChrome(userAgentString)
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
            // La pantalla de sin conexión sigue puesta hasta saber cómo termina esta carga.
            failed = false
            handler.removeCallbacks(retryLater)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!KioskLoadFailure.matters(request.isForMainFrame)) return
            failed = true
            runtime().log("[KIOSCO] No se pudo abrir ${KioskUrls.origin(request.url.toString())} (${error.description}): se reintenta solo")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!KioskLoadFailure.matters(request.isForMainFrame, response.statusCode)) return
            failed = true
            runtime().log("[KIOSCO] No se pudo abrir ${KioskUrls.origin(request.url.toString())} (HTTP ${response.statusCode}): se reintenta solo")
        }

        /** Llega después de los errores, también para la página de error de Android, que queda tapada. */
        override fun onPageFinished(view: WebView, url: String) {
            if (failed) {
                offline.visibility = View.VISIBLE
                handler.removeCallbacks(retryLater)
                handler.postDelayed(retryLater, KioskLoadFailure.RETRY_MS)
            } else {
                hideOffline()
            }
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
            hideOffline()
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
                setTextColor(getColor(R.color.text))
                textSize = 20f
                gravity = Gravity.CENTER
            },
        )
        addView(
            Button(context).apply {
                setText(R.string.kiosk_open_settings)
                setOnClickListener { startActivity(configIntent()) }
            },
        )
    }

    /** La página no cargó: lo dice Berry Med, no Android, y deja reintentar a mano. Tapa el WebView entero. */
    private fun buildOfflineView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        setBackgroundColor(getColor(R.color.bg))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(
            TextView(context).apply {
                setText(R.string.kiosk_offline)
                setTextColor(getColor(R.color.text))
                textSize = 20f
                gravity = Gravity.CENTER
            },
        )
        addView(
            Button(context).apply {
                setText(R.string.kiosk_retry)
                setOnClickListener { retry() }
            },
        )
    }

    /**
     * Mientras la teleconsulta espera la imagen del otoscopio: el otoscopio
     * arranca en su menú y alguien tiene que elegir «Computer». Con el estilo
     * de las tarjetas del panel; se va solo cuando llega la imagen o se corta.
     */
    private fun buildOtoscopeNotice(): TextView {
        val scaled = UiScale.wrap(this)
        val density = scaled.resources.displayMetrics.density
        return TextView(scaled).apply {
            setText(R.string.kiosk_otoscope_waiting)
            setTextColor(getColor(R.color.text))
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.panel_card)
            val padH = (20 * density).toInt()
            val padV = (14 * density).toInt()
            setPadding(padH, padV, padH, padV)
            elevation = 6 * density
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = (24 * density).toInt() }
        }
    }

    /** La esquina de arriba a la izquierda: cinco toques en 3 s llevan a Inicio. */
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
                askHome(technician = true)
            }
        }
    }

    /**
     * La casita y los cinco toques: a Inicio, sin PIN (el PIN es sólo para
     * la configuración). Se confirma igual, para que un toque sin querer no
     * saque al paciente del kiosco, y deja Recargar a mano. Con una consulta
     * en curso (signos enviando o la videollamada en la sala) la casita no
     * deja ni una cosa ni la otra: cortaría la consulta, igual que cambiar de
     * modo. Los cinco toques ([technician]) sí: son la salida del técnico si
     * una consulta quedó trabada.
     */
    private fun askHome(technician: Boolean = false) {
        homeDialog?.dismiss()
        val blocked = runtime().modeSwitchBlock().takeUnless { technician }
        if (blocked != null) {
            homeDialog = AlertDialog.Builder(this)
                .setTitle(R.string.kiosk_home_blocked_title)
                .setMessage(blocked)
                .setPositiveButton(R.string.kiosk_home_ok, null)
                .show()
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.kiosk_home_title)
            .setMessage(R.string.kiosk_home_body)
            .setNegativeButton(R.string.kiosk_cancel, null)
            // La consulta pudo haber empezado con el diálogo abierto: se vuelve a mirar al tocar.
            .setNeutralButton(R.string.kiosk_reload) { _, _ ->
                if (technician || runtime().modeSwitchBlock() == null) reloadConfigured() else askHome()
            }
            .setPositiveButton(R.string.home_from_local_action) { _, _ ->
                if (technician || runtime().modeSwitchBlock() == null) {
                    startActivity(Intent(this, HomeActivity::class.java))
                } else {
                    askHome()
                }
            }
            .create()
        homeDialog = dialog
        dialog.show()
    }

    private fun configIntent() =
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CONFIG, true)

    companion object {
        private const val CORNER_DP = 64
        private const val TAPS_TO_EXIT = 5
        private const val TAPS_WINDOW_MS = 3_000L
        private const val STATUS_MS = 500L
        private const val CONFIG_RETRY_MS = 1_000L

        /** Los orígenes de Zoom Video SDK que pueden pedir cámara y micrófono además del kiosco. */
        private val ZOOM_HOSTS = listOf("zoom.us", "zoom.com", "zoomgov.com")
    }
}
